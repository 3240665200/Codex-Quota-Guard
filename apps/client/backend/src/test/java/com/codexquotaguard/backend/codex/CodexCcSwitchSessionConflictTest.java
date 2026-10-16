package com.codexquotaguard.backend.codex;

import static org.assertj.core.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

class CodexCcSwitchSessionConflictTest {
    @TempDir Path home;
    private final ObjectMapper json = new ObjectMapper();

    private LocalOfficialCodexRateLimitService service() {
        return new LocalOfficialCodexRateLimitService(json, Clock.systemUTC(), "synthetic-codex", home.toString());
    }

    @Test void identifiesOnlyExplicitApiKeyAccountWithStableCcPlaceholder() throws Exception {
        Files.writeString(home.resolve("auth.json"), "{\"OPENAI_API_KEY\":\"PROXY_MANAGED\"}");
        var service = service();
        assertThatThrownBy(() -> service.requireChatGptAccount(
                json.readTree("{\"account\":{\"type\":\"apiKey\"}}"), service.authFingerprint()))
                .isInstanceOf(CodexCcSwitchSessionConflictException.class)
                .hasMessageNotContaining("PROXY_MANAGED", home.toString());
    }

    @Test void genuineChatGptAccountIsNeverAConflict() throws Exception {
        Files.writeString(home.resolve("auth.json"), "{\"OPENAI_API_KEY\":\"PROXY_MANAGED\"}");
        var service = service();
        assertThatCode(() -> service.requireChatGptAccount(
                json.readTree("{\"account\":{\"type\":\"chatgpt\"}}"), service.authFingerprint()))
                .doesNotThrowAnyException();
    }

    @Test void absentUnknownOrMalformedAccountNeverAuthorizesStoppingCc() throws Exception {
        Files.writeString(home.resolve("auth.json"), "{\"OPENAI_API_KEY\":\"PROXY_MANAGED\"}");
        var service = service();
        for (String account : new String[]{"{}", "{\"account\":null}",
                "{\"account\":{\"type\":\"unknown\"}}", "{\"account\":{\"type\":42}}"}) {
            assertThatThrownBy(() -> service.requireChatGptAccount(json.readTree(account), service.authFingerprint()))
                    .isInstanceOf(OfficialCodexRateLimitsUnavailableException.class);
        }
    }

    @Test void missingDifferentMalformedOversizedOrTokenBearingAuthNeverAuthorizesStoppingCc() throws Exception {
        var service = service();
        for (String auth : new String[]{"{}", "{\"OPENAI_API_KEY\":\"synthetic-key\"}", "{broken",
                "{\"OPENAI_API_KEY\":\"PROXY_MANAGED\",\"tokens\":{\"access_token\":\"synthetic\"}}",
                "{\"OPENAI_API_KEY\":\"PROXY_MANAGED\",\"tokens\":{\"refresh_token\":\"synthetic\"}}",
                "{\"OPENAI_API_KEY\":\"PROXY_MANAGED\",\"auth_mode\":\"chatgpt\"}",
                "{\"OPENAI_API_KEY\":\"PROXY_MANAGED\",\"access_token\":\"synthetic\"}",
                "{\"OPENAI_API_KEY\":\"PROXY_MANAGED\",\"padding\":\"" + "x".repeat(65_536) + "\"}"}) {
            Files.writeString(home.resolve("auth.json"), auth);
            assertThatThrownBy(() -> service.requireChatGptAccount(
                    json.readTree("{\"account\":{\"type\":\"apiKey\"}}"), service.authFingerprint()))
                    .isInstanceOf(OfficialCodexRateLimitsUnavailableException.class);
        }
        Files.delete(home.resolve("auth.json"));
        assertThat(service.authFingerprint()).isNull();
        assertThatThrownBy(() -> service.requireChatGptAccount(
                json.readTree("{\"account\":{\"type\":\"apiKey\"}}"), null))
                .isInstanceOf(OfficialCodexRateLimitsUnavailableException.class);
    }

    @Test void changedAuthDuringAccountReadInvalidatesEvidence() throws Exception {
        Files.writeString(home.resolve("auth.json"), "{\"OPENAI_API_KEY\":\"PROXY_MANAGED\"}");
        var service = service();
        String before = service.authFingerprint();
        Files.writeString(home.resolve("auth.json"), "{\"OPENAI_API_KEY\":\"PROXY_MANAGED\",\"revision\":2}");
        assertThatThrownBy(() -> service.requireChatGptAccount(
                json.readTree("{\"account\":{\"type\":\"apiKey\"}}"), before))
                .isInstanceOf(OfficialCodexRateLimitsUnavailableException.class);
    }

    @Test void ordinaryProcessFailureDoesNotProduceAConflict() {
        assertThatThrownBy(() -> service().read())
                .isInstanceOf(OfficialCodexRateLimitsUnavailableException.class);
    }
}
