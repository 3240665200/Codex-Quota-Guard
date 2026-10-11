package com.codexquotaguard.backend.usage;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

class RustMeterLogReaderTest {
    @TempDir Path directory;

    @Test
    void projectsOnlyBoundedWhitelistedSummariesInNewestFirstOrder() throws Exception {
        Path logs = directory.resolve("logs");
        Files.createDirectories(logs);
        Files.writeString(logs.resolve("meter.1.jsonl"), entry(100, "SERVICE_STARTED", 0, null));
        Files.writeString(logs.resolve("meter.jsonl"), entry(101, "USAGE_REPORTED", 12500, null)
                + "{\"kind\":\"UNTRUSTED\",\"atEpochSeconds\":102}\n"
                + entry(103, "ERROR", 0, "refresh_token=secret-canary"));
        var result = new RustMeterLogReader(new ObjectMapper(), logs.toString()).read();
        assertThat(result.state()).isEqualTo("READY");
        assertThat(result.entries()).hasSize(3);
        assertThat(result.entries().get(0).errorCode()).isEqualTo("METER_ERROR_REDACTED");
        assertThat(result.entries().get(1).reportedTokens()).isEqualTo(12500);
        assertThat(new ObjectMapper().writeValueAsString(result)).doesNotContain("secret-canary", "auth.json");
    }

    @Test
    void missingLogsAreExplicitRatherThanFabricatedEmptySuccess() {
        var result = new RustMeterLogReader(new ObjectMapper(), directory.resolve("absent").toString()).read();
        assertThat(result.state()).isEqualTo("NOT_AVAILABLE");
        assertThat(result.entries()).isEmpty();
    }

    @Test
    void skipsOversizedAndMalformedLinesAndCapsReturnedHistory() throws Exception {
        StringBuilder content = new StringBuilder("bad-json\n").append("x".repeat(5000)).append('\n');
        for (int i = 1; i <= 80; i++) content.append(entry(i, "HEARTBEAT", 0, null));
        Files.writeString(directory.resolve("meter.jsonl"), content);
        var result = new RustMeterLogReader(new ObjectMapper(), directory.toString()).read();
        assertThat(result.entries()).hasSize(50);
        assertThat(result.entries().get(0).atEpochSeconds()).isEqualTo(80);
        assertThat(result.skippedLines()).isEqualTo(2);
    }

    @Test
    void exposesOnlyFixedGuardEventsAndStatesWithoutRawProcessDetails() throws Exception {
        Files.writeString(directory.resolve("meter.jsonl"),
                "{\"atEpochSeconds\":100,\"kind\":\"GUARD_TERMINATED\",\"reportedEvents\":0,"
                + "\"reportedTokens\":0,\"ccSwitchGuard\":\"TERMINATED\","
                + "\"processPath\":\"C:/secret/cc-switch.exe\"}\n"
                + "{\"atEpochSeconds\":101,\"kind\":\"GUARD_ERROR\",\"reportedEvents\":0,"
                + "\"reportedTokens\":0,\"ccSwitchGuard\":\"untrusted-value\"}\n");
        var result = new RustMeterLogReader(new ObjectMapper(), directory.toString()).read();
        assertThat(result.entries()).hasSize(2);
        assertThat(result.entries().get(1).kind()).isEqualTo("GUARD_TERMINATED");
        assertThat(result.entries().get(1).ccSwitchGuard()).isEqualTo("TERMINATED");
        assertThat(result.entries().get(0).ccSwitchGuard()).isNull();
        assertThat(new ObjectMapper().writeValueAsString(result)).doesNotContain("C:/secret", "untrusted-value");
    }

    private static String entry(int at, String kind, long tokens, String error) {
        return "{\"atEpochSeconds\":" + at + ",\"kind\":\"" + kind
                + "\",\"state\":\"REPORTED\",\"reportedEvents\":1,\"reportedTokens\":" + tokens
                + ",\"heartbeatSent\":false,\"errorCode\":"
                + (error == null ? "null" : "\"" + error + "\"")
                + ",\"rawSession\":\"auth.json private-canary\"}\n";
    }
}
