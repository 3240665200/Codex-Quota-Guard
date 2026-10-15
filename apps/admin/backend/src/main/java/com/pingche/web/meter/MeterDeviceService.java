package com.pingche.web.meter;

import com.pingche.web.auth.DomainException;
import com.pingche.web.user.User;
import com.pingche.web.user.UserRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MeterDeviceService {
    private static final Duration TOKEN_LIFETIME = Duration.ofDays(30);
    private static final Duration RENEWAL_GRACE = Duration.ofDays(30);
    private static final SecureRandom RANDOM = new SecureRandom();
    private final MeterDeviceRepository devices;
    private final UserRepository users;
    private final Clock clock;

    public MeterDeviceService(MeterDeviceRepository devices, UserRepository users, Clock clock) {
        this.devices = devices;
        this.users = users;
        this.clock = clock;
    }

    @Transactional
    public MeterEnrollmentResponse enroll(UUID userId, UUID deviceId) {
        User user = users.findByIdForUpdate(userId).orElseThrow(this::invalidDevice);
        if (user.getStatus() != User.Status.ACTIVE) throw invalidDevice();
        MeterDevice device = devices.findByIdForUpdate(deviceId).orElse(null);
        if (device != null && (!userId.equals(device.getUserId())
                || device.getStatus() != MeterDevice.Status.ACTIVE)) throw invalidDevice();
        byte[] random = new byte[32];
        RANDOM.nextBytes(random);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        java.util.Arrays.fill(random, (byte) 0);
        Instant now = clock.instant();
        Instant expiresAt = now.plus(TOKEN_LIFETIME);
        String tokenHash = hash(token);
        if (device == null) {
            device = new MeterDevice(deviceId, userId, tokenHash, now, expiresAt);
        } else {
            device.rotate(tokenHash, expiresAt);
        }
        devices.saveAndFlush(device);
        return new MeterEnrollmentResponse(deviceId, token, expiresAt, userId);
    }

    @Transactional(readOnly = true)
    public MeterIdentity authenticate(String token) {
        return authenticate(token, false);
    }

    @Transactional(readOnly = true)
    public MeterIdentity authenticateForRenewal(String token) {
        return authenticate(token, true);
    }

    private MeterIdentity authenticate(String token, boolean renewal) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) throw invalidToken();
        MeterDevice device = devices.findByTokenHash(hash(token)).orElseThrow(this::invalidToken);
        Instant cutoff = renewal ? clock.instant().minus(RENEWAL_GRACE) : clock.instant();
        if (device.getStatus() != MeterDevice.Status.ACTIVE
                || !device.getExpiresAt().isAfter(cutoff)) throw invalidToken();
        User user = users.findById(device.getUserId()).orElseThrow(this::invalidToken);
        if (user.getStatus() != User.Status.ACTIVE) throw invalidToken();
        return new MeterIdentity(device.getId(), device.getUserId());
    }

    @Transactional
    public Instant heartbeat(UUID deviceId) {
        MeterDevice device = devices.findByIdForUpdate(deviceId).orElseThrow(this::invalidToken);
        Instant now = clock.instant();
        assertActive(device, now, false);
        device.seen(now);
        device.renew(now.plus(TOKEN_LIFETIME));
        devices.save(device);
        return now;
    }

    /** The device grant is independent of desktop sessions. A bounded offline
     * grace permits recovery, while administrative revocation remains final. */
    @Transactional
    public MeterRenewalResponse renew(UUID deviceId) {
        MeterDevice device = devices.findByIdForUpdate(deviceId).orElseThrow(this::invalidToken);
        Instant now = clock.instant();
        assertActive(device, now, true);
        Instant expiresAt = now.plus(TOKEN_LIFETIME);
        // Preserve the key so a lost response cannot strand an unattended service.
        device.renew(expiresAt);
        devices.save(device);
        return new MeterRenewalResponse(device.getId(), device.getUserId(), expiresAt);
    }

    private void assertActive(MeterDevice device, Instant now, boolean renewal) {
        Instant cutoff = renewal ? now.minus(RENEWAL_GRACE) : now;
        User user = users.findById(device.getUserId()).orElseThrow(this::invalidToken);
        if (device.getStatus() != MeterDevice.Status.ACTIVE
                || !device.getExpiresAt().isAfter(cutoff)
                || user.getStatus() != User.Status.ACTIVE) throw invalidToken();
    }

    @Transactional(readOnly = true)
    public List<MeterDeviceStatusResponse> listForUser(UUID userId) {
        if (!users.existsById(userId)) {
            throw new DomainException("USER_NOT_FOUND", "User not found", HttpStatus.NOT_FOUND);
        }
        Instant now = clock.instant();
        return devices.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(device -> MeterDeviceStatusResponse.from(device, now)).toList();
    }

    @Transactional
    public void revoke(UUID userId, UUID deviceId) {
        MeterDevice device = devices.findByIdForUpdate(deviceId).orElseThrow(this::invalidDevice);
        if (!device.getUserId().equals(userId)) throw invalidDevice();
        device.revoke();
        devices.save(device);
    }

    static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }

    private DomainException invalidDevice() {
        return new DomainException("METER_DEVICE_UNAVAILABLE", "Device pairing is unavailable",
                HttpStatus.CONFLICT);
    }

    private DomainException invalidToken() {
        return new DomainException("METER_TOKEN_INVALID", "Meter token is invalid",
                HttpStatus.UNAUTHORIZED);
    }
}
