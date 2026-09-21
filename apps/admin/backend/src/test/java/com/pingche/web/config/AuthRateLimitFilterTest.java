package com.pingche.web.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pingche.web.common.SecurityErrorWriter;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

class AuthRateLimitFilterTest {

    @Test
    void limitsAuthenticationEndpointPerRemoteAddress() throws Exception {
        SecurityErrorWriter writer = new SecurityErrorWriter(
                JsonMapper.builder().build()
        );
        AuthRateLimitFilter filter = new AuthRateLimitFilter(writer, 1);
        AtomicInteger chainCalls = new AtomicInteger();

        MockHttpServletRequest first = loginRequest();
        MockHttpServletResponse firstResponse = new MockHttpServletResponse();
        filter.doFilter(first, firstResponse, (request, response) ->
                chainCalls.incrementAndGet());

        MockHttpServletRequest second = loginRequest();
        MockHttpServletResponse secondResponse = new MockHttpServletResponse();
        filter.doFilter(second, secondResponse, (request, response) ->
                chainCalls.incrementAndGet());

        assertThat(chainCalls).hasValue(1);
        assertThat(secondResponse.getStatus()).isEqualTo(429);
        assertThat(secondResponse.getHeader("Retry-After")).isEqualTo("60");
        assertThat(secondResponse.getContentAsString())
                .contains("AUTH_RATE_LIMITED")
                .doesNotContain("password");
    }

    @Test
    void rejectsInvalidLimitConfiguration() {
        SecurityErrorWriter writer = new SecurityErrorWriter(
                JsonMapper.builder().build()
        );
        assertThatThrownBy(() -> new AuthRateLimitFilter(writer, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private MockHttpServletRequest loginRequest() {
        MockHttpServletRequest request =
                new MockHttpServletRequest("POST", "/api/v1/auth/login");
        request.setRemoteAddr("192.0.2.10");
        return request;
    }
}
