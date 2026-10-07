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
