package com.codexquotaguard.backend.usage;

import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Reads fixed, bounded audit summaries without changing the private service ACL. */
@Component
public final class RustMeterLogReader {
    private static final int MAX_TAIL_BYTES = 65536;
    private static final Set<String> KINDS = Set.of("SERVICE_STARTED", "SERVICE_STOPPED", "BASELINE_READY",
            "UNPAIRED", "USAGE_REPORTED", "HEARTBEAT", "ERROR", "STATUS_CHANGE");
    private static final Set<String> STATES = Set.of("BASELINE_READY", "UNPAIRED", "UP_TO_DATE", "REPORTED", "ERROR");
    private final ObjectMapper mapper;
    private final Path directory;

    @Autowired
    public RustMeterLogReader(ObjectMapper mapper,
            @Value("${quota-guard.meter.log-directory:}") String configuredDirectory) {
        this.mapper = mapper;
        String programData = System.getenv("ProgramData");
        this.directory = configuredDirectory.isBlank()
                ? (programData == null || programData.isBlank() ? null
                    : Path.of(programData).resolve("CodexQuotaGuard/Meter/logs").toAbsolutePath().normalize())
                : Path.of(configuredDirectory).toAbsolutePath().normalize();
    }

    public LogPage read() {
        if (directory == null) return LogPage.unavailable("NOT_AVAILABLE");
        var entries = new ArrayDeque<Entry>();
        int skipped = 0;
        try {
            if (!Files.readAttributes(directory, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).isDirectory()) {
                return LogPage.unavailable("NOT_AVAILABLE");
            }
            // Audit files rotate from 5 (oldest) to 0 (current). Never read an
            // arbitrary path supplied by the renderer or a full unbounded file.
            for (int index = 5; index >= 0; index--) {
                Path path = directory.resolve(index == 0 ? "meter.jsonl" : "meter." + index + ".jsonl");
                try {
                    if (!Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).isRegularFile()) continue;
                } catch (NoSuchFileException ignored) {
                    continue;
                }
                try (FileChannel file = FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
                    long start = Math.max(0, file.size() - MAX_TAIL_BYTES);
                    file.position(start);
                    ByteBuffer bytes = ByteBuffer.allocate(MAX_TAIL_BYTES);
                    while (bytes.hasRemaining() && file.read(bytes) > 0) { }
                    bytes.flip();
                    String content = StandardCharsets.UTF_8.decode(bytes).toString();
                    if (start > 0) {
                        int newline = content.indexOf('\n');
                        content = newline < 0 ? "" : content.substring(newline + 1);
                    }
                    for (String line : content.split("\n")) {
                        if (line.isBlank()) continue;
                        Entry entry = parse(line);
                        if (entry == null) { skipped++; continue; }
                        if (entries.size() == 50) entries.removeFirst();
                        entries.addLast(entry);
                    }
                }
            }
            return new LogPage("READY", entries.stream()
                    .sorted(Comparator.comparingLong(Entry::atEpochSeconds).reversed()).toList(), skipped);
        } catch (AccessDeniedException | SecurityException ignored) {
            return LogPage.unavailable("ADMIN_REQUIRED");
        } catch (NoSuchFileException ignored) {
            return LogPage.unavailable("NOT_AVAILABLE");
        } catch (Exception ignored) {
            return LogPage.unavailable("READ_FAILED");
        }
    }

    private Entry parse(String line) {
        if (line.length() > 4096) return null;
        try {
            JsonNode node = mapper.readTree(line);
            String kind = node.path("kind").asString("");
            long at = node.path("atEpochSeconds").asLong(0);
            long tokens = node.path("reportedTokens").asLong(-1);
            int count = node.path("reportedEvents").asInt(-1);
            if (!KINDS.contains(kind) || at <= 0 || tokens < 0 || count < 0) return null;
            String state = node.path("state").asString("");
            String error = node.path("errorCode").asString("");
            if (!error.isEmpty() && !error.matches("[A-Z_]{1,64}")) error = "METER_ERROR_REDACTED";
            return new Entry(at, kind, STATES.contains(state) ? state : null, count, tokens,
                    node.path("heartbeatSent").asBoolean(false), error.isEmpty() ? null : error);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    public record Entry(long atEpochSeconds, String kind, String state, int reportedEvents,
            long reportedTokens, boolean heartbeatSent, String errorCode) { }

    public record LogPage(String state, List<Entry> entries, int skippedLines) {
        public LogPage { entries = List.copyOf(entries); }
        static LogPage unavailable(String state) { return new LogPage(state, List.of(), 0); }
    }
}
