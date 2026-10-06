package com.pingche.web.quota;

import com.pingche.web.auth.DomainException;
import com.pingche.web.codex.CodexAccount;
import com.pingche.web.codex.CodexAccountRepository;
import com.pingche.web.user.User;
import com.pingche.web.user.UserRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Internal persistence boundary for trusted per-user quota measurements. There
 * is intentionally no controller around this service; a desktop client cannot
 * claim a lower usage value or submit a measurement on another user's behalf.
 */
@Service
public class QuotaUsageService {

    private final UserRepository users;
    private final CodexAccountRepository accounts;
    private final UserQuotaUsageRepository usage;
    private final Clock clock;

    public QuotaUsageService(
            UserRepository users,
            CodexAccountRepository accounts,
            UserQuotaUsageRepository usage,
            Clock clock
    ) {
        this.users = users;
        this.accounts = accounts;
        this.usage = usage;
        this.clock = clock;
    }

    @Transactional
    public void recordMeasuredUsage(
            UUID userId,
            UUID codexAccountId,
            QuotaUsageMeasurement measurement
    ) {
        // Keep account -> user ordering consistent with account deletion and
        // assignment, both of which can affect the user's account reference.
        CodexAccount account = accounts.findByIdForUpdate(codexAccountId)
                .orElseThrow(this::codexAccountNotFound);
        if (account.getStatus() != CodexAccount.Status.ACTIVE) {
            throw codexAccountUnavailable();
        }
        User user = users.findByIdForUpdate(userId).orElseThrow(this::userNotFound);
        if (!codexAccountId.equals(user.getAssignedCodexAccountId())) {
            throw new DomainException(
                    "CODEX_ACCOUNT_NOT_ASSIGNED",
                    "The Codex account is not assigned to this user",
                    HttpStatus.CONFLICT
            );
        }

        Instant measuredAt = Instant.now(clock);
        BigDecimal fiveHourUsedPercent = validatePercent(
                measurement.fiveHourUsedPercent(),
                "fiveHourUsedPercent"
        );
        BigDecimal weeklyUsedPercent = validatePercent(
                measurement.weeklyUsedPercent(),
                "weeklyUsedPercent"
        );
        validateReset(measurement.fiveHourResetsAt(), measuredAt, "fiveHourResetsAt");
        validateReset(measurement.weeklyResetsAt(), measuredAt, "weeklyResetsAt");

        UserQuotaUsage current = usage.findByUserIdForUpdate(userId).orElse(null);
        if (current == null) {
            usage.save(new UserQuotaUsage(
                    userId,
                    codexAccountId,
                    fiveHourUsedPercent,
                    weeklyUsedPercent,
                    measurement.fiveHourResetsAt(),
                    measurement.weeklyResetsAt(),
                    measuredAt
            ));
            return;
        }
        current.update(
                codexAccountId,
                fiveHourUsedPercent,
                weeklyUsedPercent,
                measurement.fiveHourResetsAt(),
                measurement.weeklyResetsAt(),
                measuredAt
        );
        usage.save(current);
    }

    private BigDecimal validatePercent(BigDecimal value, String field) {
        if (value == null || value.compareTo(BigDecimal.ZERO) < 0
                || value.compareTo(BigDecimal.valueOf(100)) > 0
                || value.scale() > 2) {
            throw new DomainException(
                    "INVALID_QUOTA_MEASUREMENT",
                    field + " must be a percentage between 0 and 100 with at most two decimals",
                    HttpStatus.BAD_REQUEST
            );
        }
        return value.setScale(2, RoundingMode.UNNECESSARY);
    }

    private void validateReset(Instant resetAt, Instant measuredAt, String field) {
        if (resetAt != null && resetAt.isBefore(measuredAt)) {
            throw new DomainException(
                    "INVALID_QUOTA_MEASUREMENT",
                    field + " must not be before the measurement time",
                    HttpStatus.BAD_REQUEST
            );
        }
    }

    private DomainException userNotFound() {
        return new DomainException("USER_NOT_FOUND", "User not found", HttpStatus.NOT_FOUND);
    }

    private DomainException codexAccountNotFound() {
        return new DomainException(
                "CODEX_ACCOUNT_NOT_FOUND",
                "Codex account was not found",
                HttpStatus.NOT_FOUND
        );
    }

    private DomainException codexAccountUnavailable() {
        return new DomainException(
                "CODEX_ACCOUNT_UNAVAILABLE",
                "The assigned Codex account is unavailable",
                HttpStatus.CONFLICT
        );
    }
}
