package com.codexquotaguard.backend.system;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
                .thenReturn(new MeterEnrollment(deviceId, "A".repeat(43), "2026-11-07T00:00:00Z", userId));
        var session = new SystemSessionService(gateway);
        session.login("alice@example.com", "password");

        boolean paired = new MeterPairingService(session, new ObjectMapper(),
                deviceIdFile.toString(), inbox.toString()).pairIfInstalled();

        assertThat(paired).isTrue();
        String content = Files.readString(inbox);
        assertThat(content).contains(deviceId.toString(), "A".repeat(43), userId.toString());
        assertThat(content).doesNotContain("access-secret", "refresh-secret");
    }

    @Test void neverWritesAnEnrollmentForAnotherSystemOwner() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID device = UUID.randomUUID();
        Path file = temporaryDirectory.resolve("meter-device-id.txt");
        Path inbox = temporaryDirectory.resolve("meter-pairing.json");
        Files.writeString(file, device.toString());
        Files.writeString(inbox, "previous pairing remains");
        ControlPlaneGateway gateway = mock(ControlPlaneGateway.class);
        var user = new SystemUser(owner, "alice@example.com", "Alice", "USER", "ACTIVE", null,null,null,null,null,null,null);
        when(gateway.login("alice@example.com", "password"))
                .thenReturn(new ControlPlaneAuthSession("access", "refresh", user));
        when(gateway.enrollMeter("access", device))
                .thenReturn(new MeterEnrollment(device, "A".repeat(43), "2026-11-07T00:00:00Z", UUID.randomUUID()));
        var session = new SystemSessionService(gateway);
        session.login("alice@example.com", "password");
        var pairing = new MeterPairingService(session, new ObjectMapper(), file.toString(), inbox.toString());
        assertThatThrownBy(pairing::pairIfInstalled).isInstanceOf(IllegalStateException.class);
        assertThat(Files.readString(inbox)).isEqualTo("previous pairing remains");
    }

    @Test void userSwitchAfterEnrollmentCannotPublishOldCredentials() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID device = UUID.randomUUID();
        Path file = temporaryDirectory.resolve("meter-device-id.txt");
        Path inbox = temporaryDirectory.resolve("meter-pairing.json");
        Files.writeString(file, device.toString());
        Files.writeString(inbox, "previous pairing remains");
        ControlPlaneGateway gateway = mock(ControlPlaneGateway.class);
        var user = new SystemUser(owner, "alice@example.com", "Alice", "USER", "ACTIVE", null,null,null,null,null,null,null);
        when(gateway.login("alice@example.com", "password"))
                .thenReturn(new ControlPlaneAuthSession("access", "refresh", user));
        when(gateway.enrollMeter("access", device))
                .thenReturn(new MeterEnrollment(device, "A".repeat(43), "2026-11-07T00:00:00Z", owner));
        var session = new SystemSessionService(gateway);
        session.login("alice@example.com", "password");
        var mapper = mock(ObjectMapper.class);
        when(mapper.writeValueAsBytes(org.mockito.ArgumentMatchers.any())).thenAnswer(call -> {
            session.logout();
            session.login("alice@example.com", "password");
            return "stale credential payload".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        });
        var pairing = new MeterPairingService(session, mapper, file.toString(), inbox.toString());
        assertThatThrownBy(pairing::pairIfInstalled).isInstanceOf(SystemSessionRequiredException.class);
        assertThat(Files.readString(inbox)).isEqualTo("previous pairing remains");
    }
}
