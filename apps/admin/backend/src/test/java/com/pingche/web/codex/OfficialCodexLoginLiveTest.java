package com.pingche.web.codex;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

/** Opt-in smoke test: obtains and cancels a device code, never confirms an account login. */
@EnabledIfSystemProperty(named = "codex.login.live.command", matches = ".+")
class OfficialCodexLoginLiveTest {

    @TempDir Path temporary;

    @Test
    void officialBrowserFlowOffersAPkceLoginWithoutExposingCredentials() throws Exception {
        var gateway = new OfficialCodexLoginGateway(new ObjectMapper(),
                System.getProperty("codex.login.live.command"), temporary.toString());
        try (var login = gateway.open(CodexLoginMode.BROWSER)) {
            // Do not print the URL: its OAuth state is only for the requesting administrator.
            assertThat(CodexLoginUrl.isBrowserLogin(login.authUrl())).isTrue();
            assertThat(login.userCode()).isNull();
            assertThat(login.poll()).isEmpty();
        }
        try (var directories = Files.list(temporary.resolve("codex-quota-guard-authorizations"))) {
            assertThat(directories.count()).isZero();
        }
    }

    @Test
    void officialProcessCanStartAndCancelWithoutLeavingCredentials() throws Exception {
        Path root = Path.of(System.getProperty("codex.login.live.work-dir", temporary.toString()));
        var gateway = new OfficialCodexLoginGateway(new ObjectMapper(),
                System.getProperty("codex.login.live.command"), root.toString());
        try (var login = gateway.open()) {
            assertThat(login.verificationUrl()).isEqualTo("https://auth.openai.com/codex/device");
            assertThat(login.userCode()).isNotBlank();
            assertThat(login.poll()).isEmpty();
            // Mimic a browser waiting on the code while background CLI initialization finishes.
            TimeUnit.SECONDS.sleep(5);
            assertThat(login.poll()).isEmpty();
        }
        try (var directories = Files.list(root.resolve("codex-quota-guard-authorizations"))) {
            assertThat(directories.count()).isZero();
        }
    }
}
