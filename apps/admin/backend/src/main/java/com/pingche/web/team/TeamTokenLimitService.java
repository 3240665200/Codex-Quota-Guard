package com.pingche.web.team;

import com.pingche.web.auth.DomainException;
import com.pingche.web.auth.RefreshSessionRepository;
import com.pingche.web.quota.OfficialAccount;
import com.pingche.web.quota.OfficialAccountRepository;
import com.pingche.web.user.User;
import com.pingche.web.user.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TeamTokenLimitService {

    private final TeamRepository teams;
    private final UserRepository users;
    private final RefreshSessionRepository sessions;
    private final OfficialAccountRepository officialAccounts;
    private final Clock clock;

    public TeamTokenLimitService(TeamRepository teams, UserRepository users,
            RefreshSessionRepository sessions, OfficialAccountRepository officialAccounts, Clock clock) {
        this.teams = teams;
        this.users = users;
        this.sessions = sessions;
        this.officialAccounts = officialAccounts;
        this.clock = clock;
    }

    @Transactional
    public TeamResponse update(UUID teamId, TeamTokenLimitRequest request) {
        Team team = teams.findByIdForUpdate(teamId).orElseThrow(() -> new DomainException(
                "TEAM_NOT_FOUND", "Team not found", HttpStatus.NOT_FOUND));
        if (request.version() != null && request.version() != team.getVersion()) {
            throw new DomainException("TEAM_VERSION_CONFLICT",
                    "团队已被其他管理员修改，请刷新后重试。", HttpStatus.CONFLICT);
        }
        Instant now = clock.instant();
        Long totalLimit = request.totalTokenLimit();
        if (request.officialAccountId() != null) {
            OfficialAccount account = officialAccounts.findById(request.officialAccountId())
                    .orElseThrow(() -> new DomainException("OFFICIAL_ACCOUNT_NOT_FOUND",
                            "Official account not found", HttpStatus.NOT_FOUND));
            if (totalLimit != null && totalLimit.longValue() != account.getEstimatedTotalTokens()) {
                throw new DomainException("TEAM_TOTAL_LIMIT_MISMATCH",
                        "团队总额度须等于官方账户登记的估算 Token 总量。", HttpStatus.BAD_REQUEST);
            }
            totalLimit = account.getEstimatedTotalTokens();
        }
        if (team.updateTokenLimits(request.fiveHourTokenLimit(), request.weeklyTokenLimit(),
                totalLimit, request.officialAccountId(), now)) {
            teams.saveAndFlush(team);
            List<User> members = users.findByTeamIdForUpdate(teamId);
            for (User member : members) {
                member.invalidateSessions();
                sessions.revokeAllForUser(member.getId(), now);
            }
            users.saveAll(members);
        }
        return TeamResponse.from(team, users.countByTeamId(teamId));
    }
}
