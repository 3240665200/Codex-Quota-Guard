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
        QuotaOverviewResponse.Policy policy = policy(user, team);
        QuotaOverviewResponse.Usage currentUsage = currentUsage(user, policy);

        String blockReason = blockReason(team, currentUsage, policy);
        return new QuotaOverviewResponse(
                QuotaSystemUserResponse.from(user),
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

    private QuotaOverviewResponse.Policy policy(User user, Team team) {
        ResolvedLimit fiveHour = resolveLimit(
                user.getFiveHourTokenLimit(),
                team == null ? null : team.getFiveHourTokenLimit()
        );
        ResolvedLimit weekly = resolveLimit(
                user.getWeeklyTokenLimit(),
                team == null ? null : team.getWeeklyTokenLimit()
        );
        return new QuotaOverviewResponse.Policy(
                fiveHour.limit(),
                weekly.limit(),
                fiveHour.source(),
                weekly.source()
        );
    }

    private ResolvedLimit resolveLimit(Long userOverride, Long teamDefault) {
        if (userOverride != null) {
            return new ResolvedLimit(userOverride, QuotaOverviewResponse.Policy.Source.USER);
        }
        if (teamDefault != null) {
            return new ResolvedLimit(teamDefault, QuotaOverviewResponse.Policy.Source.TEAM);
        }
        return new ResolvedLimit(null, QuotaOverviewResponse.Policy.Source.UNCONFIGURED);
    }

    private QuotaOverviewResponse.Usage currentUsage(
            User user,
            QuotaOverviewResponse.Policy policy
    ) {
        Instant now = Instant.now(clock);
        long fiveHourUsed = usage.sumTokensSince(user.getId(), now.minus(FIVE_HOUR_WINDOW));
        long weeklyUsed = usage.sumTokensSince(user.getId(), now.minus(WEEKLY_WINDOW));
        return new QuotaOverviewResponse.Usage(
                QuotaOverviewResponse.MeasurementStatus.CLIENT_REPORTED,
                fiveHourUsed,
                weeklyUsed,
                remaining(policy.fiveHourTokenLimit(), fiveHourUsed),
                remaining(policy.weeklyTokenLimit(), weeklyUsed),
                null,
                null,
                usage.latestReceivedAt(user.getId())
        );
    }

    private Long remaining(Long tokenLimit, long used) {
        return tokenLimit == null ? null : (used >= tokenLimit ? 0L : tokenLimit - used);
    }

    private String blockReason(
            Team team,
            QuotaOverviewResponse.Usage currentUsage,
            QuotaOverviewResponse.Policy policy
    ) {
        if (team != null && team.getStatus() != Team.Status.ACTIVE) {
            return TEAM_UNAVAILABLE;
        }
        if (policy.fiveHourTokenLimit() == null || policy.weeklyTokenLimit() == null) {
            return TOKEN_QUOTA_UNCONFIGURED;
        }
        if (currentUsage.fiveHourUsedTokens() >= policy.fiveHourTokenLimit()) {
            return FIVE_HOUR_QUOTA_EXCEEDED;
        }
        if (currentUsage.weeklyUsedTokens() >= policy.weeklyTokenLimit()) {
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
