package com.codexquotaguard.backend.quota;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

public final class QuotaSnapshotFactory {

    private static final int SCHEMA_VERSION = 1;

    private QuotaSnapshotFactory() {
    }

    public static QuotaSnapshot create(
            String roomId,
            String memberId,
            String displayName,
            AccountState account,
            Map<String, RawRateLimitBucket> rawBuckets,
            Instant capturedAt
    ) {
        validateIdentifier("room", roomId);
        validateIdentifier("member", memberId);
        validateDisplayName(displayName);
        if (account == null) {
            throw new QuotaSnapshotException("Account state is required.");
        }
        if (capturedAt == null) {
            throw new QuotaSnapshotException("Capture time is required.");
        }

        Map<String, RawRateLimitBucket> input = rawBuckets == null ? Map.of() : rawBuckets;
        List<Map.Entry<String, RawRateLimitBucket>> entries = new ArrayList<>(input.entrySet());
        entries.sort(Comparator.comparing(Map.Entry::getKey));

        List<RateLimitBucket> buckets = new ArrayList<>(entries.size());
        for (Map.Entry<String, RawRateLimitBucket> entry : entries) {
            buckets.add(normalizeBucket(entry.getKey(), entry.getValue()));
        }

        Instant normalizedTime = capturedAt.truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        return new QuotaSnapshot(
                SCHEMA_VERSION,
                roomId.trim(),
                memberId.trim(),
                displayName.trim(),
                normalizedTime,
                normalizedTime,
                "codex-app-server",
                account.authenticated(),
                account.planType(),
                buckets,
                true
        );
    }

    private static RateLimitBucket normalizeBucket(String mapKey, RawRateLimitBucket rawBucket) {
        if (rawBucket == null) {
            throw new QuotaSnapshotException("Rate limit bucket must not be null.");
        }
        String limitId = rawBucket.limitId() == null || rawBucket.limitId().isBlank()
                ? mapKey
                : rawBucket.limitId().trim();
        validateIdentifier("limit", limitId);
        return new RateLimitBucket(
                limitId,
                trimToEmpty(rawBucket.limitName()),
                normalizeWindow(rawBucket.primary()),
                normalizeWindow(rawBucket.secondary()),
                trimToEmpty(rawBucket.planType()),
                rawBucket.spendControlReached()
        );
    }

    private static RateLimitWindow normalizeWindow(RawRateLimitWindow rawWindow) {
        if (rawWindow == null) {
            return null;
        }
        Double usedPercent = rawWindow.usedPercent();
        if (usedPercent == null || !Double.isFinite(usedPercent) || usedPercent < 0 || usedPercent > 100) {
            throw new QuotaSnapshotException("Used percentage must be a finite number between 0 and 100.");
        }
        Long duration = rawWindow.windowDurationMins();
        if (duration == null || duration < 0) {
            throw new QuotaSnapshotException("Window duration must not be negative.");
        }
        Long resetsAt = rawWindow.resetsAt();
        if (resetsAt == null || resetsAt <= 0) {
            throw new QuotaSnapshotException("Reset time must be a positive Unix timestamp.");
        }
        return new RateLimitWindow(
                usedPercent,
                roundToThreeDecimals(100 - usedPercent),
                duration,
                Instant.ofEpochSecond(resetsAt)
        );
    }

    private static void validateIdentifier(String label, String value) {
        if (value == null || value.trim().isEmpty() || value.trim().length() > 96) {
            throw new QuotaSnapshotException(label + " identifier must contain 1 to 96 characters.");
        }
    }

    private static void validateDisplayName(String displayName) {
        if (displayName == null || displayName.trim().isEmpty() || displayName.trim().codePointCount(0, displayName.trim().length()) > 80) {
            throw new QuotaSnapshotException("Display name must contain 1 to 80 characters.");
        }
    }

    private static String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private static double roundToThreeDecimals(double value) {
        return Math.round(value * 1_000d) / 1_000d;
    }
}
