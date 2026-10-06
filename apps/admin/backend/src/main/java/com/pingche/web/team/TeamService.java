package com.pingche.web.team;

import com.pingche.web.auth.DomainException;
import com.pingche.web.auth.RefreshSessionRepository;
import com.pingche.web.user.User;
import com.pingche.web.user.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TeamService {

    private static final int DEFAULT_QUOTA_PERCENT = 100;

    private final TeamRepository teams;
    private final UserRepository users;
    private final RefreshSessionRepository sessions;
    private final Clock clock;

    public TeamService(
            TeamRepository teams,
            UserRepository users,
            RefreshSessionRepository sessions,
            Clock clock
    ) {
        this.teams = teams;
        this.users = users;
        this.sessions = sessions;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<TeamResponse> list() {
        return teams.findAllByOrderByNameAscIdAsc().stream()
                .map(team -> response(team))
                .toList();
    }

    @Transactional(readOnly = true)
    public TeamResponse get(UUID teamId) {
        return response(teams.findById(teamId).orElseThrow(this::teamNotFound));
    }

    @Transactional
    public TeamResponse create(TeamCreateRequest request) {
        String name = normalizeName(request.name());
        if (teams.existsByName(name)) {
            throw teamNameExists();
        }
        int fiveHourQuotaPercent = resolvePercent(request.fiveHourQuotaPercent());
        int weeklyQuotaPercent = resolvePercent(request.weeklyQuotaPercent());
        Team team = new Team(
                name,
                normalizeDescription(request.description()),
                request.status() == null ? Team.Status.ACTIVE : request.status(),
                fiveHourQuotaPercent,
                weeklyQuotaPercent,
                Instant.now(clock)
        );
        try {
            return response(teams.saveAndFlush(team));
        } catch (DataIntegrityViolationException exception) {
            throw teamNameExists();
        }
    }

    @Transactional
    public TeamResponse update(UUID teamId, TeamUpdateRequest request) {
        if (!containsUpdate(request)) {
            throw validation("At least one team field is required");
        }
        Team team = teams.findByIdForUpdate(teamId).orElseThrow(this::teamNotFound);
        if (request.version() != null && request.version() != team.getVersion()) {
            throw versionConflict();
        }

        String name = request.name() == null ? team.getName() : normalizeName(request.name());
        if (!name.equals(team.getName()) && teams.existsByNameAndIdNot(name, teamId)) {
            throw teamNameExists();
        }
        String description = request.description() == null
                ? team.getDescription()
                : normalizeDescription(request.description());
        Team.Status status = request.status() == null ? team.getStatus() : request.status();
        int fiveHourQuotaPercent = request.fiveHourQuotaPercent() == null
                ? team.getFiveHourQuotaPercent()
                : resolvePercent(request.fiveHourQuotaPercent());
        int weeklyQuotaPercent = request.weeklyQuotaPercent() == null
                ? team.getWeeklyQuotaPercent()
                : resolvePercent(request.weeklyQuotaPercent());
        boolean accessPolicyChanged = status != team.getStatus()
                || fiveHourQuotaPercent != team.getFiveHourQuotaPercent()
                || weeklyQuotaPercent != team.getWeeklyQuotaPercent();

        team.update(
                name,
                description,
                status,
                fiveHourQuotaPercent,
                weeklyQuotaPercent,
                Instant.now(clock)
        );
        try {
            teams.saveAndFlush(team);
        } catch (DataIntegrityViolationException exception) {
            throw teamNameExists();
        }
        if (accessPolicyChanged) {
            invalidateMembers(teamId);
        }
        return response(team);
    }

    @Transactional
    public void delete(UUID teamId) {
        Team team = teams.findByIdForUpdate(teamId).orElseThrow(this::teamNotFound);
        Instant now = Instant.now(clock);
        List<User> members = users.findByTeamIdForUpdate(teamId);
        for (User member : members) {
            member.clearTeam(now);
            invalidate(member, now);
        }
        users.saveAll(members);
        teams.delete(team);
        teams.flush();
    }

    private TeamResponse response(Team team) {
        return TeamResponse.from(team, users.countByTeamId(team.getId()));
    }

    private void invalidateMembers(UUID teamId) {
        Instant now = Instant.now(clock);
        List<User> members = users.findByTeamIdForUpdate(teamId);
        for (User member : members) {
            invalidate(member, now);
        }
        users.saveAll(members);
    }

    private void invalidate(User user, Instant now) {
        user.invalidateSessions();
        sessions.revokeAllForUser(user.getId(), now);
    }

    private boolean containsUpdate(TeamUpdateRequest request) {
        return request.name() != null
                || request.description() != null
                || request.status() != null
                || request.fiveHourQuotaPercent() != null
                || request.weeklyQuotaPercent() != null;
    }

    private int resolvePercent(Integer value) {
        if (value == null) {
            return DEFAULT_QUOTA_PERCENT;
        }
        if (value < 0 || value > 100) {
            throw validation("quota percent must be between 0 and 100");
        }
        return value;
    }

    private String normalizeName(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) {
            throw validation("name must not be blank");
        }
        return normalized;
    }

    private String normalizeDescription(String value) {
        return value == null ? "" : value.trim();
    }

    private DomainException validation(String message) {
        return new DomainException("VALIDATION_ERROR", message, HttpStatus.BAD_REQUEST);
    }

    private DomainException teamNotFound() {
        return new DomainException("TEAM_NOT_FOUND", "Team not found", HttpStatus.NOT_FOUND);
    }

    private DomainException teamNameExists() {
        return new DomainException(
                "TEAM_NAME_EXISTS",
                "A team with this name already exists",
                HttpStatus.CONFLICT
        );
    }

    private DomainException versionConflict() {
        return new DomainException(
                "TEAM_VERSION_CONFLICT",
                "The team was changed by another administrator",
                HttpStatus.CONFLICT
        );
    }
}
