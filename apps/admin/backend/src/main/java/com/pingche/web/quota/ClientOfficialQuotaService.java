package com.pingche.web.quota;

import com.pingche.web.auth.DomainException;
import com.pingche.web.officialsession.OfficialQuotaPollingService;
import com.pingche.web.team.Team;
import com.pingche.web.team.TeamRepository;
import com.pingche.web.user.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Reads the team's server-polled official account; a desktop never initiates an upstream request. */
@Service
public class ClientOfficialQuotaService {
    public record Response(String source, String accountLabel, String status, boolean enabled,
            int intervalSeconds, boolean stale, String planType,
            List<OfficialQuotaSnapshotRequest.Window> windows, Instant fetchedAt, Instant nextAttemptAt) { }

    private final UserRepository users;
    private final TeamRepository teams;
    private final OfficialAccountRepository accounts;
    private final OfficialQuotaPollingService polling;
    private final Clock clock;

    public ClientOfficialQuotaService(UserRepository users, TeamRepository teams,
            OfficialAccountRepository accounts, OfficialQuotaPollingService polling, Clock clock) {
        this.users = users;
        this.teams = teams;
        this.accounts = accounts;
        this.polling = polling;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Response read(UUID userId) {
        var user = users.findById(userId).orElseThrow(() ->
                new DomainException("USER_NOT_FOUND", "User not found", HttpStatus.NOT_FOUND));
        var team = user.getTeamId() == null ? null : teams.findById(user.getTeamId()).orElse(null);
        var account = team == null || team.getStatus() != Team.Status.ACTIVE || team.getOfficialAccountId() == null
                ? null : accounts.findById(team.getOfficialAccountId()).orElse(null);
        if (account == null) {
            return new Response("SERVER", null, "NOT_LINKED", false, 300, true, null, List.of(), null, null);
        }
        var state = polling.read(account.getId());
        var quota = state.quota();
        Instant fetchedAt = quota == null ? null : quota.fetchedAt();
        boolean stale = !state.enabled() || !List.of("SUCCESS", "FETCHING").contains(state.status())
                || fetchedAt == null || fetchedAt.isBefore(clock.instant().minusSeconds(Math.max(600L, 2L * state.intervalSeconds())));
        return new Response("SERVER", account.getLabel(), state.status(), state.enabled(), state.intervalSeconds(), stale,
                quota == null ? null : quota.planType(), quota == null ? List.of() : quota.windows(), fetchedAt, state.nextAttemptAt());
    }
}
