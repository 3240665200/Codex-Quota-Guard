package com.codexquotaguard.backend.security;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;

class AssistanceStreamGateTest {
    @Test void restorationAndNotificationsAreAllowedBeforeDeviceBindingButRemainReadOnly() {
        for(String path:java.util.List.of("/api/v1/login-assistance/listeners/current","/api/v1/login-assistance/events")) {
            assertThat(ClientBindingGateConfiguration.bootstrapAllowed("GET",path)).isTrue();
            assertThat(ClientBindingGateConfiguration.bootstrapAllowed("POST",path)).isFalse();
            assertThat(ClientBindingGateConfiguration.bootstrapAllowed("DELETE",path)).isFalse();
        }
        assertThat(ClientBindingGateConfiguration.bootstrapAllowed("GET","/api/v1/login-assistance/events/other")).isFalse();
    }
}
