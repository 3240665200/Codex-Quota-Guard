package com.codexquotaguard.backend.system;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.codexquotaguard.backend.controlplane.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ClientAccessSessionTest {
    private final ControlPlaneGateway gateway = mock(ControlPlaneGateway.class);
    private final SystemUser user = new SystemUser(UUID.randomUUID(), "alice@example.com", "Alice", "USER", "ACTIVE", null,null,null,null,null,null,null);
    private SystemSessionService login() {
        when(gateway.login(anyString(), anyString())).thenReturn(new ControlPlaneAuthSession("first-access", "first-refresh", user));
        var sessions = new SystemSessionService(gateway);
        sessions.login("alice@example.com", "password");
        return sessions;
    }

    @Test void bindingRevocationDoesNotEraseTheProductLogin() {
        var sessions = login();
        when(gateway.quotaOverview("first-access")).thenThrow(ControlPlaneException.bindingRequired());
        when(gateway.current("first-access")).thenReturn(user);
        assertThatThrownBy(sessions::quotaOverview).isInstanceOf(SessionBindingRequiredException.class);
        assertThat(sessions.currentOverview().authenticated()).isTrue();
        verify(gateway, never()).refresh(anyString());
        verify(gateway, never()).logout(anyString(), anyString());
    }

    @Test void lateAccessGrantCannotCrossLogoutAndLoginForTheSameUser() {
        var sessions = login();
        when(gateway.clientAccess("first-access")).thenAnswer(call -> {
            sessions.logout();
            sessions.login("alice@example.com", "password");
            return new ClientAccess(true, UUID.randomUUID(), null);
        });
        assertThatThrownBy(sessions::clientAccess).isInstanceOf(SystemSessionRequiredException.class);
    }

    @Test void refreshedIdentityMustMatchBeforeReplayingAccess() {
        var sessions = login();
        var other = new SystemUser(UUID.randomUUID(), "bob@example.com", "Bob", "USER", "ACTIVE", null,null,null,null,null,null,null);
        when(gateway.clientAccess("first-access")).thenThrow(ControlPlaneException.unauthorized());
        when(gateway.refresh("first-refresh")).thenReturn(new ControlPlaneAuthSession("other-access", "other-refresh", other));
        assertThatThrownBy(sessions::clientAccess).isInstanceOf(SystemSessionRequiredException.class);
        verify(gateway, never()).clientAccess("other-access");
    }

    @Test void transientAccessFailureNeitherErasesLoginNorCachesTheFailure() {
        var sessions = login();
        var allowed = new ClientAccess(true, UUID.randomUUID(), null);
        when(gateway.clientAccess("first-access")).thenReturn(allowed)
                .thenThrow(ControlPlaneException.unavailable()).thenReturn(allowed);
        when(gateway.current("first-access")).thenReturn(user);
        assertThat(sessions.clientAccess()).isEqualTo(allowed);
        assertThatThrownBy(sessions::clientAccess).isInstanceOf(ControlPlaneUnavailableException.class);
        assertThat(sessions.currentOverview().authenticated()).isTrue();
        assertThat(sessions.clientAccess()).isEqualTo(allowed);
        verify(gateway, never()).refresh(anyString());
        verify(gateway, never()).logout(anyString(), anyString());
    }
}
