package com.codexquotaguard.backend.system;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.codexquotaguard.backend.controlplane.ControlPlaneAuthSession;
import com.codexquotaguard.backend.controlplane.ControlPlaneGateway;
import com.codexquotaguard.backend.controlplane.MeterEnrollment;
import com.codexquotaguard.backend.controlplane.SystemUser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

class MeterPairingServiceTest {

    @TempDir Path temporaryDirectory;

    @Test
    void writesOnlyTheMeterScopedEnrollmentIntoTheRustServiceInbox() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();
        Path deviceIdFile = temporaryDirectory.resolve("meter-device-id.txt");
        Path inbox = temporaryDirectory.resolve("meter-pairing.json");
        Files.writeString(deviceIdFile, deviceId.toString());
        ControlPlaneGateway gateway = mock(ControlPlaneGateway.class);
        var user = new SystemUser(userId, "alice@example.com", "Alice", "USER", "ACTIVE",
                null, null, null, null, null, null, null);
        when(gateway.login("alice@example.com", "password"))
                .thenReturn(new ControlPlaneAuthSession("access-secret", "refresh-secret", user));
        when(gateway.enrollMeter("access-secret", deviceId))
                .thenReturn(new MeterEnrollment(deviceId, "A".repeat(43), "2026-11-07T00:00:00Z"));
        var session = new SystemSessionService(gateway);
        session.login("alice@example.com", "password");

        boolean paired = new MeterPairingService(session, new ObjectMapper(),
                deviceIdFile.toString(), inbox.toString()).pairIfInstalled();

        assertThat(paired).isTrue();
        String content = Files.readString(inbox);
        assertThat(content).contains(deviceId.toString(), "A".repeat(43));
        assertThat(content).doesNotContain("access-secret", "refresh-secret");
    }
}
