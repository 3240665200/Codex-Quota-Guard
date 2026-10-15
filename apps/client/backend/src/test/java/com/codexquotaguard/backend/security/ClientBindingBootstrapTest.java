package com.codexquotaguard.backend.security;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ClientBindingBootstrapTest {
    @Test void onlyTheExactImplementedMethodsAndHelperPathsAreExempt() {
        String id = "11111111-1111-4111-8111-111111111111";
        assertThat(ClientBindingGateConfiguration.bootstrapAllowed("GET", "/api/v1/session/access")).isTrue();
        assertThat(ClientBindingGateConfiguration.bootstrapAllowed("POST", "/api/v1/session-bindings/attempts/" + id + "/confirm")).isTrue();
        assertThat(ClientBindingGateConfiguration.bootstrapAllowed("DELETE", "/api/v1/login-assistance/listeners/" + id)).isTrue();
        for (String path : java.util.List.of("/api/v1/session/new-business", "/api/v1/session-bindings/future",
                "/api/v1/login-assistance/future", "/api/v1/official-login-helper/future",
                "/api/v1/session-bindings/attempts/" + id + "/claim")) {
            for (String method : java.util.List.of("GET", "POST", "PUT", "DELETE")) {
                assertThat(ClientBindingGateConfiguration.bootstrapAllowed(method, path)).isFalse();
            }
        }
        assertThat(ClientBindingGateConfiguration.bootstrapAllowed("PUT", "/api/v1/login-assistance")).isFalse();
    }
}
