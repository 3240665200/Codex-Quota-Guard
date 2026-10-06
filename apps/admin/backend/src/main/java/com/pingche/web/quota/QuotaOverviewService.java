package com.pingche.web.quota;

import com.pingche.web.auth.DomainException;
import com.pingche.web.codex.ClientSystemUserResponse;
import com.pingche.web.codex.CodexAccount;
import com.pingche.web.codex.CodexAccountRepository;
import com.pingche.web.codex.ManagedCodexAccountResponse;
import com.pingche.web.team.Team;
import com.pingche.web.team.TeamRepository;
import com.pingche.web.user.User;
import com.pingche.web.user.UserRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class QuotaOverviewService {

    private static final int DEFAULT_QUOTA_PERCENT = 100;
    private static final Duration MAX_MEASUREMENT_AGE = Duration.ofMinutes(5);
    private static final String CODEX_ACCOUNT_UNASSIGNED = "CODEX_ACCOUNT_UNASSIGNED";
    private static final String CODEX_ACCOUNT_UNAVAILABLE = "CODEX_ACCOUNT_UNAVAILABLE";
    private static final String TEAM_UNAVAILABLE = "TEAM_UNAVAILABLE";
    private static final String USAGE_NOT_MEASURED = "USAGE_NOT_MEASURED";
    private static final String FIVE_HOUR_QUOTA_EXCEEDED = "FIVE_HOUR_QUOTA_EXCEEDED";
    private static final String WEEKLY_QUOTA_EXCEEDED = "WEEKLY_QUOTA_EXCEEDED";

    private final UserRepository users;
    private final CodexAccountRepository accounts;
    private final TeamRepository teams;
    private final UserQuotaUsageRepository usage;
    private final Clock clock;

    public QuotaOverviewService(
            UserRepository users,
            CodexAccountRepository accounts,
            TeamRepository teams,
            UserQuotaUsageRepository usage,
            Clock clock
    ) {
        this.users = users;
        this.accounts = accounts;
        this.teams = teams;
        this.usage = usage;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public QuotaOverviewResponse overview(UUID userId) {
        User user = users.findById(userId).orElseThrow(this::userNotFound);
        Team team = findTeam(user);
        CodexAccount account = findAccount(user);
        QuotaOverviewResponse.TeamReference teamReference = team == null
                ? null
                : new QuotaOverviewResponse.TeamReference(team.getId(), team.getName());
        QuotaOverviewResponse.Policy policy = policy(user, team);
        QuotaOverviewResponse.Usage currentUsage = currentUsage(user, account, policy);

        String blockReason = blockReason(account, team, currentUsage, policy);
        return new QuotaOverviewResponse(
                account != null,
                ClientSystemUserResponse.from(user),
                account == null ? null : ManagedCodexAccountResponse.from(account),
                teamReference,
                policy,
                currentUsage,
                blockReason == null,
                blockReason
        );
    }

    private Team findTeam(User user) {
        UUID teamId = user.getTeamId();
        return teamId == null ? null : teams.findById(teamId).orElse(null);
    }

    private CodexAccount findAccount(User user) {
        UUID accountId = user.getAssignedCodexAccountId();
        return accountId == null ? null : accounts.findById(accountId).orElse(null);
    }

    private QuotaOverviewResponse.Policy policy(User user, Team team) {
        ResolvedLimit fiveHour = resolveLimit(
                user.getFiveHourQuotaPercent(),
                team == null ? null : team.getFiveHourQuotaPercent()
        );
        ResolvedLimit weekly = resolveLimit(
                user.getWeeklyQuotaPercent(),
                team == null ? null : team.getWeeklyQuotaPercent()
        );
        return new QuotaOverviewResponse.Policy(
                fiveHour.limitPercent(),
                weekly.limitPercent(),
                fiveHour.source(),
                weekly.source()
        );
    }

    private ResolvedLimit resolveLimit(Integer userOverride, Integer teamDefault) {
        if (userOverride != null) {
            return new ResolvedLimit(userOverride, QuotaOverviewResponse.Policy.Source.USER);
        }
        if (teamDefault != null) {
            return new ResolvedLimit(teamDefault, QuotaOverviewResponse.Policy.Source.TEAM);
        }
        return new ResolvedLimit(DEFAULT_QUOTA_PERCENT, QuotaOverviewResponse.Policy.Source.DEFAULT);
    }

    private QuotaOverviewResponse.Usage currentUsage(
            User user,
            CodexAccount account,
            QuotaOverviewResponse.Policy policy
    ) {
        if (account == null) {
            return QuotaOverviewResponse.Usage.notMeasured();
        }
        UserQuotaUsage measured = usage.findById(user.getId()).orElse(null);
        Instant now = Instant.now(clock);
        if (measured == null || !measured.belongsTo(account.getId()) || !isCurrent(measured, now)) {
            return QuotaOverviewResponse.Usage.notMeasured();
        }
        return new QuotaOverviewResponse.Usage(
                QuotaOverviewResponse.MeasurementStatus.CURRENT,
                measured.getFiveHourUsedPercent(),
                measured.getWeeklyUsedPercent(),
                remaining(policy.fiveHourLimitPercent(), measured.getFiveHourUsedPercent()),
                remaining(policy.weeklyLimitPercent(), measured.getWeeklyUsedPercent()),
                measured.getFiveHourResetsAt(),
                measured.getWeeklyResetsAt(),
                measured.getMeasuredAt()
        );
    }

    private boolean isCurrent(UserQuotaUsage measured, Instant now) {
        if (measured.getMeasuredAt().plus(MAX_MEASUREMENT_AGE).isBefore(now)) {
            return false;
        }
        return !isExpired(measured.getFiveHourResetsAt(), now)
                && !isExpired(measured.getWeeklyResetsAt(), now);
    }

    private boolean isExpired(Instant resetAt, Instant now) {
        return resetAt != null && !resetAt.isAfter(now);
    }

    private BigDecimal remaining(int limitPercent, BigDecimal usedPercent) {
        return BigDecimal.valueOf(limitPercent).subtract(usedPercent).max(BigDecimal.ZERO);
    }

    private String blockReason(
            CodexAccount account,
            Team team,
            QuotaOverviewResponse.Usage currentUsage,
            QuotaOverviewResponse.Policy policy
    ) {
        if (account == null) {
            return CODEX_ACCOUNT_UNASSIGNED;
        }
        if (account.getStatus() != CodexAccount.Status.ACTIVE) {
            return CODEX_ACCOUNT_UNAVAILABLE;
        }
        if (team != null && team.getStatus() != Team.Status.ACTIVE) {
            return TEAM_UNAVAILABLE;
        }
        if (currentUsage.measurementStatus() != QuotaOverviewResponse.MeasurementStatus.CURRENT) {
            return USAGE_NOT_MEASURED;
        }
        if (currentUsage.fiveHourUsedPercent().compareTo(
                BigDecimal.valueOf(policy.fiveHourLimitPercent())) >= 0) {
            return FIVE_HOUR_QUOTA_EXCEEDED;
        }
        if (currentUsage.weeklyUsedPercent().compareTo(
                BigDecimal.valueOf(policy.weeklyLimitPercent())) >= 0) {
            return WEEKLY_QUOTA_EXCEEDED;
        }
        return null;
    }

    private DomainException userNotFound() {
        return new DomainException(
                "AUTH_TOKEN_INVALID",
                "Access token is invalid",
                HttpStatus.UNAUTHORIZED
        );
    }

    private record ResolvedLimit(int limitPercent, QuotaOverviewResponse.Policy.Source source) {
    }
}
