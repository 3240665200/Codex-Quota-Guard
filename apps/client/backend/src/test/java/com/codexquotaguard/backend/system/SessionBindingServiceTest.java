package com.codexquotaguard.backend.system;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SessionBindingServiceTest {
    @TempDir Path directory;

    @Test void deviceIdentityIsReadFromTrustedMeterFileAndNeverFromRenderer() throws Exception {
        UUID device = UUID.randomUUID();
        Path file = directory.resolve("meter-device-id.txt");
        Files.writeString(file, device.toString());
        var sessions = mock(SystemSessionService.class);
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
}
