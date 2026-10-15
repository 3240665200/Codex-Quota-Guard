package com.codexquotaguard.backend.system;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.time.Instant;
import com.codexquotaguard.backend.controlplane.SessionBindingAttempt;
import com.codexquotaguard.backend.controlplane.SessionBindingCurrent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SessionBindingServiceTest {
    @TempDir Path directory;

    @Test void deviceIdentityIsReadFromTrustedMeterFileAndNeverFromRenderer() throws Exception {
        UUID device = UUID.randomUUID();
        Path file = directory.resolve("meter-device-id.txt");
        Files.writeString(file, device.toString());
        var sessions = mock(SystemSessionService.class);
        when(sessions.sessionBindingCurrent(device, device)).thenReturn(new SessionBindingCurrent(null, null));
        when(sessions.startSessionBinding(device, device)).thenReturn(attempt(device, UUID.randomUUID(), "QUEUED", null));
        var service = new SessionBindingService(sessions, file.toString());
        service.current();
        verify(sessions).sessionBindingCurrent(device, device);
        service.start();
        verify(sessions).startSessionBinding(device, device);
    }

    @Test void missingMalformedOrOversizedDeviceIdentityNeverMakesCentralRequest() throws Exception {
        Path file = directory.resolve("meter-device-id.txt");
        var sessions = mock(SystemSessionService.class);
        var service = new SessionBindingService(sessions, file.toString());
        assertThatThrownBy(service::start).isInstanceOf(SessionBindingException.class);
        Files.writeString(file, "not-a-uuid");
        assertThatThrownBy(service::start).isInstanceOf(SessionBindingException.class);
        Files.writeString(file, "a".repeat(101));
        assertThatThrownBy(service::current).isInstanceOf(SessionBindingException.class);
        verifyNoInteractions(sessions);
    }

    @Test void confirmUsesOnlyTheAuthenticatedCentralCandidate() throws Exception {
        UUID device = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        Path file = directory.resolve("meter-device-id.txt");
        Files.writeString(file, device.toString());
        var sessions = mock(SystemSessionService.class);
        when(sessions.sessionBindingAttempt(id)).thenReturn(attempt(device, id, "AWAITING_CONFIRMATION", "central-candidate"));
        when(sessions.sessionBindingAction(id, "confirm", "central-candidate")).thenReturn(attempt(device, id, "BOUND", null));
        var result = new SessionBindingService(sessions, file.toString()).action(id, "confirm");
        assertThat(result.status()).isEqualTo("BOUND");
        verify(sessions).sessionBindingAction(id, "confirm", "central-candidate");
    }

    @Test void wrongDeviceOrTaskIsRejectedBeforeMutation() throws Exception {
        UUID device = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        Path file = directory.resolve("meter-device-id.txt");
        Files.writeString(file, device.toString());
        var sessions = mock(SystemSessionService.class);
        when(sessions.sessionBindingAttempt(id)).thenReturn(attempt(UUID.randomUUID(), id, "READY_FOR_LOGIN", null));
        var service = new SessionBindingService(sessions, file.toString());
        assertThatThrownBy(() -> service.action(id, "detect")).isInstanceOf(SessionBindingException.class);
        verify(sessions, never()).sessionBindingAction(any(), any(), any());
        when(sessions.sessionBindingAttempt(id)).thenReturn(attempt(device, UUID.randomUUID(), "READY_FOR_LOGIN", null));
        assertThatThrownBy(() -> service.status(id)).isInstanceOf(SessionBindingException.class);
    }

    private static SessionBindingAttempt attempt(UUID device, UUID id, String state, String candidate) {
        return new SessionBindingAttempt(id, device, device, UUID.randomUUID(), state, 0,
                "READY_FOR_LOGIN".equals(state), candidate, null, null, Instant.now().plusSeconds(120), null, false, null);
    }
}
