package com.pingche.web.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;

class ManagementTransportProfilesTest {
    @Test
    void productionAndDevelopmentAllowTheSameHttpAndHttpsByDefault() throws Exception {
        for (boolean production : new boolean[] {false, true}) {
            var environment = environment(production);
            assertThat(environment.getProperty("app.require-https", Boolean.class)).isFalse();
            var guard = new ManagementTransportGuard(environment.getProperty("app.require-https", Boolean.class), false);
            assertThat(guard.isAllowed(request(false))).isTrue();
            assertThat(guard.isAllowed(request(true))).isTrue();
        }
    }

    @Test
    void explicitStrictHttpsSwitchWorksInBothProfilesAndKeepsLegacySwitchCompatible() throws Exception {
        for (boolean production : new boolean[] {false, true}) {
            for (String setting : new String[] {"APP_REQUIRE_HTTPS", "REQUIRE_HTTPS"}) {
                var environment = environment(production).withProperty(setting, "true");
                assertThat(environment.getProperty("app.require-https", Boolean.class)).isTrue();
                var guard = new ManagementTransportGuard(environment.getProperty("app.require-https", Boolean.class), false);
                assertThat(guard.isAllowed(request(false))).isFalse();
                assertThat(guard.isAllowed(request(true))).isTrue();
            }
        }
    }

    private MockEnvironment environment(boolean production) throws IOException {
        var environment = new MockEnvironment();
        var loader = new YamlPropertySourceLoader();
        if (production) loader.load("production", new ClassPathResource("application-prod.yml"))
                .forEach(source -> environment.getPropertySources().addLast(source));
        loader.load("base", new ClassPathResource("application.yml"))
                .forEach(source -> environment.getPropertySources().addLast(source));
        return environment;
    }

    private MockHttpServletRequest request(boolean secure) {
        var request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.8");
        request.setScheme(secure ? "https" : "http");
        request.setSecure(secure);
        return request;
    }
}
