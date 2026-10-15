package com.codexquotaguard.backend.system;

import com.codexquotaguard.backend.controlplane.MeterEnrollment;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/** Bridges a system login to the separately installed Rust meter service. */
@Service
public final class MeterPairingService {
    private final SystemSessionService session;
    private final ObjectMapper mapper;
    private final Path deviceIdFile;
    private final Path pairingInbox;
    private volatile String state = "NOT_INSTALLED";
    private UUID pairedUser;
    private UUID pairedDevice;
    private long pairedGeneration = -1;

    @Autowired
    public MeterPairingService(SystemSessionService session, ObjectMapper mapper,
            @Value("${quota-guard.meter.device-id-file:${user.home}/AppData/Roaming/com.codexquotaguard.desktop/meter-device-id.txt}")
            String deviceIdFile,
            @Value("${quota-guard.meter.pairing-inbox:${user.home}/AppData/Roaming/com.codexquotaguard.desktop/meter-pairing.json}")
            String pairingInbox) {
        this.session = session;
        this.mapper = mapper;
        this.deviceIdFile = Path.of(deviceIdFile).toAbsolutePath().normalize();
        this.pairingInbox = Path.of(pairingInbox).toAbsolutePath().normalize();
    }

    /** Never returns a device token or official credential to the renderer. */
    public synchronized boolean pairIfInstalled() {
        if (!Files.isRegularFile(deviceIdFile, LinkOption.NOFOLLOW_LINKS)) {
            state = "NOT_INSTALLED";
            return false;
        }
        byte[] payload = null;
        Path temporary = null;
        try {
            if (Files.size(deviceIdFile) > 100) throw new IOException();
            UUID deviceId = UUID.fromString(Files.readString(deviceIdFile).trim());
            SystemSessionService.Probe probe = session.pairingProbe();
            UUID userId = probe.overview().user().id();
            if (deviceId.equals(pairedDevice) && userId.equals(pairedUser) && probe.generation() == pairedGeneration) return true;
            MeterEnrollment enrollment = session.enrollMeter(deviceId, probe);
            if (!deviceId.equals(enrollment.deviceId()) || !userId.equals(enrollment.userId())) throw new IllegalStateException();
            payload = mapper.writeValueAsBytes(new PairingInbox(deviceId, enrollment.deviceToken(), enrollment.userId()));
            Path parent = pairingInbox.getParent();
            if (parent == null) throw new IOException();
            Files.createDirectories(parent);
            temporary = Files.createTempFile(parent, ".meter-pairing-", ".tmp");
            Files.write(temporary, payload);
            Path ready = temporary;
            session.withCurrentIdentity(probe, () -> {
                try {
                    Files.move(ready, pairingInbox, StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException failure) { throw new java.io.UncheckedIOException(failure); }
            });
            pairedDevice = deviceId;
            pairedUser = userId;
            pairedGeneration = probe.generation();
            state = "PAIRING_SENT";
            return true;
        } catch (ControlPlaneUnavailableException | SessionStorageException | SystemSessionRequiredException exception) {
            state = "PAIRING_FAILED";
            throw exception;
        } catch (IOException | RuntimeException exception) {
            state = "PAIRING_FAILED";
            throw new IllegalStateException("Meter pairing is unavailable");
        } finally {
            if (payload != null) Arrays.fill(payload, (byte) 0);
            if (temporary != null) {
                try { Files.deleteIfExists(temporary); } catch (IOException ignored) { }
            }
        }
    }

    public String state() { return state; }

    private record PairingInbox(UUID deviceId, String deviceToken, UUID userId) {
        @Override public String toString() { return "PairingInbox[deviceToken=REDACTED]"; }
    }
}
