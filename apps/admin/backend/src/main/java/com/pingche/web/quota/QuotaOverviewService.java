package com.pingche.web.quota;

import com.pingche.web.auth.DomainException;
import com.pingche.web.team.Team;
import com.pingche.web.team.TeamRepository;
import com.pingche.web.user.User;
import com.pingche.web.user.UserRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class QuotaOverviewService {

    private static final Duration FIVE_HOUR_WINDOW = Duration.ofHours(5);
    private static final Duration WEEKLY_WINDOW = Duration.ofDays(7);
    private static final String TEAM_UNAVAILABLE = "TEAM_UNAVAILABLE";
    private static final String TOKEN_QUOTA_UNCONFIGURED = "TOKEN_QUOTA_UNCONFIGURED";
    private static final String FIVE_HOUR_QUOTA_EXCEEDED = "FIVE_HOUR_TOKEN_QUOTA_EXCEEDED";
    private static final String WEEKLY_QUOTA_EXCEEDED = "WEEKLY_TOKEN_QUOTA_EXCEEDED";

    private final UserRepository users;
    private final TeamRepository teams;
    private final CodexUsageEventRepository usage;
    private final Clock clock;

    public QuotaOverviewService(
            UserRepository users,
            TeamRepository teams,
            CodexUsageEventRepository usage,
            Clock clock
    ) {
        this.users = users;
        this.teams = teams;
        this.usage = usage;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public QuotaOverviewResponse overview(UUID userId) {
        User user = users.findById(userId).orElseThrow(this::userNotFound);
        Team team = findTeam(user);
        QuotaOverviewResponse.TeamReference teamReference = team == null
                ? null
                : new QuotaOverviewResponse.TeamReference(team.getId(), team.getName());
        QuotaOverviewResponse.Policy policy = policy(user);
        Instant now = clock.instant();
        TeamQuotaOverviewResponse teamQuota = team == null ? null : teamQuota(team, now);
        QuotaOverviewResponse.Usage currentUsage = currentUsage(user, now);

        String blockReason = blockReason(team, teamQuota, currentUsage, policy);
        return new QuotaOverviewResponse(
                QuotaSystemUserResponse.from(user),
                teamReference,
                teamQuota,
                policy,
                currentUsage,
                blockReason == null,
                blockReason
        );
    }

    @Transactional(readOnly = true)
    public TeamQuotaOverviewResponse teamOverview(UUID teamId) {
        Team team = teams.findById(teamId).orElseThrow(() -> new DomainException(
                "TEAM_NOT_FOUND", "Team not found", HttpStatus.NOT_FOUND));
        return teamQuota(team, clock.instant());
    }

    private TeamQuotaOverviewResponse teamQuota(Team team, Instant now) {
        Instant sinceFiveHour = since(now, FIVE_HOUR_WINDOW, team.getQuotaResetAt());
        Instant sinceWeekly = since(now, WEEKLY_WINDOW, team.getQuotaResetAt());
        Instant resetAt = resetAtOrEpoch(team.getQuotaResetAt());
        long fiveHourUsed = usage.sumTeamTokensSince(team.getId(), sinceFiveHour, now, resetAt);
        long weeklyUsed = usage.sumTeamTokensSince(team.getId(), sinceWeekly, now, resetAt);
        long totalUsed = usage.sumTeamTokensSince(team.getId(), resetAt, now, resetAt);
        return new TeamQuotaOverviewResponse(
                team.getId(), team.getName(), team.getStatus().name(),
                users.countByTeamId(team.getId()),
                team.getFiveHourTokenLimit(), team.getWeeklyTokenLimit(), team.getTotalTokenLimit(),
                fiveHourUsed, weeklyUsed, totalUsed,
                remaining(team.getFiveHourTokenLimit(), fiveHourUsed),
                remaining(team.getWeeklyTokenLimit(), weeklyUsed),
                remaining(team.getTotalTokenLimit(), totalUsed),
                usage.latestTeamReceivedAt(team.getId())
        );
    }

    private Team findTeam(User user) {
        UUID teamId = user.getTeamId();
        return teamId == null ? null : teams.findById(teamId).orElse(null);
    }

    private QuotaOverviewResponse.Policy policy(User user) {
        ResolvedLimit fiveHour = resolveLimit(user.getFiveHourTokenLimit());
        ResolvedLimit weekly = resolveLimit(user.getWeeklyTokenLimit());
        return new QuotaOverviewResponse.Policy(
                fiveHour.limit(),
                weekly.limit(),
                fiveHour.source(),
                weekly.source()
        );
    }

    private ResolvedLimit resolveLimit(Long personalLimit) {
        if (personalLimit != null) {
            return new ResolvedLimit(personalLimit, QuotaOverviewResponse.Policy.Source.USER);
        }
        return new ResolvedLimit(null, QuotaOverviewResponse.Policy.Source.UNCONFIGURED);
    }

    private QuotaOverviewResponse.Usage currentUsage(User user, Instant now) {
        long fiveHourUsed = usage.sumTokensSince(user.getId(),
                since(now, FIVE_HOUR_WINDOW, user.getQuotaResetAt()), now,
                resetAtOrEpoch(user.getQuotaResetAt()));
        long weeklyUsed = usage.sumTokensSince(user.getId(),
                since(now, WEEKLY_WINDOW, user.getQuotaResetAt()), now,
                resetAtOrEpoch(user.getQuotaResetAt()));
        Long personalFiveHourRemaining = remaining(user.getFiveHourTokenLimit(), fiveHourUsed);
        Long personalWeeklyRemaining = remaining(user.getWeeklyTokenLimit(), weeklyUsed);
        return new QuotaOverviewResponse.Usage(
                QuotaOverviewResponse.MeasurementStatus.CLIENT_REPORTED,
                fiveHourUsed,
                weeklyUsed,
                personalFiveHourRemaining,
                personalWeeklyRemaining,
                null,
                null,
                usage.latestReceivedAt(user.getId())
        );
    }

    private Instant since(Instant now, Duration window, Instant resetAt) {
        Instant windowStart = now.minus(window);
        return resetAt == null || resetAt.isBefore(windowStart) ? windowStart : resetAt;
    }

    private Instant resetAtOrEpoch(Instant resetAt) {
        return resetAt == null ? Instant.EPOCH : resetAt;
    }

    private Long remaining(Long tokenLimit, long used) {
        return tokenLimit == null ? null : (used >= tokenLimit ? 0L : tokenLimit - used);
    }

    private String blockReason(
            Team team,
            TeamQuotaOverviewResponse teamQuota,
            QuotaOverviewResponse.Usage currentUsage,
            QuotaOverviewResponse.Policy policy
    ) {
        if (team != null && team.getStatus() != Team.Status.ACTIVE) {
            return TEAM_UNAVAILABLE;
        }
        if (teamQuota != null && teamQuota.weeklyTokenLimit() == null) {
            return TOKEN_QUOTA_UNCONFIGURED;
        }
        if (teamQuota == null && policy.weeklyTokenLimit() == null) {
            return TOKEN_QUOTA_UNCONFIGURED;
        }
        if (teamQuota != null && Long.valueOf(0).equals(teamQuota.fiveHourRemainingTokens())) {
            return "TEAM_FIVE_HOUR_TOKEN_QUOTA_EXCEEDED";
        }
        if (teamQuota != null && Long.valueOf(0).equals(teamQuota.weeklyRemainingTokens())) {
            return "TEAM_WEEKLY_TOKEN_QUOTA_EXCEEDED";
        }
        if (teamQuota != null && Long.valueOf(0).equals(teamQuota.totalRemainingTokens())) {
            return "TEAM_TOTAL_TOKEN_QUOTA_EXCEEDED";
        }
        if (policy.fiveHourSource() == QuotaOverviewResponse.Policy.Source.USER
                && policy.fiveHourTokenLimit() != null
                && currentUsage.fiveHourUsedTokens() >= policy.fiveHourTokenLimit()) {
            return FIVE_HOUR_QUOTA_EXCEEDED;
        }
        if (policy.weeklySource() == QuotaOverviewResponse.Policy.Source.USER
                && policy.weeklyTokenLimit() != null
                && currentUsage.weeklyUsedTokens() >= policy.weeklyTokenLimit()) {
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

    private record ResolvedLimit(Long limit, QuotaOverviewResponse.Policy.Source source) {
    }
}
