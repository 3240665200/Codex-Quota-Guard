package com.pingche.web.team;

import com.pingche.web.auth.DomainException;
import com.pingche.web.auth.RefreshSessionRepository;
import com.pingche.web.user.User;
import com.pingche.web.user.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TeamMembershipService {
    private final TeamRepository teams;
    private final UserRepository users;
    private final RefreshSessionRepository sessions;
    private final Clock clock;

    public TeamMembershipService(TeamRepository teams, UserRepository users,
            RefreshSessionRepository sessions, Clock clock) {
        this.teams = teams;
        this.users = users;
        this.sessions = sessions;
        this.clock = clock;
    }

    @Transactional
    public TeamResponse update(UUID teamId, TeamMembersUpdateRequest request) {
        Map<UUID, TeamMembersUpdateRequest.Change> changes = validate(request);
        // Match existing team mutations: lock the team, then users in database UUID order.
        Team team = teams.findByIdForUpdate(teamId).orElseThrow(() ->
                new DomainException("TEAM_NOT_FOUND", "Team not found", HttpStatus.NOT_FOUND));
        List<User> members = users.findByIdsForUpdate(new ArrayList<>(changes.keySet()));
        if (members.size() != changes.size()) throw validation("One or more users do not exist");

        // Validate every expectation before changing any membership or session.
        for (User member : members) {
            TeamMembersUpdateRequest.Change change = changes.get(member.getId());
            UUID actualTeamId = member.getTeamId();
            if (!Objects.equals(actualTeamId, change.expectedTeamId())
                    || (!change.selected() && actualTeamId != null && !teamId.equals(actualTeamId))) {
                throw new DomainException("TEAM_MEMBERSHIP_CONFLICT",
                        "Team membership conflicts with the requested changes", HttpStatus.CONFLICT);
            }
            if (change.selected() && !teamId.equals(actualTeamId) && team.getStatus() != Team.Status.ACTIVE) {
                throw new DomainException("TEAM_UNAVAILABLE", "Only an active team can be assigned", HttpStatus.CONFLICT);
            }
        }

        Instant now = clock.instant();
        List<User> changed = new ArrayList<>();
        for (User member : members) {
            UUID nextTeamId = changes.get(member.getId()).selected() ? teamId : null;
            if (member.updateQuotaPolicy(nextTeamId, member.getFiveHourQuotaPercent(), member.getWeeklyQuotaPercent(), now)) {
                member.invalidateSessions();
                changed.add(member);
            }
        }
        if (!changed.isEmpty()) {
            // Refresh revocation clears the persistence context; persist the whole batch first.
            users.saveAllAndFlush(changed);
            for (User member : changed) sessions.revokeAllForUser(member.getId(), now);
        }
        return TeamResponse.from(team, users.countByTeamId(teamId));
    }

    private Map<UUID, TeamMembersUpdateRequest.Change> validate(TeamMembersUpdateRequest request) {
        if (request == null || request.changes() == null || request.changes().isEmpty() || request.changes().size() > 100)
            throw validation("Between 1 and 100 membership changes are required");
        Map<UUID, TeamMembersUpdateRequest.Change> changes = new HashMap<>();
        for (TeamMembersUpdateRequest.Change change : request.changes()) {
            if (change == null || change.userId() == null || change.selected() == null)
                throw validation("Each membership change requires a user and selection");
            if (changes.putIfAbsent(change.userId(), change) != null)
                throw validation("Each user may appear only once");
        }
        return changes;
    }

    private DomainException validation(String message) {
        return new DomainException("VALIDATION_ERROR", message, HttpStatus.BAD_REQUEST);
    }
}
