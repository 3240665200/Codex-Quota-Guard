package com.codexquotaguard.backend.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ApiTokenVerifierTest {

    private static final String TOKEN = "pJ8ybk_mqMDtQ4Qixl2f5rwcuKeB0VLtyQT6N48S9jc";

    @Test
    void acceptsOnlyTheExactBearerToken() {
        var verifier = new ApiTokenVerifier(TOKEN);

        assertThat(verifier.isAuthorized("Bearer " + TOKEN)).isTrue();
        assertThat(verifier.isAuthorized("Bearer another-token")).isFalse();
        assertThat(verifier.isAuthorized("Basic " + TOKEN)).isFalse();
        assertThat(verifier.isAuthorized(null)).isFalse();
    }

    @Test
    void rejectsMissingOrBlankConfiguredTokens() {
        assertThat(ApiTokenVerifier.isValidToken(TOKEN)).isTrue();
        assertThat(ApiTokenVerifier.isValidToken(" ")).isFalse();
        assertThat(ApiTokenVerifier.isValidToken(null)).isFalse();
    }
}
