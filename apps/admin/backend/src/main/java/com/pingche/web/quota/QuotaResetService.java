package com.pingche.web.quota;

import com.pingche.web.auth.DomainException;
import com.pingche.web.team.Team;
import com.pingche.web.team.TeamRepository;
import com.pingche.web.user.User;
import com.pingche.web.user.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class QuotaResetService {
    private final UserRepository users;
    private final TeamRepository teams;
    private final Clock clock;

    public QuotaResetService(UserRepository users, TeamRepository teams, Clock clock) {
        this.users = users;
        this.teams = teams;
        this.clock = clock;
    }

    @Transactional
    public void resetUser(UUID userId) {
        User user = users.findByIdForUpdate(userId).orElseThrow(this::userNotFound);
        user.resetQuota(clock.instant());
        users.saveAndFlush(user);
    }

    @Transactional
    public void resetTeam(UUID teamId) {
        Team team = teams.findByIdForUpdate(teamId).orElseThrow(this::teamNotFound);
        team.resetQuota(clock.instant());
        teams.saveAndFlush(team);
    }

    private DomainException userNotFound() {
        return new DomainException("USER_NOT_FOUND", "User not found", HttpStatus.NOT_FOUND);
    }

    private DomainException teamNotFound() {
        return new DomainException("TEAM_NOT_FOUND", "Team not found", HttpStatus.NOT_FOUND);
    }
}
