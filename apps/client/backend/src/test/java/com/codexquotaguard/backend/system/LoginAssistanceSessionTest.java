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

    @Test
    void aLateCodeCannotCrossLogoutAndLoginEvenForTheSameUserId() {
        var gateway = mock(ControlPlaneGateway.class);
        var user = new SystemUser(UUID.randomUUID(), "user@example.com", "User", "USER", "ACTIVE",
                null, null, null, null, null, null, null);
        when(gateway.login(anyString(), anyString())).thenReturn(
                new ControlPlaneAuthSession("synthetic-first-access", "synthetic-first-refresh", user),
                new ControlPlaneAuthSession("synthetic-second-access", "synthetic-second-refresh", user));
        var service = new SystemSessionService(gateway);
        service.login("user@example.com", "synthetic-password");
        UUID listener = UUID.randomUUID();
        when(gateway.loginAssistanceStatus("synthetic-first-access", listener)).thenAnswer(call -> {
            service.logout();
            service.login("user@example.com", "synthetic-password");
            return new LoginAssistanceListener(listener, "RECEIVED", Instant.EPOCH, Instant.EPOCH.plusSeconds(180),
                    Instant.EPOCH, Instant.EPOCH.plusSeconds(1), "123456", null);
        });
        assertThatThrownBy(() -> service.loginAssistanceStatus(listener))
                .isInstanceOf(SystemSessionRequiredException.class);
        verify(gateway, times(2)).login("user@example.com", "synthetic-password");
    }

    @Test
    void aRefreshForAnotherUserNeverReplaysTheMailOperationWithThatUsersToken() {
        var gateway = mock(ControlPlaneGateway.class);
        var first = new SystemUser(UUID.randomUUID(), "first@example.com", "First", "USER", "ACTIVE",
                null, null, null, null, null, null, null);
        var other = new SystemUser(UUID.randomUUID(), "other@example.com", "Other", "USER", "ACTIVE",
                null, null, null, null, null, null, null);
        when(gateway.login(anyString(), anyString()))
                .thenReturn(new ControlPlaneAuthSession("synthetic-first-access", "synthetic-first-refresh", first));
        when(gateway.refresh("synthetic-first-refresh"))
                .thenReturn(new ControlPlaneAuthSession("synthetic-other-access", "synthetic-other-refresh", other));
        var service = new SystemSessionService(gateway);
        service.login("first@example.com", "synthetic-password");
        UUID listener = UUID.randomUUID();
        when(gateway.loginAssistanceStatus("synthetic-first-access", listener))
                .thenThrow(ControlPlaneException.unauthorized());
        assertThatThrownBy(() -> service.loginAssistanceStatus(listener))
                .isInstanceOf(SystemSessionRequiredException.class);
        verify(gateway).refresh("synthetic-first-refresh");
        verify(gateway, never()).loginAssistanceStatus("synthetic-other-access", listener);
    }
}
