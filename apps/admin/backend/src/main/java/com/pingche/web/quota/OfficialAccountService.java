package com.pingche.web.quota;

import com.pingche.web.auth.DomainException;
import com.pingche.web.auth.RefreshSessionRepository;
import com.pingche.web.officialsession.OfficialSessionLogoutFence;
import com.pingche.web.team.Team;
import com.pingche.web.team.TeamRepository;
import com.pingche.web.user.User;
import com.pingche.web.user.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OfficialAccountService {
    private final OfficialAccountRepository accounts;
    private final OfficialQuotaSnapshotService snapshots;
    private final TeamRepository teams;
    private final UserRepository users;
    private final RefreshSessionRepository sessions;
    private final Clock clock;
    private final OfficialSessionLogoutFence logoutFence;
    private final JdbcTemplate jdbc;
    private final com.pingche.web.officialsession.OfficialQuotaPollingService quotaPolling;

    public OfficialAccountService(OfficialAccountRepository accounts, OfficialQuotaSnapshotService snapshots,
            TeamRepository teams, UserRepository users, RefreshSessionRepository sessions, Clock clock,
            OfficialSessionLogoutFence logoutFence, JdbcTemplate jdbc,
            com.pingche.web.officialsession.OfficialQuotaPollingService quotaPolling) {
        this.accounts = accounts;
        this.snapshots = snapshots;
        this.teams = teams;
        this.users = users;
        this.sessions = sessions;
        this.clock = clock;
        this.logoutFence = logoutFence;
        this.jdbc = jdbc;
        this.quotaPolling = quotaPolling;
    }

    @Transactional(readOnly = true)
    public List<OfficialAccountResponse> list() {
        return accounts.findAllByOrderByLabelAscIdAsc().stream().map(this::response).toList();
    }

    @Transactional
    public OfficialAccountResponse create(OfficialAccountRequest request) {
        String label = request.label().trim();
        if (accounts.existsByLabel(label)) throw conflict("OFFICIAL_ACCOUNT_EXISTS", "账户名称已存在。");
        UUID reporterId = reporterId(request.reporterEmail());
        if (reporterId != null && accounts.existsByReporterUserId(reporterId)) {
            throw conflict("REPORTER_ALREADY_ASSIGNED", "该系统用户已为另一官方账户上报额度。");
        }
        OfficialAccount account = accounts.saveAndFlush(new OfficialAccount(
                label, request.estimatedTotalTokens(), reporterId, clock.instant()));
        return response(account);
    }

    @Transactional
    public OfficialAccountResponse update(UUID id, OfficialAccountRequest request) {
        OfficialAccount account = accounts.findById(id).orElseThrow(this::notFound);
        if (request.version() != null && request.version() != account.getVersion()) {
            throw conflict("OFFICIAL_ACCOUNT_VERSION_CONFLICT", "账户已被其他管理员修改，请刷新后重试。");
        }
        String label = request.label().trim();
        if (accounts.existsByLabelAndIdNot(label, id)) {
            throw conflict("OFFICIAL_ACCOUNT_EXISTS", "账户名称已存在。");
        }
        UUID reporterId = reporterId(request.reporterEmail());
        if (reporterId != null && accounts.existsByReporterUserIdAndIdNot(reporterId, id)) {
            throw conflict("REPORTER_ALREADY_ASSIGNED", "该系统用户已为另一官方账户上报额度。");
        }
        long previousEstimate = account.getEstimatedTotalTokens();
        Instant now = clock.instant();
        account.update(label, request.estimatedTotalTokens(), reporterId, now);
        accounts.saveAndFlush(account);
        if (previousEstimate != account.getEstimatedTotalTokens()) {
            for (Team team : teams.findByOfficialAccountId(id)) {
                team.updateTokenLimits(team.getFiveHourTokenLimit(), team.getWeeklyTokenLimit(),
                        account.getEstimatedTotalTokens(), id, now);
                for (User member : users.findByTeamIdForUpdate(team.getId())) {
                    member.invalidateSessions();
                    sessions.revokeAllForUser(member.getId(), now);
                }
            }
        }
        return response(account);
    }

    @Transactional
    public void delete(UUID id) {
        OfficialAccount account = accounts.findByIdForUpdate(id).orElseThrow(this::notFound);
        logoutFence.assertAvailable(id);
        // Do not cascade into a job held by a helper that will next acquire this account lock.
        if (jdbc.queryForObject("select count(*) from official_browser_login_jobs where official_account_id=? "
                + "and state in ('WAITING_FOR_HELPER','WAITING_FOR_LOGIN','SAVING')", Integer.class, id) > 0)
            throw conflict("BROWSER_LOGIN_BUSY", "官方网页登录正在进行，请先完成或取消登录。");
        if (teams.existsByOfficialAccountId(id)) {
            throw conflict("OFFICIAL_ACCOUNT_IN_USE", "请先解除团队与该账户的关联。");
        }
        accounts.delete(account);
    }

    private UUID reporterId(String email) {
        if (email == null || email.isBlank()) return null;
        String normalized = email.trim().toLowerCase(Locale.ROOT);
        return users.findByEmail(normalized).map(User::getId).orElseThrow(() ->
                new DomainException("REPORTER_USER_NOT_FOUND", "上报用户不存在。", HttpStatus.BAD_REQUEST));
    }

    private OfficialAccountResponse response(OfficialAccount account) {
        UUID reporterId = account.getReporterUserId();
        User reporter = reporterId == null ? null : users.findById(reporterId).orElse(null);
        var polling = quotaPolling.read(account.getId());
        return new OfficialAccountResponse(account.getId(), account.getLabel(),
                account.getEstimatedTotalTokens(), reporter == null ? null : reporter.getEmail(),
                polling.quota(),
                account.getCreatedAt(), account.getUpdatedAt(), account.getVersion(), polling);
    }

    private DomainException notFound() {
        return new DomainException("OFFICIAL_ACCOUNT_NOT_FOUND", "Official account not found", HttpStatus.NOT_FOUND);
    }

    private DomainException conflict(String code, String message) {
        return new DomainException(code, message, HttpStatus.CONFLICT);
    }
}
