package com.pingche.web.codex;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class CodexLoginUrlTest {
    static final String VALID_URL = "https://auth.openai.com/oauth/authorize?response_type=code"
            + "&redirect_uri=http%3A%2F%2Flocalhost%3A1455%2Fauth%2Fcallback"
            + "&state=test-state&code_challenge_method=S256&code_challenge=" + "a".repeat(43);

    @Test
    void permitsOfficialHttpsUrlsWithPkceAndTheLocalCallbackOnly() {
        assertThat(CodexLoginUrl.isBrowserLogin(VALID_URL)).isTrue();
        assertThat(CodexLoginUrl.isBrowserLogin(VALID_URL.replace("auth.openai.com", "chatgpt.com"))).isTrue();
        assertThat(CodexLoginUrl.isBrowserLogin(VALID_URL.replace("localhost", "127.0.0.1"))).isTrue();
    }

    @ParameterizedTest
    @MethodSource("unsafeUrls")
    void rejectsNonOfficialOrMalformedAuthorizationLinks(String url) {
        assertThat(CodexLoginUrl.isBrowserLogin(url)).isFalse();
    }

    static Stream<String> unsafeUrls() {
        return Stream.of(null, "", "not a URL", VALID_URL.replace("https:", "http:"),
                VALID_URL.replace("auth.openai.com", "auth.openai.com.attacker.invalid"),
                VALID_URL.replace("auth.openai.com", "attacker@auth.openai.com"),
                VALID_URL.replace("auth.openai.com", "auth.openai.com:444"),
                VALID_URL.replace("localhost", "attacker.invalid"),
                VALID_URL.replace("1455", "8080"), VALID_URL.replace("auth%2Fcallback", "another"),
                VALID_URL.replace("S256", "plain"), VALID_URL.replace("test-state", ""),
                VALID_URL.replace("response_type=code", "response_type=token"),
                VALID_URL + "#fragment", VALID_URL + "&state=other", VALID_URL + "&%73tate=other",
                VALID_URL.replace("&state", "&invalid=%ZZ&state"), VALID_URL + "&extra=" + "x".repeat(8192));
    }
}
