package com.codexquotaguard.backend.codex;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

class LocalOfficialCodexRateLimitServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @TempDir Path temporaryDirectory;

    @Test
    void findsAnOfficialDesktopCliInsideItsVersionedBinDirectory() throws Exception {
        Path bin = temporaryDirectory.resolve("OpenAI/Codex/bin");
        Path executable = bin.resolve("version-1/codex.exe");
        Files.createDirectories(executable.getParent());
        Files.write(executable, new byte[] { 1 });

        assertThat(LocalOfficialCodexRateLimitService.findBundledExecutable(bin))
                .isEqualTo(executable.toAbsolutePath().normalize().toString());
    }

    @Test
    void parsesOnlyWindowsActuallyReturnedByOfficialAppServer() {
        var account = mapper.readTree("""
                {"account":{"type":"chatgpt","planType":"plus"}}
                """);
        var limits = mapper.readTree("""
                {"rateLimitsByLimitId":{"codex":{"primary":{"windowDurationMins":10080,
                "usedPercent":42.5,"resetsAt":1791400000}}}}
                """);

        var result = LocalOfficialCodexRateLimitService.parse(account, limits,
                Instant.parse("2026-10-06T00:00:00Z"));

        assertThat(result.planType()).isEqualTo("plus");
        assertThat(result.windows()).hasSize(1);
        assertThat(result.windows().get(0).windowDurationMins()).isEqualTo(10080);
        assertThat(result.windows().get(0).usedPercent()).isEqualTo(42.5);
    }
}
