package com.codexquotaguard.backend.system;

import com.codexquotaguard.backend.controlplane.OfficialSessionBinding;
import com.codexquotaguard.backend.controlplane.SessionBindingAttempt;
import com.codexquotaguard.backend.controlplane.SessionBindingCurrent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** The renderer supplies only an opaque task ID. Identity always comes from the installed meter. */
@Service
public final class SessionBindingService {
    private final SystemSessionService sessions;
    private final Path deviceIdFile;

    public SessionBindingService(SystemSessionService sessions,
            @Value("${quota-guard.meter.device-id-file:${user.home}/AppData/Roaming/com.codexquotaguard.desktop/meter-device-id.txt}") String deviceIdFile) {
        this.sessions = sessions;
        this.deviceIdFile = Path.of(deviceIdFile).toAbsolutePath().normalize();
    }

    public SessionBindingCurrent current() {
        UUID device = deviceId();
        SessionBindingCurrent result = sessions.sessionBindingCurrent(device, device);
        if (result == null) throw new SessionBindingException();
        if (result.attempt() != null) validate(result.attempt(), device, result.attempt().id());
        validateBinding(result.binding(), device);
        return result;
    }

    public SessionBindingAttempt start() {
        UUID device = deviceId();
        return validate(sessions.startSessionBinding(device, device), device, null);
    }

    public SessionBindingAttempt status(UUID id) {
        UUID device = deviceId();
        return validate(sessions.sessionBindingAttempt(id), device, id);
    }

    public SessionBindingAttempt action(UUID id, String action) {
        UUID device = deviceId();
        SessionBindingAttempt previous = validate(sessions.sessionBindingAttempt(id), device, id);
        if (!java.util.Set.of("prepare", "detect", "confirm", "cancel").contains(action)) throw new SessionBindingException();
        // Fetch the unique candidate from the authenticated central task, never from WebView input.
        String candidate = null;
        if ("confirm".equals(action)) {
            if (!"AWAITING_CONFIRMATION".equals(previous.status()) || previous.candidateSessionId() == null
                    || previous.candidateSessionId().isBlank()) throw new SessionBindingException();
            candidate = previous.candidateSessionId();
        }
        if ("detect".equals(action) && (!"READY_FOR_LOGIN".equals(previous.status()) || !previous.loginAllowed())) {
            throw new SessionBindingException();
        }
        return validate(sessions.sessionBindingAction(id, action, candidate), device, id);
    }

    private UUID deviceId() {
        try {
            if (!Files.isRegularFile(deviceIdFile, LinkOption.NOFOLLOW_LINKS) || Files.size(deviceIdFile) > 100) throw new IOException();
            String value = Files.readString(deviceIdFile).trim();
            UUID parsed = UUID.fromString(value);
            if (!parsed.toString().equalsIgnoreCase(value)) throw new IOException();
            return parsed;
        } catch (IOException | IllegalArgumentException failure) { throw new SessionBindingException(); }
    }

    private SessionBindingAttempt validate(SessionBindingAttempt value, UUID device, UUID expectedId) {
        if (value == null || value.id() == null || (expectedId != null && !expectedId.equals(value.id()))
                || !device.equals(value.deviceId()) || !device.equals(value.enrollmentId())) throw new SessionBindingException();
        validateBinding(value.binding(), device);
        return value;
    }
    private void validateBinding(OfficialSessionBinding binding, UUID device) {
        if (binding != null && (!device.equals(binding.deviceId()) || !device.equals(binding.enrollmentId()))) {
            throw new SessionBindingException();
        }
    }
}
