package com.codexquotaguard.backend.system;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.codexquotaguard.backend.controlplane.SystemUser;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SystemSessionControllerTest {

    @Test
    void installedMeterMustPairBeforeTheSystemLoginIsAccepted() {
        var sessions = mock(SystemSessionService.class);
        var pairing = mock(MeterPairingService.class);
        var user = new SystemUser(UUID.randomUUID(), "member@example.com", "Member",
                "USER", "ACTIVE", null, null, null, null, null, null, null);
        when(sessions.loginProbe("member@example.com", "password"))
                .thenReturn(new SystemSessionService.Probe(SystemSessionOverview.signedIn(user), 1));
        when(pairing.pairIfInstalled()).thenThrow(new IllegalStateException("Meter pairing is unavailable"));

        var controller = new SystemSessionController(sessions, pairing);
        assertThatThrownBy(() -> controller.login(
                new SystemSessionController.LoginRequest("member@example.com", "password")))
                .isInstanceOf(MeterPairingRequiredException.class);
        verify(sessions).logoutIfGeneration(1);
    }

    @Test
    void aRestoredSessionMustPassInstalledMeterOwnershipBeforeCurrentReturnsIt() {
        var sessions = mock(SystemSessionService.class);
        var pairing = mock(MeterPairingService.class);
        var user = new SystemUser(UUID.randomUUID(), "member@example.com", "Member",
                "USER", "ACTIVE", null, null, null, null, null, null, null);
        when(sessions.currentProbe()).thenReturn(new SystemSessionService.Probe(SystemSessionOverview.signedIn(user), 1));
        when(pairing.pairIfInstalled()).thenThrow(new IllegalStateException("Meter pairing is unavailable"));

        assertThatThrownBy(() -> new SystemSessionController(sessions, pairing).current())
                .isInstanceOf(MeterPairingRequiredException.class);
    }
}
