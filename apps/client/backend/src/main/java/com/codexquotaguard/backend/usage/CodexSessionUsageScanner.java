package com.codexquotaguard.backend.usage;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Read-only incremental extractor for token measurements in an explicitly configured Codex home directory.
 *
 * <p>Only JSONL files recursively beneath {@code CODEX_HOME/sessions} are considered. The scanner never discovers a default
 * Codex home, never reads authentication material, and never turns locally measured token counts into
 * account-quota percentages.</p>
 *
 * <p>Current rollout files write a per-response {@code token_usage_record}; that is the authoritative
 * source used here. Legacy {@code token_count} snapshots are only a delayed fallback when a whole file
 * has no direct records, and only monotonic positive deltas from {@code total_token_usage} are accepted.
 * The repeated {@code last_token_usage} snapshot is intentionally not summed.</p>
 */
public final class CodexSessionUsageScanner {

    private static final int PREFIX_LIMIT_BYTES = 4 * 1024;
    private static final int MAX_JSONL_LINE_BYTES = 4 * 1024 * 1024;
    private static final Duration DEFAULT_FALLBACK_SETTLING_DELAY = Duration.ofSeconds(30);

    private final Path sessionsDirectory;
    private final Clock clock;
    private final Duration fallbackSettlingDelay;
    private final ObjectMapper objectMapper;

    /**
     * Creates a scanner for one caller-selected {@code CODEX_HOME}. No user-home default is used.
     */
    public CodexSessionUsageScanner(Path isolatedCodexHome) {
        this(isolatedCodexHome, Clock.systemUTC(), DEFAULT_FALLBACK_SETTLING_DELAY);
    }

    CodexSessionUsageScanner(Path isolatedCodexHome, Clock clock, Duration fallbackSettlingDelay) {
        Path normalizedHome = Objects.requireNonNull(isolatedCodexHome, "isolatedCodexHome is required.")
                .toAbsolutePath()
                .normalize();
        this.sessionsDirectory = normalizedHome.resolve("sessions");
        this.clock = Objects.requireNonNull(clock, "clock is required.");
        this.fallbackSettlingDelay = Objects.requireNonNull(fallbackSettlingDelay, "fallbackSettlingDelay is required.");
        if (fallbackSettlingDelay.isNegative()) {
            throw new IllegalArgumentException("fallbackSettlingDelay must not be negative.");
        }
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Establishes byte cursors for existing files without parsing historical
     * JSONL. Real Codex homes may contain gigabytes of old sessions.
     */
    public CodexUsageScanState baseline() {
        if (!Files.isDirectory(sessionsDirectory, LinkOption.NOFOLLOW_LINKS)) {
            return CodexUsageScanState.empty();
        }
        Map<String, CodexUsageFileCursor> files = new LinkedHashMap<>();
        try (Stream<Path> paths = Files.walk(sessionsDirectory)) {
            List<Path> jsonl = paths.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(this::isJsonl)
                    .sorted(Comparator.comparing(this::relativePath))
                    .toList();
            for (Path file : jsonl) {
                BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class,
                        LinkOption.NOFOLLOW_LINKS);
                FileSignature signature = signature(file, attributes, null);
                files.put(relativePath(file), new CodexUsageFileCursor(
                        signature.identityDigest(), signature.prefixLength(), signature.prefixDigest(),
                        attributes.size(), false, null, List.of()));
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Codex usage baseline could not be established.");
        }
        return new CodexUsageScanState(files, Set.of());
    }

    /**
     * Scans complete JSONL lines appended after the cursors in {@code previousState}.
     *
     * <p>Commit {@link CodexUsageScanResult#nextState()} only after this result's events are safely captured
     * in a durable local outbox or accepted by an idempotent receiver. Event IDs are opaque stable hashes so
     * a retry from the prior state can be de-duplicated upstream without receiving a raw Codex response
     * identifier.</p>
     */
    public CodexUsageScanResult scan(CodexUsageScanState previousState) {
        CodexUsageScanState state = previousState == null ? CodexUsageScanState.empty() : previousState;
        List<CodexUsageEvent> events = new ArrayList<>();
        List<CodexUsageScanIssue> issues = new ArrayList<>();
        Map<String, CodexUsageFileCursor> nextFiles = new LinkedHashMap<>(state.files());
        Set<String> emittedEventIds = new LinkedHashSet<>(state.emittedEventIds());

        if (!Files.isDirectory(sessionsDirectory, LinkOption.NOFOLLOW_LINKS)) {
            issues.add(new CodexUsageScanIssue(CodexUsageScanIssueCode.SESSIONS_DIRECTORY_UNAVAILABLE));
            return result(events, nextFiles, emittedEventIds, issues);
        }

        List<Path> files;
        try (Stream<Path> pathStream = Files.walk(sessionsDirectory)) {
            files = pathStream
                    .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(this::isJsonl)
                    .sorted(Comparator.comparing(this::relativePath))
                    .toList();
        } catch (IOException exception) {
            issues.add(new CodexUsageScanIssue(CodexUsageScanIssueCode.SESSIONS_DIRECTORY_UNAVAILABLE));
            return result(events, nextFiles, emittedEventIds, issues);
        }

        Set<String> pendingEventIds = pendingEventIds(nextFiles);
        for (Path file : files) {
            String relativePath = relativePath(file);
            CodexUsageFileCursor priorCursor = state.files().get(relativePath);
            FileScanOutcome outcome = scanFile(file, priorCursor, emittedEventIds, pendingEventIds);
            issues.addAll(outcome.issues());
            if (!outcome.completed()) {
                continue;
            }
            nextFiles.put(relativePath, outcome.cursor());
            emittedEventIds.addAll(outcome.newEventIds());
            events.addAll(outcome.events());
        }

        return result(events, nextFiles, emittedEventIds, issues);
    }

    private CodexUsageScanResult result(
            List<CodexUsageEvent> events,
            Map<String, CodexUsageFileCursor> files,
            Set<String> emittedEventIds,
            List<CodexUsageScanIssue> issues
    ) {
        events.sort(Comparator.comparing(CodexUsageEvent::occurredAt).thenComparing(CodexUsageEvent::eventId));
        return new CodexUsageScanResult(
                events,
                new CodexUsageScanState(files, emittedEventIds),
                issues
        );
    }

    private FileScanOutcome scanFile(
            Path file,
            CodexUsageFileCursor priorCursor,
            Set<String> emittedEventIds,
            Set<String> pendingEventIds
    ) {
        List<CodexUsageScanIssue> issues = new ArrayList<>();
        BasicFileAttributes attributes;
        try {
            attributes = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException exception) {
            return FileScanOutcome.unreadable();
        }

        FileSignature signature;
        try {
            signature = signature(file, attributes, priorCursor);
        } catch (IOException exception) {
            return FileScanOutcome.unreadable();
        }

        boolean reset = isReplacementOrTruncation(priorCursor, signature, attributes.size());
        MutableCursor cursor = reset
                ? MutableCursor.fresh(signature)
                : MutableCursor.from(priorCursor, signature);
        Set<String> newEventIds = new LinkedHashSet<>();
        List<CodexUsageEvent> events = new ArrayList<>();

        try {
            cursor.nextByteOffset = readCompleteLines(
                    file,
                    cursor.nextByteOffset,
                    new JsonlLineConsumer() {
                        @Override
                        public void completeLine(byte[] line) {
                            processLine(line, cursor, emittedEventIds, pendingEventIds, newEventIds, events, issues);
                        }

                        @Override
                        public void oversizedLine() {
                            issues.add(new CodexUsageScanIssue(CodexUsageScanIssueCode.OVERSIZED_JSONL_RECORD));
                        }
                    }
            );
        } catch (IOException exception) {
            return FileScanOutcome.unreadable();
        }

        flushSettledFallbackEvents(cursor, attributes.lastModifiedTime().toInstant(), emittedEventIds, newEventIds, events);
        return FileScanOutcome.complete(cursor.toImmutable(), events, newEventIds, issues);
    }

    private FileSignature signature(Path file, BasicFileAttributes attributes, CodexUsageFileCursor priorCursor) throws IOException {
        String identitySource = attributes.fileKey() == null
                ? "created:" + attributes.creationTime().toMillis()
                : "file-key:" + attributes.fileKey();
        String identityDigest = digest(identitySource.getBytes(StandardCharsets.UTF_8));

        int requestedPrefixLength = priorCursor != null && priorCursor.prefixLength() > 0
                ? priorCursor.prefixLength()
                : (int) Math.min(PREFIX_LIMIT_BYTES, attributes.size());
        if (attributes.size() < requestedPrefixLength) {
            requestedPrefixLength = (int) attributes.size();
        }
        return new FileSignature(identityDigest, requestedPrefixLength, digestPrefix(file, requestedPrefixLength));
    }

    private boolean isReplacementOrTruncation(
            CodexUsageFileCursor priorCursor,
            FileSignature signature,
            long currentSize
    ) {
        if (priorCursor == null) {
            return true;
        }
        if (!priorCursor.fileIdentityDigest().equals(signature.identityDigest())) {
            return true;
        }
        if (currentSize < priorCursor.nextByteOffset() || currentSize < priorCursor.prefixLength()) {
            return true;
        }
        return priorCursor.prefixLength() > 0 && !priorCursor.prefixDigest().equals(signature.prefixDigest());
    }

    private long readCompleteLines(Path file, long startOffset, JsonlLineConsumer consumer) throws IOException {
        long lastCompleteOffset = startOffset;
        long currentOffset = startOffset;
        boolean discardingOversizedLine = false;
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        ByteBuffer buffer = ByteBuffer.allocate(16 * 1024);

        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            channel.position(startOffset);
            int bytesRead;
            while ((bytesRead = channel.read(buffer)) > 0) {
                buffer.flip();
                while (buffer.hasRemaining()) {
                    byte next = buffer.get();
                    currentOffset++;
                    if (next == '\n') {
                        if (discardingOversizedLine) {
                            consumer.oversizedLine();
                            discardingOversizedLine = false;
                        } else {
                            consumer.completeLine(trimTrailingCarriageReturn(line.toByteArray()));
                        }
                        line.reset();
                        lastCompleteOffset = currentOffset;
                        continue;
                    }
                    if (!discardingOversizedLine) {
                        line.write(next);
                        if (line.size() > MAX_JSONL_LINE_BYTES) {
                            line.reset();
                            discardingOversizedLine = true;
                        }
                    }
                }
                buffer.clear();
            }
        }
        return lastCompleteOffset;
    }

    private void processLine(
            byte[] lineBytes,
            MutableCursor cursor,
            Set<String> emittedEventIds,
            Set<String> pendingEventIds,
            Set<String> newEventIds,
            List<CodexUsageEvent> events,
            List<CodexUsageScanIssue> issues
    ) {
        if (lineBytes.length == 0) {
            return;
        }
        String line = new String(lineBytes, StandardCharsets.UTF_8);
        JsonNode record;
        try {
            record = objectMapper.readTree(line);
        } catch (JacksonException exception) {
            issues.add(new CodexUsageScanIssue(CodexUsageScanIssueCode.MALFORMED_JSONL_RECORD));
            return;
        }
        if (record == null || !record.isObject()) {
            issues.add(new CodexUsageScanIssue(CodexUsageScanIssueCode.MALFORMED_JSONL_RECORD));
            return;
        }

        String type = text(record, "type");
        if ("token_usage_record".equals(type)) {
            processDirectUsageRecord(record, line, cursor, emittedEventIds, newEventIds, events, issues);
            return;
        }
        if ("event_msg".equals(type) && "token_count".equals(text(record.path("payload"), "type"))) {
            processCumulativeSnapshot(record, line, cursor, emittedEventIds, pendingEventIds, issues);
        }
    }

    private void processDirectUsageRecord(
            JsonNode record,
            String line,
            MutableCursor cursor,
            Set<String> emittedEventIds,
            Set<String> newEventIds,
            List<CodexUsageEvent> events,
            List<CodexUsageScanIssue> issues
    ) {
        JsonNode payload = record.path("payload");
        CodexCumulativeUsage usage = usage(payload.path("usage"));
        Instant occurredAt = timestamp(record);
        if (usage == null || occurredAt == null) {
            issues.add(new CodexUsageScanIssue(CodexUsageScanIssueCode.INVALID_USAGE_RECORD));
            return;
        }

        cursor.directUsageRecordObserved = true;
        cursor.pendingFallbackEvents.clear();
        String responseId = text(payload, "response_id");
        String eventIdentity = responseId == null ? "record:" + line : "response:" + responseId;
        CodexUsageEvent event = event("codex-local-usage:v1:" + digest(eventIdentity.getBytes(StandardCharsets.UTF_8)), occurredAt, usage,
                CodexUsageEventSource.TOKEN_USAGE_RECORD);
        appendIfNew(event, emittedEventIds, newEventIds, events);
    }

    private void processCumulativeSnapshot(
            JsonNode record,
            String line,
            MutableCursor cursor,
            Set<String> emittedEventIds,
            Set<String> pendingEventIds,
            List<CodexUsageScanIssue> issues
    ) {
        if (cursor.directUsageRecordObserved) {
            return;
        }
        JsonNode info = record.path("payload").path("info");
        CodexCumulativeUsage current = usage(info.path("total_token_usage"));
        if (current == null) {
            return;
        }
        if (cursor.cumulativeUsage == null) {
            cursor.cumulativeUsage = current;
            return;
        }
        if (!current.isAtLeast(cursor.cumulativeUsage)) {
            cursor.cumulativeUsage = current;
            issues.add(new CodexUsageScanIssue(CodexUsageScanIssueCode.CUMULATIVE_COUNTER_RESET));
            return;
        }

        CodexCumulativeUsage delta = current.minus(cursor.cumulativeUsage);
        cursor.cumulativeUsage = current;
        if (delta.isZero()) {
            return;
        }
        Instant occurredAt = timestamp(record);
        if (occurredAt == null) {
            issues.add(new CodexUsageScanIssue(CodexUsageScanIssueCode.INVALID_USAGE_RECORD));
            return;
        }

        CodexUsageEvent event = event(
                "codex-local-cumulative:v1:" + digest(line.getBytes(StandardCharsets.UTF_8)),
                occurredAt,
                delta,
                CodexUsageEventSource.CUMULATIVE_TOKEN_COUNT_FALLBACK
        );
        if (!emittedEventIds.contains(event.eventId()) && pendingEventIds.add(event.eventId())) {
            cursor.pendingFallbackEvents.add(new CodexPendingUsageEvent(event));
        }
    }

    private void flushSettledFallbackEvents(
            MutableCursor cursor,
            Instant fileLastModifiedAt,
            Set<String> emittedEventIds,
            Set<String> newEventIds,
            List<CodexUsageEvent> events
    ) {
        if (cursor.directUsageRecordObserved || cursor.pendingFallbackEvents.isEmpty()) {
            return;
        }
        Instant cutoff = clock.instant().minus(fallbackSettlingDelay);
        if (fileLastModifiedAt.isAfter(cutoff)) {
            return;
        }
        for (CodexPendingUsageEvent pending : cursor.pendingFallbackEvents) {
            appendIfNew(pending.event(), emittedEventIds, newEventIds, events);
        }
        cursor.pendingFallbackEvents.clear();
    }

    private void appendIfNew(
            CodexUsageEvent event,
            Set<String> emittedEventIds,
            Set<String> newEventIds,
            List<CodexUsageEvent> events
    ) {
        if (!emittedEventIds.contains(event.eventId()) && newEventIds.add(event.eventId())) {
            events.add(event);
        }
    }

    private CodexCumulativeUsage usage(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        Long totalTokens = nonNegativeLong(node, "total_tokens");
        if (totalTokens == null) {
            return null;
        }
        Long inputTokens = optionalNonNegativeLong(node, "input_tokens");
        Long cachedInputTokens = optionalNonNegativeLong(node, "cached_input_tokens");
        Long cacheWriteInputTokens = optionalNonNegativeLong(node, "cache_write_input_tokens");
        Long outputTokens = optionalNonNegativeLong(node, "output_tokens");
        Long reasoningOutputTokens = optionalNonNegativeLong(node, "reasoning_output_tokens");
        if (inputTokens == null || cachedInputTokens == null || cacheWriteInputTokens == null
                || outputTokens == null || reasoningOutputTokens == null) {
            return null;
        }
        return new CodexCumulativeUsage(
                inputTokens,
                cachedInputTokens,
                cacheWriteInputTokens,
                outputTokens,
                reasoningOutputTokens,
                totalTokens
        );
    }

    private Long nonNegativeLong(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isIntegralNumber()) {
            return null;
        }
        var number = value.asLongOpt();
        if (number.isEmpty() || number.getAsLong() < 0) {
            return null;
        }
        return number.getAsLong();
    }

    private Long optionalNonNegativeLong(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return 0L;
        }
        return nonNegativeLong(node, field);
    }

    private String text(JsonNode node, String field) {
        if (node == null || !node.isObject()) {
            return null;
        }
        JsonNode value = node.get(field);
        if (value == null || !value.isString()) {
            return null;
        }
        String text = value.asString();
        return text == null || text.isBlank() ? null : text;
    }

    private Instant timestamp(JsonNode record) {
        String timestamp = text(record, "timestamp");
        if (timestamp == null) {
            return null;
        }
        try {
            return Instant.parse(timestamp);
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private CodexUsageEvent event(
            String eventId,
            Instant occurredAt,
            CodexCumulativeUsage usage,
            CodexUsageEventSource source
    ) {
        return new CodexUsageEvent(
                eventId,
                occurredAt,
                usage.inputTokens(),
                usage.cachedInputTokens(),
                usage.cacheWriteInputTokens(),
                usage.outputTokens(),
                usage.reasoningOutputTokens(),
                usage.totalTokens(),
                source
        );
    }

    private Set<String> pendingEventIds(Map<String, CodexUsageFileCursor> files) {
        Set<String> result = new LinkedHashSet<>();
        for (CodexUsageFileCursor cursor : files.values()) {
            for (CodexPendingUsageEvent pending : cursor.pendingFallbackEvents()) {
                result.add(pending.event().eventId());
            }
        }
        return result;
    }

    private boolean isJsonl(Path path) {
        Path fileName = path.getFileName();
        return fileName != null && fileName.toString().toLowerCase(java.util.Locale.ROOT).endsWith(".jsonl");
    }

    private String relativePath(Path path) {
        return sessionsDirectory.relativize(path).toString().replace('\\', '/');
    }

    private String digestPrefix(Path file, int prefixLength) throws IOException {
        if (prefixLength == 0) {
            return digest(new byte[0]);
        }
        ByteBuffer bytes = ByteBuffer.allocate(prefixLength);
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            while (bytes.hasRemaining() && channel.read(bytes) >= 0) {
                // Continue until the requested stable prefix has been read or EOF is reached.
            }
        }
        if (bytes.position() != prefixLength) {
            throw new IOException("JSONL file changed while its prefix was read.");
        }
        return digest(bytes.array());
    }

    private String digest(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime.", exception);
        }
    }

    private static byte[] trimTrailingCarriageReturn(byte[] line) {
        if (line.length > 0 && line[line.length - 1] == '\r') {
            byte[] trimmed = new byte[line.length - 1];
            System.arraycopy(line, 0, trimmed, 0, trimmed.length);
            return trimmed;
        }
        return line;
    }

    @FunctionalInterface
    private interface JsonlLineConsumer {
        void completeLine(byte[] line);

        default void oversizedLine() {
            // Implemented by the adapter below when a scan has diagnostics to collect.
        }
    }

    private record FileSignature(String identityDigest, int prefixLength, String prefixDigest) {
    }

    private static final class MutableCursor {
        private final String fileIdentityDigest;
        private int prefixLength;
        private String prefixDigest;
        private long nextByteOffset;
        private boolean directUsageRecordObserved;
        private CodexCumulativeUsage cumulativeUsage;
        private final List<CodexPendingUsageEvent> pendingFallbackEvents;

        private MutableCursor(
                String fileIdentityDigest,
                int prefixLength,
                String prefixDigest,
                long nextByteOffset,
                boolean directUsageRecordObserved,
                CodexCumulativeUsage cumulativeUsage,
                List<CodexPendingUsageEvent> pendingFallbackEvents
        ) {
            this.fileIdentityDigest = fileIdentityDigest;
            this.prefixLength = prefixLength;
            this.prefixDigest = prefixDigest;
            this.nextByteOffset = nextByteOffset;
            this.directUsageRecordObserved = directUsageRecordObserved;
            this.cumulativeUsage = cumulativeUsage;
            this.pendingFallbackEvents = new ArrayList<>(pendingFallbackEvents);
        }

        private static MutableCursor fresh(FileSignature signature) {
            return new MutableCursor(signature.identityDigest(), signature.prefixLength(), signature.prefixDigest(), 0, false, null, List.of());
        }

        private static MutableCursor from(CodexUsageFileCursor cursor, FileSignature signature) {
            int prefixLength = cursor.prefixLength() == 0 ? signature.prefixLength() : cursor.prefixLength();
            String prefixDigest = cursor.prefixLength() == 0 ? signature.prefixDigest() : cursor.prefixDigest();
            return new MutableCursor(
                    signature.identityDigest(),
                    prefixLength,
                    prefixDigest,
                    cursor.nextByteOffset(),
                    cursor.directUsageRecordObserved(),
                    cursor.cumulativeUsage(),
                    cursor.pendingFallbackEvents()
            );
        }

        private CodexUsageFileCursor toImmutable() {
            return new CodexUsageFileCursor(
                    fileIdentityDigest,
                    prefixLength,
                    prefixDigest,
                    nextByteOffset,
                    directUsageRecordObserved,
                    cumulativeUsage,
                    pendingFallbackEvents
            );
        }
    }

    private record FileScanOutcome(
            boolean completed,
            CodexUsageFileCursor cursor,
            List<CodexUsageEvent> events,
            Set<String> newEventIds,
            List<CodexUsageScanIssue> issues
    ) {

        private static FileScanOutcome complete(
                CodexUsageFileCursor cursor,
                List<CodexUsageEvent> events,
                Set<String> newEventIds,
                List<CodexUsageScanIssue> issues
        ) {
            return new FileScanOutcome(true, cursor, List.copyOf(events), Set.copyOf(newEventIds), List.copyOf(issues));
        }

        private static FileScanOutcome unreadable() {
            return new FileScanOutcome(
                    false,
                    null,
                    List.of(),
                    Set.of(),
                    List.of(new CodexUsageScanIssue(CodexUsageScanIssueCode.FILE_UNREADABLE))
            );
        }
    }
}
