package com.codexquotaguard.backend.system;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.codexquotaguard.backend.controlplane.*;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LoginAssistanceSessionTest {
    @Test
    void requiresSystemLoginBeforeAnyMailboxRequest() {
        var gateway = mock(ControlPlaneGateway.class);
        var service = new SystemSessionService(gateway);
        assertThatThrownBy(service::loginAssistanceInfo).isInstanceOf(SystemSessionRequiredException.class);
        assertThatThrownBy(service::startLoginAssistance).isInstanceOf(SystemSessionRequiredException.class);
        verifyNoInteractions(gateway);
    }

    @Test
    void aLateMailResponseAfterLogoutCannotExposeTheOldUsersCode() {
        var gateway = mock(ControlPlaneGateway.class);
        var user = new SystemUser(UUID.randomUUID(), "user@example.com", "User", "USER", "ACTIVE",
                null, null, null, null, null, null, null);
        when(gateway.login(anyString(), anyString()))
                .thenReturn(new ControlPlaneAuthSession("synthetic-access", "synthetic-refresh", user));
        var service = new SystemSessionService(gateway);
        service.login("user@example.com", "synthetic-password");
        UUID listener = UUID.randomUUID();
        when(gateway.loginAssistanceStatus("synthetic-access", listener)).thenAnswer(call -> {
            service.logout();
            return new LoginAssistanceListener(listener, "RECEIVED", Instant.EPOCH, Instant.EPOCH.plusSeconds(180),
                    Instant.EPOCH, Instant.EPOCH.plusSeconds(1), "123456", null);
        });
        assertThatThrownBy(() -> service.loginAssistanceStatus(listener))
                .isInstanceOf(SystemSessionRequiredException.class);
    }
}
