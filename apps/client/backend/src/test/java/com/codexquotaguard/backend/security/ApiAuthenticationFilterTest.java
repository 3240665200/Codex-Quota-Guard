package com.codexquotaguard.backend.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class ApiAuthenticationFilterTest {

    private static final String TOKEN = "pJ8ybk_mqMDtQ4Qixl2f5rwcuKeB0VLtyQT6N48S9jc";

    @Test
    void rejectsAnApiRequestBeforeItReachesTheControllerWhenTokenIsMissing() throws Exception {
        var request = new MockHttpServletRequest("GET", "/api/v1/status");
        var response = new MockHttpServletResponse();
        var reachedController = new AtomicBoolean();
        var filter = new ApiAuthenticationFilter(new ApiTokenVerifier(TOKEN));

        filter.doFilter(request, response, (ignoredRequest, ignoredResponse) -> reachedController.set(true));

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(reachedController).isFalse();
    }

    @Test
    void letsAnAuthenticatedApiRequestReachTheController() throws Exception {
        var request = new MockHttpServletRequest("GET", "/api/v1/status");
        request.addHeader("Authorization", "Bearer " + TOKEN);
        var response = new MockHttpServletResponse();
        var reachedController = new AtomicBoolean();
        var filter = new ApiAuthenticationFilter(new ApiTokenVerifier(TOKEN));

        filter.doFilter(request, response, (ignoredRequest, ignoredResponse) -> reachedController.set(true));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(reachedController).isTrue();
    }
}
