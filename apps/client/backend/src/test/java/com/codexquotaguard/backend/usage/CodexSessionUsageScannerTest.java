package com.codexquotaguard.backend.usage;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CodexSessionUsageScannerTest {

    private static final Instant NOW = Instant.parse("2026-10-06T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @TempDir
    Path temporaryDirectory;

    @Test
    void preservesMissingInputAndOutputInsteadOfInventingZeroCostComponents() throws Exception {
        Path home = temporaryDirectory.resolve("partial-breakdown");
        writeSession(home, "run.jsonl", """
                {"type":"token_usage_record","timestamp":"2026-10-05T09:00:00Z","payload":{"response_id":"partial","usage":{"total_tokens":120}}}
                """);
        var first = scanner(home).scan(CodexUsageScanState.empty());
        var event = first.events().get(0);
        assertThat(event.totalTokens()).isEqualTo(120);
        assertThat(event.inputTokens()).isNull();
        assertThat(event.outputTokens()).isNull();
        assertThat(event.cachedInputTokens()).isZero();
        assertThat(event.cacheWriteInputTokens()).isZero();
        assertThat(first.issues()).isEmpty();
        assertThat(scanner(home).scan(first.nextState()).events()).isEmpty();
        var mapper = new tools.jackson.databind.ObjectMapper();
        assertThat(mapper.readValue(mapper.writeValueAsBytes(event), CodexUsageEvent.class)).isEqualTo(event);
    }

    @Test
    void preservesExplicitNullComponentsButRejectsMalformedCounts() throws Exception {
        Path home = temporaryDirectory.resolve("null-breakdown");
        writeSession(home, "run.jsonl", """
                {"type":"token_usage_record","timestamp":"2026-10-05T09:00:00Z","payload":{"response_id":"nulls","usage":{"total_tokens":120,"input_tokens":100,"output_tokens":20,"cached_input_tokens":null,"reasoning_output_tokens":null}}}
                {"type":"token_usage_record","timestamp":"2026-10-05T09:01:00Z","payload":{"response_id":"invalid","usage":{"total_tokens":120,"input_tokens":-1}}}
                """);
        var result = scanner(home).scan(CodexUsageScanState.empty());
        assertThat(result.events()).hasSize(1);
        assertThat(result.events().get(0).cachedInputTokens()).isNull();
        assertThat(result.events().get(0).reasoningOutputTokens()).isNull();
        assertThat(result.issues()).extracting(CodexUsageScanIssue::code)
                .containsExactly(CodexUsageScanIssueCode.INVALID_USAGE_RECORD);
    }

    @Test
    void cumulativeDeltasRequireBothSnapshotsForEachComponentAndSurviveRestarts() throws Exception {
        Path home = temporaryDirectory.resolve("partial-cumulative");
        Path file = writeSession(home, "run.jsonl", """
                {"type":"event_msg","timestamp":"2026-10-05T09:00:00Z","payload":{"type":"token_count","info":{"total_token_usage":{"total_tokens":120}}}}
                """);
        var mapper = new tools.jackson.databind.ObjectMapper();
        var baseline = scanner(home).scan(CodexUsageScanState.empty());
        var restored = mapper.readValue(mapper.writeValueAsBytes(baseline.nextState()), CodexUsageScanState.class);
        Files.writeString(file, """
                {"type":"event_msg","timestamp":"2026-10-05T09:01:00Z","payload":{"type":"token_count","info":{"total_token_usage":{"total_tokens":180,"input_tokens":150,"output_tokens":30}}}}
                {"type":"event_msg","timestamp":"2026-10-05T09:02:00Z","payload":{"type":"token_count","info":{"total_token_usage":{"total_tokens":240,"input_tokens":200,"output_tokens":40}}}}
                """, java.nio.file.StandardOpenOption.APPEND);
        Files.setLastModifiedTime(file, FileTime.from(NOW.minus(Duration.ofHours(1))));
        var result = scanner(home).scan(restored);
        assertThat(result.events()).hasSize(2);
        assertThat(result.events()).extracting(CodexUsageEvent::totalTokens).containsExactly(60L, 60L);
        assertThat(result.events().get(0).inputTokens()).isNull();
        assertThat(result.events().get(0).outputTokens()).isNull();
        assertThat(result.events().get(1).inputTokens()).isEqualTo(50);
        assertThat(result.events().get(1).outputTokens()).isEqualTo(10);
        assertThat(result.issues()).isEmpty();
    }

    @Test
    void associatesMetadataByTurnAcrossRestartsAndDoesNotChangeUsageOrEventIds() throws Exception {
        Path home = temporaryDirectory.resolve("metadata");
        String contexts = """
                {"type":"turn_context","payload":{"turn_id":"a","model":"gpt-6.1-sol","effort":"max"}}
                {"type":"turn_context","payload":{"turn_id":"b","model":"gpt-6-sol","effort":"high"}}
                """;
        Path file = writeSession(home, "run.jsonl", contexts);
        var first = scanner(home).scan(CodexUsageScanState.empty());
        var mapper = new tools.jackson.databind.ObjectMapper();
        var restored = mapper.readValue(mapper.writeValueAsBytes(first.nextState()), CodexUsageScanState.class);
        String usage = usageRecord("metadata-response", 10, 0, 0, 5, 0, 15)
                .replace("\"response_id\":", "\"turn_id\":\"a\",\"response_id\":");
        Files.writeString(file, usage, java.nio.file.StandardOpenOption.APPEND);
        var event = scanner(home).scan(restored).events().get(0);
        assertThat(event.model()).isEqualTo("gpt-6.1-sol");
        assertThat(event.reasoningEffort()).isEqualTo("max");
        assertThat(event.totalTokens()).isEqualTo(15);
        Path plain = temporaryDirectory.resolve("plain");
        writeSession(plain, "run.jsonl", usage);
        var withoutContext = scanner(plain).scan(CodexUsageScanState.empty()).events().get(0);
        assertThat(withoutContext.model()).isNull();
        assertThat(withoutContext.eventId()).isEqualTo(event.eventId());
    }

    @Test
    void invalidMetadataDoesNotBlockUsageAndOnlySafeSelectionFieldsArePersisted() throws Exception {
        Path home = temporaryDirectory.resolve("safe-metadata");
        String context = """
                {"type":"turn_context","payload":{"turn_id":"a","model":"gpt-6.1-sol","effort":"max","summary":"private-canary"}}
                """;
        String usage = usageRecord("safe", 10, 0, 0, 5, 0, 15)
                .replace("\"response_id\":", "\"turn_id\":\"unknown\",\"model\":\"gpt-6-sol\",\"effort\":\"HIGH\",\"response_id\":");
        writeSession(home, "run.jsonl", context + usage);
        var result = scanner(home).scan(CodexUsageScanState.empty());
        assertThat(result.events().get(0).model()).isEqualTo("gpt-6-sol");
        assertThat(result.events().get(0).reasoningEffort()).isEqualTo("high");
        assertThat(new tools.jackson.databind.ObjectMapper().writeValueAsString(result.nextState()))
                .doesNotContain("private-canary");
        Path invalid = temporaryDirectory.resolve("invalid-metadata");
        writeSession(invalid, "run.jsonl", """
                {"type":"turn_context","payload":{"model":"<invalid-model>","effort":"private-canary"}}
                """ + usageRecord("invalid", 10, 0, 0, 5, 0, 15));
        var event = scanner(invalid).scan(CodexUsageScanState.empty()).events().get(0);
        assertThat(event.totalTokens()).isEqualTo(15);
        assertThat(event.model()).isNull();
        assertThat(event.reasoningEffort()).isNull();
    }

    @Test
    void oldCursorJsonWithoutContextFieldsRemainsReadable() {
        var mapper = new tools.jackson.databind.ObjectMapper();
        var cursor = mapper.readValue("""
                {"fileIdentityDigest":"a","prefixLength":0,"prefixDigest":"b","nextByteOffset":0,
                "directUsageRecordObserved":false,"cumulativeUsage":null,"pendingFallbackEvents":[]}
                """, CodexUsageFileCursor.class);
        assertThat(cursor.currentContext()).isNull();
        assertThat(cursor.turnContexts()).isEmpty();
    }

    @Test
    void unknownOrInvalidTurnMetadataIsNeverBorrowedFromTheLatestDifferentTurn() throws Exception {
        Path home = temporaryDirectory.resolve("unknown-turn");
        String context = """
                {"type":"turn_context","payload":{"turn_id":"known","model":"gpt-6.1-sol","effort":"max"}}
                """;
        String unknown = usageRecord("unknown", 10, 0, 0, 5, 0, 15)
                .replace("\"response_id\":", "\"turn_id\":\"other\",\"response_id\":");
        String invalid = usageRecord("invalid-direct", 10, 0, 0, 5, 0, 15)
                .replace("\"response_id\":", "\"turn_id\":\"known\",\"model\":null,\"effort\":\"unsupported\",\"response_id\":");
        writeSession(home, "run.jsonl", context + unknown + invalid);
        var events = scanner(home).scan(CodexUsageScanState.empty()).events();
        assertThat(events).hasSize(2);
        assertThat(events).allSatisfy(event -> {
            assertThat(event.model()).isNull();
            assertThat(event.reasoningEffort()).isNull();
            assertThat(event.totalTokens()).isEqualTo(15);
        });
    }

    @Test
    void extractsMeasuredPerResponseUsageOnlyFromTheConfiguredSessionsDirectory() throws Exception {
        Path codexHome = temporaryDirectory.resolve("isolated-codex-home");
        writeSession(codexHome, "2026/10/05/rollout.jsonl", usageRecord("response-one", 100, 80, 10, 20, 5, 120));
        Files.createDirectories(codexHome.resolve("outside-sessions"));
        Files.writeString(codexHome.resolve("outside-sessions/ignored.jsonl"), usageRecord("must-not-read", 1, 0, 0, 1, 0, 2));

        CodexUsageScanResult result = scanner(codexHome).scan(CodexUsageScanState.empty());

        assertThat(result.events()).hasSize(1);
        CodexUsageEvent event = result.events().get(0);
        assertThat(event.eventId()).startsWith("codex-local-usage:v1:").doesNotContain("response-one");
        assertThat(event.occurredAt()).isEqualTo(Instant.parse("2026-10-05T09:00:00Z"));
        assertThat(event.inputTokens()).isEqualTo(100);
        assertThat(event.cachedInputTokens()).isEqualTo(80);
        assertThat(event.cacheWriteInputTokens()).isEqualTo(10);
        assertThat(event.outputTokens()).isEqualTo(20);
        assertThat(event.reasoningOutputTokens()).isEqualTo(5);
        assertThat(event.totalTokens()).isEqualTo(120);
        assertThat(event.source()).isEqualTo(CodexUsageEventSource.TOKEN_USAGE_RECORD);
    }

    @Test
    void fastBaselineSkipsHistoricalContentsAndReadsOnlyFutureAppends() throws Exception {
        Path home = temporaryDirectory.resolve("official-home");
        Path file = writeSession(home, "2026/history.jsonl", "not-json" + System.lineSeparator());
        CodexSessionUsageScanner scanner = scanner(home);

        CodexUsageScanState baseline = scanner.baseline();
        assertThat(baseline.files().get("2026/history.jsonl").nextByteOffset())
                .isEqualTo(Files.size(file));
        Files.writeString(file, usageRecord("new-response", 10, 0, 0, 5, 0, 15),
                java.nio.file.StandardOpenOption.APPEND);

        CodexUsageScanResult fresh = scanner.scan(baseline);
        assertThat(fresh.events()).extracting(CodexUsageEvent::totalTokens).containsExactly(15L);
        assertThat(fresh.issues()).isEmpty();
    }

    @Test
    void retainsAFileCursorSoRepeatedScansDoNotReplayEventsAndAFileGrowthEmitsOnlyTheAppend() throws Exception {
        Path codexHome = temporaryDirectory.resolve("isolated-codex-home");
        Path file = writeSession(codexHome, "2026/10/05/rollout.jsonl", usageRecord("response-one", 10, 5, 0, 5, 2, 15));
        CodexSessionUsageScanner scanner = scanner(codexHome);

        CodexUsageScanResult first = scanner.scan(CodexUsageScanState.empty());
        CodexUsageScanResult repeated = scanner.scan(first.nextState());

        Files.writeString(file, usageRecord("response-two", 20, 10, 0, 10, 3, 30), java.nio.file.StandardOpenOption.APPEND);
        CodexUsageScanResult appended = scanner.scan(repeated.nextState());

        assertThat(first.events()).extracting(CodexUsageEvent::totalTokens).containsExactly(15L);
        assertThat(repeated.events()).isEmpty();
        assertThat(appended.events()).extracting(CodexUsageEvent::totalTokens).containsExactly(30L);
    }

    @Test
    void waitsForATrailingPartialJsonLineBeforeAdvancingTheCursor() throws Exception {
        Path codexHome = temporaryDirectory.resolve("isolated-codex-home");
        Path file = writeSession(codexHome, "2026/10/05/rollout.jsonl", usageRecord("response-one", 10, 0, 0, 5, 0, 15).stripTrailing());
        CodexSessionUsageScanner scanner = scanner(codexHome);

        CodexUsageScanResult partial = scanner.scan(CodexUsageScanState.empty());
        Files.writeString(file, System.lineSeparator(), java.nio.file.StandardOpenOption.APPEND);
        CodexUsageScanResult completed = scanner.scan(partial.nextState());

        assertThat(partial.events()).isEmpty();
        assertThat(completed.events()).extracting(CodexUsageEvent::totalTokens).containsExactly(15L);
    }

    @Test
    void resetsTheCursorWhenAJsonlFileIsReplacedAtTheSamePath() throws Exception {
        Path codexHome = temporaryDirectory.resolve("isolated-codex-home");
        Path file = writeSession(codexHome, "2026/10/05/rollout.jsonl", usageRecord("response-one", 10, 0, 0, 5, 0, 15));
        CodexSessionUsageScanner scanner = scanner(codexHome);
        CodexUsageScanResult first = scanner.scan(CodexUsageScanState.empty());

        Files.delete(file);
        Files.writeString(file, usageRecord("response-two", 20, 0, 0, 10, 0, 30));
        CodexUsageScanResult replacement = scanner.scan(first.nextState());

        assertThat(replacement.events()).extracting(CodexUsageEvent::totalTokens).containsExactly(30L);
    }

    @Test
    void deduplicatesCopiedForkHistoryByStableResponseEventIdButKeepsSubagentResponsesDistinct() throws Exception {
        Path codexHome = temporaryDirectory.resolve("isolated-codex-home");
        String copiedHistory = usageRecord("response-parent", 10, 0, 0, 5, 0, 15);
        writeSession(codexHome, "2026/10/05/parent.jsonl", copiedHistory);
        writeSession(codexHome, "2026/10/05/fork.jsonl", copiedHistory + usageRecord("response-subagent", 20, 0, 0, 10, 0, 30));

        CodexUsageScanResult result = scanner(codexHome).scan(CodexUsageScanState.empty());

        assertThat(result.events()).hasSize(2);
        assertThat(result.events()).extracting(CodexUsageEvent::totalTokens).containsExactlyInAnyOrder(15L, 30L);
        assertThat(result.events()).extracting(CodexUsageEvent::eventId).doesNotHaveDuplicates();
    }

    @Test
    void usesOnlyPositiveDeltasFromLegacyCumulativeSnapshotsAndIgnoresRepeatedLastUsage() throws Exception {
        Path codexHome = temporaryDirectory.resolve("isolated-codex-home");
        Path file = writeSession(codexHome, "2026/10/04/legacy.jsonl",
                tokenCount(100, 80, 0, 20, 5, 120, 100, 80, 0, 20, 5, 120)
                        + tokenCount(100, 80, 0, 20, 5, 120, 100, 80, 0, 20, 5, 120)
                        + tokenCount(150, 120, 0, 30, 8, 180, 50, 40, 0, 10, 3, 60));
        Files.setLastModifiedTime(file, FileTime.from(NOW.minus(Duration.ofHours(1))));

        CodexUsageScanResult result = scanner(codexHome).scan(CodexUsageScanState.empty());

        assertThat(result.events()).hasSize(1);
        CodexUsageEvent event = result.events().get(0);
        assertThat(event.source()).isEqualTo(CodexUsageEventSource.CUMULATIVE_TOKEN_COUNT_FALLBACK);
        assertThat(event.inputTokens()).isEqualTo(50);
        assertThat(event.outputTokens()).isEqualTo(10);
        assertThat(event.totalTokens()).isEqualTo(60);
    }

    @Test
    void prefersUsageRecordsOverCumulativeSnapshotsFromTheSameFile() throws Exception {
        Path codexHome = temporaryDirectory.resolve("isolated-codex-home");
        Path file = writeSession(codexHome, "2026/10/04/current.jsonl",
                tokenCount(100, 80, 0, 20, 5, 120, 100, 80, 0, 20, 5, 120)
                        + tokenCount(150, 120, 0, 30, 8, 180, 50, 40, 0, 10, 3, 60)
                        + usageRecord("response-current", 50, 40, 0, 10, 3, 60));
        Files.setLastModifiedTime(file, FileTime.from(NOW.minus(Duration.ofHours(1))));

        CodexUsageScanResult result = scanner(codexHome).scan(CodexUsageScanState.empty());

        assertThat(result.events()).hasSize(1);
        assertThat(result.events().get(0).source()).isEqualTo(CodexUsageEventSource.TOKEN_USAGE_RECORD);
        assertThat(result.events().get(0).totalTokens()).isEqualTo(60);
    }

    @Test
    void doesNotCreateNegativeUsageWhenACumulativeCounterResets() throws Exception {
        Path codexHome = temporaryDirectory.resolve("isolated-codex-home");
        Path file = writeSession(codexHome, "2026/10/04/legacy.jsonl",
                tokenCount(100, 80, 0, 20, 5, 120, 100, 80, 0, 20, 5, 120)
                        + tokenCount(90, 70, 0, 20, 5, 110, 0, 0, 0, 0, 0, 0));
        Files.setLastModifiedTime(file, FileTime.from(NOW.minus(Duration.ofHours(1))));

        CodexUsageScanResult result = scanner(codexHome).scan(CodexUsageScanState.empty());

        assertThat(result.events()).isEmpty();
        assertThat(result.issues()).extracting(CodexUsageScanIssue::code)
                .contains(CodexUsageScanIssueCode.CUMULATIVE_COUNTER_RESET);
    }

    @Test
    void keepsMalformedRecordsOutOfEventsWithoutExposingTheirContents() throws Exception {
        Path codexHome = temporaryDirectory.resolve("isolated-codex-home");
        writeSession(codexHome, "2026/10/05/rollout.jsonl", "not-json" + System.lineSeparator() + usageRecord("response-one", 10, 0, 0, 5, 0, 15));

        CodexUsageScanResult result = scanner(codexHome).scan(CodexUsageScanState.empty());

        assertThat(result.events()).hasSize(1);
        assertThat(result.issues()).extracting(CodexUsageScanIssue::code)
                .contains(CodexUsageScanIssueCode.MALFORMED_JSONL_RECORD);
    }

    private CodexSessionUsageScanner scanner(Path codexHome) {
        return new CodexSessionUsageScanner(codexHome, CLOCK, Duration.ofSeconds(10));
    }

    private Path writeSession(Path codexHome, String relativePath, String content) throws Exception {
        Path file = codexHome.resolve("sessions").resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        return file;
    }

    private static String usageRecord(String responseId, long input, long cachedInput, long cacheWrite, long output, long reasoning, long total) {
        return """
                {"timestamp":"2026-10-05T09:00:00Z","type":"token_usage_record","payload":{"response_id":"%s","usage":{"input_tokens":%d,"cached_input_tokens":%d,"cache_write_input_tokens":%d,"output_tokens":%d,"reasoning_output_tokens":%d,"total_tokens":%d}}}
                """.formatted(responseId, input, cachedInput, cacheWrite, output, reasoning, total);
    }

    private static String tokenCount(
            long input,
            long cachedInput,
            long cacheWrite,
            long output,
            long reasoning,
            long total,
            long lastInput,
            long lastCachedInput,
            long lastCacheWrite,
            long lastOutput,
            long lastReasoning,
            long lastTotal
    ) {
        return """
                {"timestamp":"2026-10-04T09:00:00Z","type":"event_msg","payload":{"type":"token_count","info":{"total_token_usage":{"input_tokens":%d,"cached_input_tokens":%d,"cache_write_input_tokens":%d,"output_tokens":%d,"reasoning_output_tokens":%d,"total_tokens":%d},"last_token_usage":{"input_tokens":%d,"cached_input_tokens":%d,"cache_write_input_tokens":%d,"output_tokens":%d,"reasoning_output_tokens":%d,"total_tokens":%d}}}}
                """.formatted(input, cachedInput, cacheWrite, output, reasoning, total, lastInput, lastCachedInput, lastCacheWrite, lastOutput, lastReasoning, lastTotal);
    }
}
