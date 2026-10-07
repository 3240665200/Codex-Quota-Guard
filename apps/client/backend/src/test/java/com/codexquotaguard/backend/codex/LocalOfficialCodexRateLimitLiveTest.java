package com.codexquotaguard.backend.codex;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import tools.jackson.databind.ObjectMapper;

/** Opt-in smoke test; it uses the operator's already signed-in official CLI. */
class LocalOfficialCodexRateLimitLiveTest {

    @Test
    @EnabledIfSystemProperty(named = "codex.rate.limit.live", matches = "true")
    void readsSafeQuotaMetadataFromTheLocalOfficialLogin() {
        String command = System.getProperty("codex.rate.limit.command", "codex.exe");
        String home = System.getProperty("user.home") + "/.codex";
        assertThat(java.nio.file.Path.of(LocalOfficialCodexRateLimitService.resolveExecutable(command))
                .isAbsolute()).isTrue();
        var service = new LocalOfficialCodexRateLimitService(
                new ObjectMapper(), Clock.systemUTC(), command, home);

        var snapshot = service.read();

        assertThat(snapshot.fetchedAt()).isNotBlank();
        assertThat(snapshot.windows()).isNotNull();
    }
}
