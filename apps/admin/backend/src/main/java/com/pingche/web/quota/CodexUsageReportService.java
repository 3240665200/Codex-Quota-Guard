package com.pingche.web.quota;

import com.pingche.web.auth.DomainException;
import com.pingche.web.user.User;
import com.pingche.web.user.UserRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Stores idempotent, client-reported token use. Reports are not upstream-verified. */
@Service
public class CodexUsageReportService {

    private static final Duration MAX_EVENT_AGE = Duration.ofDays(30);
    private static final Duration MAX_CLOCK_SKEW = Duration.ofMinutes(5);

    private final UserRepository users;
    private final CodexUsageEventRepository events;
    private final QuotaOverviewService overview;
    private final Clock clock;
    private final UsageBillingService billing;

    public CodexUsageReportService(UserRepository users,
            CodexUsageEventRepository events,
            QuotaOverviewService overview, Clock clock, UsageBillingService billing) {
        this.users = users;
        this.events = events;
        this.overview = overview;
        this.clock = clock;
        this.billing = billing;
    }

    @Transactional
    public QuotaOverviewResponse report(UUID userId, CodexUsageReportRequest request) {
        return report(userId, null, request);
    }

    @Transactional
    public QuotaOverviewResponse report(UUID userId, UUID deviceId, CodexUsageReportRequest request) {
        User user = users.findByIdForUpdate(userId).orElseThrow(this::unavailable);
        if (user.getStatus() != User.Status.ACTIVE) {
            throw unavailable();
        }

        Instant now = clock.instant();
        List<CodexUsageEvent> newEvents = new ArrayList<>();
        Set<String> queued = new HashSet<>();
        for (CodexUsageReportRequest.Event event : request.events()) {
            if (event.occurredAt().isAfter(now.plus(MAX_CLOCK_SKEW))
                    || event.occurredAt().isBefore(now.minus(MAX_EVENT_AGE))) {
                throw new DomainException("INVALID_USAGE_EVENT_TIME",
                        "Codex 用量事件时间超出允许范围。", HttpStatus.BAD_REQUEST);
            }
            String key = eventKey(userId, event.eventId());
            if (queued.add(key) && !events.existsById(key)) {
                CodexUsageEvent stored = new CodexUsageEvent(key, userId, user.getTeamId(),
                        event.totalTokens(), event.occurredAt(), now, event.source().name(),
                        event.model(), event.reasoningEffort(), event.inputTokens(), event.cachedInputTokens(),
                        event.cacheWriteInputTokens(), event.outputTokens(), event.reasoningOutputTokens());
                stored.setServiceTier(event.serviceTier());
                stored.setMeterDeviceId(deviceId);
                newEvents.add(stored);
            }
        }
        if (!newEvents.isEmpty()) {
            events.saveAllAndFlush(newEvents);
            billing.record(newEvents);
        }
        return overview.overview(userId);
    }

    @Transactional
    public int correctModes(UUID userId, UUID deviceId, UsageModeCorrectionRequest request) {
        User user = users.findByIdForUpdate(userId).orElseThrow(this::unavailable);
        if (user.getStatus() != User.Status.ACTIVE) throw unavailable();
        int updated = 0;
        for (var item : request.events()) updated += billing.correctReportedMode(userId, deviceId, eventKey(userId,item.eventId()),item.serviceTier());
        return updated;
    }

    private String eventKey(UUID userId, String eventId) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(userId.toString().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) ':');
            return HexFormat.of().formatHex(digest.digest(eventId.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", exception);
        }
    }

    private DomainException unavailable() {
        return new DomainException("USAGE_REPORT_UNAVAILABLE",
                "当前系统账户不可用。", HttpStatus.CONFLICT);
    }
}
