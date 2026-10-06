package com.pingche.web.quota;

import com.pingche.web.auth.DomainException;
import com.pingche.web.auth.RefreshSessionRepository;
import com.pingche.web.team.Team;
import com.pingche.web.team.TeamRepository;
import com.pingche.web.user.User;
import com.pingche.web.user.UserRepository;
import com.pingche.web.user.UserResponse;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class QuotaPolicyService {

    private final UserRepository users;
    private final TeamRepository teams;
    private final RefreshSessionRepository sessions;
    private final Clock clock;

    public QuotaPolicyService(
            UserRepository users,
            TeamRepository teams,
            RefreshSessionRepository sessions,
            Clock clock
    ) {
        this.users = users;
        this.teams = teams;
        this.sessions = sessions;
        this.clock = clock;
    }

    @Transactional
    public UserResponse update(UUID userId, QuotaPolicyUpdateRequest request) {
        UUID teamId = request.teamId();
        if (teamId != null) {
            Team team = teams.findByIdForUpdate(teamId).orElseThrow(this::teamNotFound);
            if (team.getStatus() != Team.Status.ACTIVE) {
                throw new DomainException(
                        "TEAM_UNAVAILABLE",
                        "Only an active team can be assigned",
                        HttpStatus.CONFLICT
                );
            }
        }
        // Team mutations lock the team before their members. Keep the same
        // ordering here so a policy assignment cannot deadlock with a team
        // policy/status update.
        User user = users.findByIdForUpdate(userId).orElseThrow(this::userNotFound);
        validatePercent(request.fiveHourQuotaPercent(), "fiveHourQuotaPercent");
        validatePercent(request.weeklyQuotaPercent(), "weeklyQuotaPercent");

        Instant now = Instant.now(clock);
        if (user.updateQuotaPolicy(
                teamId,
                request.fiveHourQuotaPercent(),
                request.weeklyQuotaPercent(),
                now
        )) {
            user.invalidateSessions();
            sessions.revokeAllForUser(userId, now);
            users.saveAndFlush(user);
        }
        return UserResponse.from(user);
    }

    private void validatePercent(Integer value, String field) {
        if (value != null && (value < 0 || value > 100)) {
            throw new DomainException(
                    "VALIDATION_ERROR",
                    field + " must be between 0 and 100",
                    HttpStatus.BAD_REQUEST
            );
        }
    }

    private DomainException userNotFound() {
        return new DomainException("USER_NOT_FOUND", "User not found", HttpStatus.NOT_FOUND);
    }

    private DomainException teamNotFound() {
        return new DomainException("TEAM_NOT_FOUND", "Team not found", HttpStatus.NOT_FOUND);
    }
}
