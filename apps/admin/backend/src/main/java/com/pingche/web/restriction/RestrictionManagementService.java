package com.pingche.web.restriction;

import com.pingche.web.auth.DomainException;
import com.pingche.web.officialsession.OfficialSessionLogoutService;
import com.pingche.web.officialsession.OfficialSessionLogoutService.AutomaticOutcome;
import com.pingche.web.quota.QuotaOverviewService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** Persisted policy, per-account leases and clocks; provider writes use the shared logout journal/fence. */
@Service
public class RestrictionManagementService {
    public static final int QUOTA_CHECK_SECONDS = 30;
    public record Settings(@NotNull Boolean personalQuotaLogoutEnabled, @NotNull Boolean unboundCleanupEnabled,
            @Min(30) @Max(1440) int unboundCheckIntervalMinutes, @NotNull @Min(0) Long version) { }
    public record Policy(boolean personalQuotaLogoutEnabled, boolean unboundCleanupEnabled,
            int unboundCheckIntervalMinutes, int quotaCheckIntervalSeconds, long version, Instant updatedAt) { }
    public record AccountStatus(UUID officialAccountId, String accountLabel, Instant quotaCheckedAt, Instant nextQuotaAt,
            Instant unboundCheckedAt, Instant nextUnboundAt, String lastErrorCode, UUID pendingPlanId, String operationStatus) { }
    public record Execution(UUID id, UUID officialAccountId, String accountLabel, String rule, Instant checkedAt,
            String status, int targetedCount, int excludedCount, int acceptedCount, int unknownCount, String errorCode) { }
    public record Response(Policy policy, List<AccountStatus> accounts, List<Execution> executions) { }
    private record State(boolean quota, boolean cleanup, int interval, long version, UUID owner, Instant updated) { }
    private record Binding(UUID id, UUID user, String session) { }
    private record Work(UUID account, UUID lease, String rule, State policy) { }
    private final JdbcTemplate jdbc;
    private final QuotaOverviewService quotas;
    private final OfficialSessionLogoutService logout;
    private final Clock clock;
    private final ObjectMapper json;
    private final TransactionTemplate tx;

    public RestrictionManagementService(JdbcTemplate jdbc, QuotaOverviewService quotas,
            OfficialSessionLogoutService logout, Clock clock, ObjectMapper json, PlatformTransactionManager manager) {
        this.jdbc = jdbc; this.quotas = quotas; this.logout = logout; this.clock = clock; this.json = json;
        tx = new TransactionTemplate(manager);
    }

    public Response read() {
        State state = policy(false);
        Policy policy = new Policy(state.quota, state.cleanup, state.interval, QUOTA_CHECK_SECONDS, state.version, state.updated);
        List<AccountStatus> statuses = jdbc.query("select a.id,a.label,s.quota_checked_at,s.next_quota_at,s.unbound_checked_at,s.next_unbound_at,s.cooldown_until,s.last_error_code,s.pending_plan_id,p.status as operation_status "
                + "from official_accounts a join official_account_connections c on c.official_account_id=a.id "
                + "left join restriction_account_checks s on s.official_account_id=a.id "
                + "left join official_session_logout_plans p on p.id=s.pending_plan_id order by a.label,a.id", (rs, n) ->
                new AccountStatus(rs.getObject("id", UUID.class), rs.getString("label"), instant(rs,"quota_checked_at"),
                        state.quota ? later(fallback(instant(rs,"next_quota_at"), state.updated.plusSeconds(QUOTA_CHECK_SECONDS)), instant(rs,"cooldown_until")) : null,
                        instant(rs,"unbound_checked_at"), state.cleanup ? later(fallback(instant(rs,"next_unbound_at"), state.updated.plusSeconds(state.interval * 60L)), instant(rs,"cooldown_until")) : null,
                        rs.getString("last_error_code"), rs.getObject("pending_plan_id", UUID.class), rs.getString("operation_status")));
        List<Execution> executions = jdbc.query("select e.*,p.status as plan_status,p.results_json from restriction_executions e "
                + "left join official_session_logout_plans p on p.id=e.plan_id order by e.checked_at desc,e.id desc limit 20", (rs, n) ->
                new Execution(rs.getObject("id", UUID.class), rs.getObject("official_account_id", UUID.class), rs.getString("account_label"),
                        rs.getString("rule"), instant(rs,"checked_at"), fallback(rs.getString("plan_status"), rs.getString("status")),
                        rs.getInt("targeted_count"), rs.getInt("excluded_count"), count(rs.getString("results_json"), "ACCEPTED"),
                        count(rs.getString("results_json"), "UNKNOWN"), fallback(rs.getString("error_code"), resultError(rs.getString("results_json")))));
        return new Response(policy, List.copyOf(statuses), List.copyOf(executions));
    }

    public Response configure(UUID administrator, Settings request) {
        if (administrator == null || request.personalQuotaLogoutEnabled == null || request.unboundCleanupEnabled == null
                || request.version == null || request.version < 0 || request.unboundCheckIntervalMinutes < 30 || request.unboundCheckIntervalMinutes > 1440)
            throw new DomainException("RESTRICTION_SETTINGS_INVALID", "检查间隔应为 30～1440 分钟。", HttpStatus.BAD_REQUEST);
        tx.executeWithoutResult(ignored -> {
            State before = policy(true);
            if (request.version != before.version) throw new DomainException("RESTRICTION_VERSION_CONFLICT", "限制设置已变化，请刷新后再保存。", HttpStatus.CONFLICT);
            Instant now = clock.instant();
            jdbc.update("update restriction_policy set personal_quota_logout_enabled=?,unbound_cleanup_enabled=?,unbound_interval_minutes=?,updated_by=?,updated_at=?,version=version+1 where id=1",
                    request.personalQuotaLogoutEnabled, request.unboundCleanupEnabled, request.unboundCheckIntervalMinutes, administrator, at(now));
            // Configuration never releases an existing official-write fence or an in-flight database lease.
            if (request.personalQuotaLogoutEnabled != before.quota)
                jdbc.update("update restriction_account_checks set next_quota_at=?", request.personalQuotaLogoutEnabled ? at(now) : null);
            if (request.unboundCleanupEnabled != before.cleanup || request.unboundCheckIntervalMinutes != before.interval)
                jdbc.update("update restriction_account_checks set next_unbound_at=?", request.unboundCleanupEnabled ? at(now.plusSeconds(request.unboundCheckIntervalMinutes * 60L)) : null);
        });
        return read();
    }

    public List<UUID> dueAccounts() {
        State policy = policy(false);
        if (!policy.quota && !policy.cleanup) return List.of();
        List<String> conditions = new ArrayList<>();
        List<Object> args = new ArrayList<>();
        Instant now = clock.instant();
        args.add(at(now));
        args.add(at(now));
        if (policy.quota) { conditions.add("(s.next_quota_at is null or s.next_quota_at<=?)"); args.add(at(now)); }
        if (policy.cleanup) { conditions.add("(s.next_unbound_at is null or s.next_unbound_at<=?)"); args.add(at(now)); }
        args.add(at(now));
        return jdbc.query("select a.id from official_accounts a join official_account_connections c on c.official_account_id=a.id "
                + "left join restriction_account_checks s on s.official_account_id=a.id where c.bound_account_id is not null and c.bound_user_id is not null "
                + "and (s.lease_until is null or s.lease_until<=?) and (s.cooldown_until is null or s.cooldown_until<=?) and (" + String.join(" or ", conditions) + ") "
                + "order by coalesce(s.next_quota_at,s.next_unbound_at,?),a.id limit 32", (rs, n) -> rs.getObject(1, UUID.class), args.toArray());
    }

    public void checkAccount(UUID account) {
        Work work = reserve(account);
        if (work == null) return;
        AutomaticOutcome result;
        boolean record = true;
        try {
            List<Binding> candidates = "PERSONAL_QUOTA".equals(work.rule) ? quotaBindings(account) : List.of();
            if ("PERSONAL_QUOTA".equals(work.rule) && candidates.isEmpty()) {
                result = new AutomaticOutcome(null, "NO_TARGETS", 0, 0, null, null);
                record = false;
            } else {
                Set<String> sessions = candidates.stream().map(Binding::session).collect(Collectors.toUnmodifiableSet());
                result = logout.automate(work.policy.owner, account, "PERSONAL_QUOTA".equals(work.rule) ? "SELECTED" : "UNBOUND", sessions,
                        selected -> authorized(work, candidates, selected), prepared -> prepared(work, prepared));
            }
        } catch (RuntimeException failure) {
            String code = failure instanceof DomainException domain ? safeCode(domain.getCode()) : "RESTRICTION_CHECK_FAILED";
            result = new AutomaticOutcome(null, "FAILED", 0, 0, code, clock.instant().plusSeconds(300));
        }
        complete(work, result, record);
    }

    private Work reserve(UUID account) {
        return tx.execute(ignored -> {
            if (!lockAccount(account)) return null;
            State policy = policy(true);
            if ((!policy.quota && !policy.cleanup) || policy.owner == null) return null;
            Instant now = clock.instant();
            if (jdbc.queryForObject("select count(*) from restriction_account_checks where official_account_id=?", Integer.class, account) == 0)
                jdbc.update("insert into restriction_account_checks(official_account_id,next_quota_at,next_unbound_at) values(?,?,?)",
                        account, policy.quota ? at(now) : null, policy.cleanup ? at(now.plusSeconds(policy.interval * 60L)) : null);
            var rows = jdbc.query("select * from restriction_account_checks where official_account_id=? for update", (rs, n) ->
                    new Check(instant(rs,"next_quota_at"), instant(rs,"next_unbound_at"), instant(rs,"lease_until"), instant(rs,"cooldown_until"), rs.getObject("pending_plan_id", UUID.class)), account);
            Check state = rows.get(0);
            if (state.lease != null && state.lease.isAfter(now)) return null;
            if (state.cooldown != null && state.cooldown.isAfter(now)) return null;
            if (state.pending != null) {
                var plan = jdbc.query("select status,results_json from official_session_logout_plans where id=?", (rs, n) ->
                        new Pending(rs.getString("status"), rs.getString("results_json")), state.pending);
                if (!plan.isEmpty()) {
                    Pending pending = plan.get(0);
                    if (Set.of("EXECUTING", "UNKNOWN").contains(pending.status)) {
                        jdbc.update("update restriction_account_checks set next_quota_at=?,next_unbound_at=?,last_error_code=? where official_account_id=?",
                                policy.quota ? at(now.plusSeconds(30)) : null,
                                policy.cleanup && due(state.cleanup, now) ? at(now.plusSeconds(30)) : at(state.cleanup),
                                "UNKNOWN".equals(pending.status) ? "OFFICIAL_LOGOUT_OUTCOME_UNKNOWN" : null, account);
                        return null;
                    }
                    if (Set.of("FAILED", "PARTIAL", "INVALIDATED").contains(pending.status)) {
                        String code = resultError(pending.results);
                        long delay = "OFFICIAL_RATE_LIMITED".equals(code) ? 900 : 300;
                        jdbc.update("update restriction_account_checks set pending_plan_id=null,next_quota_at=?,next_unbound_at=?,last_error_code=? where official_account_id=?",
                                policy.quota ? at(now.plusSeconds(delay)) : null,
                                policy.cleanup && due(state.cleanup, now) ? at(now.plusSeconds(delay)) : at(state.cleanup),
                                fallback(code,"RESTRICTION_LOGOUT_FAILED"), account);
                        return null;
                    }
                }
                jdbc.update("update restriction_account_checks set pending_plan_id=null where official_account_id=?", account);
            }
            String rule = policy.quota && due(state.quota, now) ? "PERSONAL_QUOTA"
                    : policy.cleanup && due(state.cleanup, now) ? "UNBOUND" : null;
            if (rule == null) return null;
            UUID lease = UUID.randomUUID();
            jdbc.update("update restriction_account_checks set lease_id=?,lease_until=? where official_account_id=?", lease, at(now.plusSeconds(120)), account);
            return new Work(account, lease, rule, policy);
        });
    }

    private List<Binding> quotaBindings(UUID account) {
        List<Binding> current = bindings(account);
        Set<UUID> exhausted = current.stream().map(Binding::user).distinct().filter(this::personalExhausted).collect(Collectors.toSet());
        return current.stream().filter(binding -> exhausted.contains(binding.user)).toList();
    }

    private List<Binding> bindings(UUID account) {
        return jdbc.query("select b.id,b.user_id,b.session_id from session_bindings b join users u on u.id=b.user_id "
                + "join teams t on t.id=u.team_id join official_account_connections c on c.official_account_id=b.official_account_id "
                + "where b.official_account_id=? and b.status='ACTIVE' and u.status='ACTIVE' and t.status='ACTIVE' "
                + "and b.team_id=u.team_id and b.team_version=t.version and t.official_account_id=b.official_account_id "
                + "and b.connection_revision=c.revision order by b.id", (rs, n) ->
                new Binding(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class), rs.getString("session_id")), account);
    }

    private boolean personalExhausted(UUID user) {
        var overview = quotas.overview(user);
        return overview.policy().fiveHourTokenLimit() != null && overview.usage().fiveHourUsedTokens() >= overview.policy().fiveHourTokenLimit()
                || overview.policy().weeklyTokenLimit() != null && overview.usage().weeklyUsedTokens() >= overview.policy().weeklyTokenLimit();
    }

    private boolean authorized(Work work, List<Binding> candidates, Set<String> selected) {
        State current = policy(true);
        if (current.version != work.policy.version || ("PERSONAL_QUOTA".equals(work.rule) ? !current.quota : !current.cleanup)) return false;
        int owned = jdbc.queryForObject("select count(*) from restriction_account_checks s where s.official_account_id=? and "
                + "((s.lease_id=? and s.lease_until>?) or exists (select 1 from restriction_executions e where e.id=? and e.plan_id=s.pending_plan_id))",
                Integer.class, work.account, work.lease, at(clock.instant()), work.lease);
        if (owned != 1) return false;
        if (!"PERSONAL_QUOTA".equals(work.rule) || selected.isEmpty()) return true;
        List<Binding> live = bindings(work.account);
        for (String session : selected) {
            List<Binding> expected = candidates.stream().filter(binding -> session.equals(binding.session)).toList();
            if (expected.size() != 1 || !live.contains(expected.get(0)) || !personalExhausted(expected.get(0).user)) return false;
        }
        return true;
    }

    private void prepared(Work work, AutomaticOutcome prepared) {
        if (jdbc.update("update restriction_account_checks set pending_plan_id=? where official_account_id=? and lease_id=?",
                prepared.planId(), work.account, work.lease) != 1)
            throw new DomainException("RESTRICTION_LEASE_EXPIRED", "本次检查已被更新的任务取代。", HttpStatus.CONFLICT);
        recordExecution(work, prepared);
    }

    private void recordExecution(Work work, AutomaticOutcome result) {
        if (jdbc.queryForObject("select count(*) from restriction_executions where id=?", Integer.class, work.lease) > 0) return;
        String label = jdbc.queryForObject("select label from official_accounts where id=?", String.class, work.account);
        jdbc.update("insert into restriction_executions(id,official_account_id,account_label,rule,checked_at,policy_version,plan_id,status,targeted_count,excluded_count,error_code) values(?,?,?,?,?,?,?,?,?,?,?)",
                work.lease, work.account, label, work.rule, at(clock.instant()), work.policy.version, result.planId(), result.status(),
                result.targetedCount(), result.excludedCount(), result.errorCode());
    }

    private void complete(Work work, AutomaticOutcome result, boolean record) {
        tx.executeWithoutResult(ignored -> {
            if (!lockAccount(work.account)) return;
            // Keep the same policy -> state order used by settings changes and claims.
            State currentPolicy = policy(true);
            Integer owned = jdbc.queryForObject("select count(*) from restriction_account_checks where official_account_id=? and lease_id=?", Integer.class, work.account, work.lease);
            if (owned == 0) return;
            Instant now = clock.instant();
            boolean quota = "PERSONAL_QUOTA".equals(work.rule);
            String previousError = jdbc.queryForObject("select last_error_code from restriction_account_checks where official_account_id=?", String.class, work.account);
            boolean noQuotaTarget = quota && record && "NO_TARGETS".equals(result.status());
            String diagnostic = noQuotaTarget ? "RESTRICTION_NO_ELIGIBLE_SESSION" : result.errorCode();
            Instant cooldown = later(jdbc.queryForObject("select cooldown_until from restriction_account_checks where official_account_id=?", (rs,n) -> instant(rs,"cooldown_until"), work.account), result.retryAt());
            Instant next = result.errorCode() == null ? now.plusSeconds(quota ? QUOTA_CHECK_SECONDS : work.policy.interval * 60L)
                    : result.retryAt() != null && result.retryAt().isAfter(now) ? result.retryAt()
                    : now.plusSeconds("OFFICIAL_LOGOUT_BUSY".equals(result.errorCode()) ? 30 : 300);
            if (noQuotaTarget) next = now.plusSeconds(300);
            if (currentPolicy.version == work.policy.version) {
                jdbc.update("update restriction_account_checks set " + (quota ? "quota_checked_at=?,next_quota_at=?" : "unbound_checked_at=?,next_unbound_at=?")
                        + ",lease_id=null,lease_until=null,pending_plan_id=?,last_error_code=?,cooldown_until=? where official_account_id=? and lease_id=?",
                        at(now), at(next), result.planId(), diagnostic, at(cooldown), work.account, work.lease);
            } else {
                jdbc.update("update restriction_account_checks set lease_id=null,lease_until=null,pending_plan_id=?,last_error_code=?,cooldown_until=? where official_account_id=? and lease_id=?",
                        result.planId(), diagnostic, at(cooldown), work.account, work.lease);
            }
            if (record && (result.planId() != null || diagnostic == null || !java.util.Objects.equals(previousError, diagnostic))) recordExecution(work, result);
        });
    }

    private State policy(boolean lock) {
        return jdbc.queryForObject("select * from restriction_policy where id=1" + (lock ? " for update" : ""), (rs, n) ->
                new State(rs.getBoolean("personal_quota_logout_enabled"), rs.getBoolean("unbound_cleanup_enabled"),
                        rs.getInt("unbound_interval_minutes"), rs.getLong("version"), rs.getObject("updated_by", UUID.class), instant(rs,"updated_at")));
    }
    private boolean lockAccount(UUID account) { return !jdbc.query("select id from official_accounts where id=? for update", (rs, n) -> rs.getObject(1, UUID.class), account).isEmpty(); }
    private int count(String results, String status) {
        if (results == null) return 0;
        try { var node = json.readTree(results); int count = 0; for (var item : node.isArray() ? node : node.path("results")) if (status.equals(item.path("status").asString())) count++; return count; }
        catch (RuntimeException invalid) { return 0; }
    }
    private String resultError(String results) {
        if (results == null) return null;
        try { var node = json.readTree(results); for (var item : node.isArray() ? node : node.path("results")) if (!item.path("errorCode").isNull() && item.has("errorCode")) return safeCode(item.path("errorCode").asString()); }
        catch (RuntimeException invalid) { return "RESTRICTION_RESULT_UNAVAILABLE"; }
        return null;
    }
    private static String safeCode(String value) { return value != null && value.matches("(?:OFFICIAL|PROXY|RESTRICTION)_[A-Z0-9_]{1,75}") ? value : "RESTRICTION_CHECK_FAILED"; }
    private static boolean due(Instant value, Instant now) { return value == null || !value.isAfter(now); }
    private static <T> T fallback(T value, T other) { return value == null ? other : value; }
    private static Instant later(Instant first, Instant second) { return first == null ? second : second == null || first.isAfter(second) ? first : second; }
    private static Instant instant(ResultSet rs, String column) throws SQLException { var value = rs.getTimestamp(column); return value == null ? null : value.toInstant(); }
    private static OffsetDateTime at(Instant value) { return value == null ? null : value.atOffset(ZoneOffset.UTC); }
    private record Check(Instant quota, Instant cleanup, Instant lease, Instant cooldown, UUID pending) { }
    private record Pending(String status, String results) { }
}
