package com.pingche.web.admin;

import com.pingche.web.auth.DomainException;
import com.pingche.web.officialsession.OfficialSessionLogoutPreview;
import com.pingche.web.officialsession.OfficialSessionLogoutResult;
import com.pingche.web.officialsession.OfficialSessionLogoutService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** An aggregate never authorizes account-wide logout and never retries an official write. */
@Service
public class AdminUserLogoutService {
    private static final Logger LOG = LoggerFactory.getLogger(AdminUserLogoutService.class);
    private static final String SCOPE = "ALL_ACCOUNTS";
    private final JdbcTemplate jdbc;
    private final AdminService admin;
    private final OfficialSessionLogoutService official;
    private final ObjectMapper json;
    private final Clock clock;
    private final TransactionTemplate tx;

    public AdminUserLogoutService(JdbcTemplate jdbc, AdminService admin, OfficialSessionLogoutService official,
            ObjectMapper json, Clock clock, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.admin = admin;
        this.official = official;
        this.json = json;
        this.clock = clock;
        this.tx = new TransactionTemplate(manager);
    }

    public Preview preview(UUID owner, UUID user) {
        admin.getUser(user);
        Instant expires = clock.instant().plusSeconds(120);
        List<ScopeBinding> bindings = scope(user);
        Map<UUID, List<ScopeBinding>> grouped = new LinkedHashMap<>();
        for (ScopeBinding binding : bindings) grouped.computeIfAbsent(binding.account, ignored -> new ArrayList<>()).add(binding);
        List<AccountPreview> accounts = new ArrayList<>();
        for (var entry : grouped.entrySet()) {
            List<String> sessions = entry.getValue().stream().map(ScopeBinding::session).distinct().sorted().toList();
            String label = entry.getValue().get(0).label;
            try {
                if (entry.getValue().stream().anyMatch(binding -> binding.currentRevision != null && !binding.revision.equals(binding.currentRevision)))
                    throw conflict("USER_LOGOUT_BINDING_STALE");
                var selected = official.preview(owner, entry.getKey(), "SELECTED", sessions);
                if (selected.expiresAt().isBefore(expires)) expires = selected.expiresAt();
                // A duplicate upstream row may produce multiple exclusions for the same selected ID.
                Map<String, OfficialSessionLogoutPreview.Exclusion> excluded = new LinkedHashMap<>();
                for (var item : selected.excluded()) excluded.putIfAbsent(item.session().sessionId(), item);
                boolean completeRows = selected.targets().stream().allMatch(target -> target.rowId() != null && !target.rowId().isBlank());
                boolean ready = completeRows && !selected.targets().isEmpty();
                accounts.add(new AccountPreview(entry.getKey(), label, selected.planId(), ready ? "READY" : "NOT_ATTEMPTED", sessions,
                        selected.targets(), List.copyOf(excluded.values()), completeRows ? null : "USER_LOGOUT_SNAPSHOT_INCOMPLETE"));
            } catch (DomainException failure) {
                accounts.add(unavailable(entry.getKey(), label, sessions, safeCode(failure.getCode())));
            } catch (RuntimeException failure) {
                accounts.add(unavailable(entry.getKey(), label, sessions, "USER_LOGOUT_PREVIEW_UNAVAILABLE"));
            }
        }
        Preview preview = new Preview(UUID.randomUUID(), user, SCOPE, expires, List.copyOf(accounts),
                List.of("CURRENT_ACTIVE_BINDINGS_ONLY", "PRODUCT_LOGIN_SESSIONS_INCLUDED", "OFFICIAL_PROPAGATION_DELAY"));
        tx.executeWithoutResult(ignored -> {
            jdbc.update("insert into admin_user_logout_plans(id,owner_id,user_id,status,scope_fingerprint,preview_json,system_status,created_at,expires_at) values(?,?,?,'PREVIEW',?,?,'NOT_ATTEMPTED',?,?)",
                    preview.planId(), owner, user, fingerprint(bindings), json.writeValueAsString(preview), at(clock.instant()), at(preview.expiresAt()));
            for (AccountPreview account : preview.accounts())
                jdbc.update("insert into admin_user_logout_accounts(plan_id,official_account_id,official_plan_id,dispatch_status,error_code) values(?,?,?,?,?)",
                        preview.planId(), account.officialAccountId(), account.planId(), "READY".equals(account.status()) ? "PENDING" : "NOT_ATTEMPTED", account.errorCode());
        });
        LOG.info("User logout audit event=PREVIEW actorId={} userId={} planId={} accounts={}", owner, user, preview.planId(), accounts.size());
        return preview;
    }

    // Do not wrap dispatch in an outer transaction: official.confirm commits its journal
    // before starting its worker. Product revocation and the one-use claim commit first.
    public Result confirm(UUID owner, UUID user, UUID id, boolean acknowledge) {
        if (!acknowledge) throw conflict("USER_LOGOUT_ACK_REQUIRED");
        Plan plan = tx.execute(ignored -> {
            Plan current = owned(owner, user, id, true);
            if (!"PREVIEW".equals(current.status)) throw conflict("USER_LOGOUT_PLAN_CONSUMED");
            if (!current.preview.expiresAt().isAfter(clock.instant())) throw conflict("USER_LOGOUT_PLAN_EXPIRED");
            for (UUID account : current.preview.accounts().stream().map(AccountPreview::officialAccountId).sorted().toList()) {
                if (jdbc.query("select id from official_accounts where id=? for update", (rs, n) -> rs.getObject(1, UUID.class), account).isEmpty())
                    throw conflict("USER_LOGOUT_CONTEXT_CHANGED");
            }
            if (!current.fingerprint.equals(fingerprint(scope(user)))) throw conflict("USER_LOGOUT_CONTEXT_CHANGED");
            admin.forceLogout(user);
            jdbc.update("update admin_user_logout_plans set status='DISPATCHING',system_status='REVOKED',started_at=? where id=?", at(clock.instant()), id);
            return current;
        });
        LOG.info("User logout audit event=CONFIRMED actorId={} userId={} planId={} systemStatus=REVOKED", owner, user, id);
        for (AccountPreview account : plan.preview.accounts()) {
            if (!"READY".equals(account.status())) continue;
            dispatch(id, account.officialAccountId(), "DISPATCHING", null);
            try {
                official.confirm(owner, account.officialAccountId(), account.planId(), true);
                dispatch(id, account.officialAccountId(), "SUBMITTED", null);
            } catch (DomainException failure) {
                dispatch(id, account.officialAccountId(), "NOT_ATTEMPTED", safeCode(failure.getCode()));
            } catch (RuntimeException failure) {
                // A crash after the official service's journal committed cannot prove non-dispatch.
                dispatch(id, account.officialAccountId(), "UNKNOWN", "USER_LOGOUT_OUTCOME_UNKNOWN");
            }
        }
        jdbc.update("update admin_user_logout_plans set status='SUBMITTED' where id=?", id);
        LOG.info("User logout audit event=SUBMITTED actorId={} userId={} planId={}", owner, user, id);
        return result(owner, user, id);
    }

    public Result result(UUID owner, UUID user, UUID id) {
        Plan plan = owned(owner, user, id, false);
        if ("PREVIEW".equals(plan.status)) throw conflict("USER_LOGOUT_PLAN_CONSUMED");
        List<Observation> observed = plan.preview.accounts().stream().map(account -> accountResult(owner, plan, account)).toList();
        List<AccountResult> accounts = observed.stream().map(Observation::result).toList();
        String status;
        if (observed.stream().anyMatch(Observation::submissionPending)) status = "EXECUTING";
        else if (accounts.stream().anyMatch(account -> "EXECUTING".equals(account.status()))) status = "EXECUTING";
        else if (accounts.stream().anyMatch(account -> "UNKNOWN".equals(account.status()))) status = "UNKNOWN";
        else if ("REVOKED".equals(plan.systemStatus) && accounts.stream().allMatch(account -> "ACCEPTED".equals(account.status()))) status = "ACCEPTED";
        else status = "REVOKED".equals(plan.systemStatus) ? "PARTIAL" : "FAILED";
        return new Result(id, user, SCOPE, status, plan.systemStatus, accounts,
                "Product login revocation and official outcomes are separate; only previewed active bindings were selected. No automatic official retry was performed.");
    }

    public CurrentResponse current(UUID owner, UUID user) {
        // No provider requests, writes, or retries. Keep unresolved operations ahead of history.
        List<UUID> ids = jdbc.query("select id from admin_user_logout_plans where owner_id=? and user_id=? and status<>'PREVIEW' order by started_at desc,id desc",
                (rs, n) -> rs.getObject(1, UUID.class), owner, user);
        Result latest = null;
        for (UUID id : ids) {
            Result observed = result(owner, user, id);
            if (latest == null) latest = observed;
            if (Set.of("EXECUTING", "UNKNOWN").contains(observed.status())) return new CurrentResponse(observed);
        }
        return new CurrentResponse(latest);
    }

    private Observation accountResult(UUID owner, Plan plan, AccountPreview account) {
        Dispatch state = jdbc.queryForObject("select dispatch_status,error_code from admin_user_logout_accounts where plan_id=? and official_account_id=?",
                (rs, n) -> new Dispatch(rs.getString(1), rs.getString(2)), plan.preview.planId(), account.officialAccountId());
        if (!"READY".equals(account.status()))
            return new Observation(projected(account, "NOT_ATTEMPTED", false, List.of(), state.error, "NOT_ATTEMPTED"), false);
        try {
            OfficialSessionLogoutResult result = official.result(owner, account.officialAccountId(), account.planId());
            String status = "ACCEPTED".equals(result.status()) && !account.excluded().isEmpty() ? "PARTIAL" : result.status();
            return new Observation(projected(account, status, result.requiresReauthorization(), result.results(), null,
                    "EXECUTING".equals(result.status()) || "FAILED".equals(result.status()) ? "NOT_ATTEMPTED" : "UNKNOWN"), false);
        } catch (DomainException failure) {
            if ("OFFICIAL_LOGOUT_PLAN_CONSUMED".equals(failure.getCode())) {
                List<ChildState> children = jdbc.query("select status,expires_at from official_session_logout_plans where id=? and owner_id=? and official_account_id=?",
                        (rs, n) -> new ChildState(rs.getString(1), rs.getTimestamp(2).toInstant()), account.planId(), owner, account.officialAccountId());
                if (!children.isEmpty() && Set.of("PREVIEW", "INVALIDATED").contains(children.get(0).status)) {
                    ChildState child = children.get(0);
                    // The child's expiry is the same durable gate checked by official.confirm.
                    // Once expired, a paused parent cannot turn this unsent item into a late POST.
                    boolean pending = "DISPATCHING".equals(plan.status) && Set.of("PENDING", "DISPATCHING").contains(state.status)
                            && "PREVIEW".equals(child.status) && child.expiresAt.isAfter(clock.instant());
                    return new Observation(projected(account, "NOT_ATTEMPTED", false, List.of(), state.error, "NOT_ATTEMPTED"), pending);
                }
            }
            return unavailableResult(account);
        } catch (RuntimeException failure) {
            return unavailableResult(account);
        }
    }

    private Observation unavailableResult(AccountPreview account) {
        return new Observation(projected(account, "UNKNOWN", false, List.of(), "USER_LOGOUT_RESULT_UNAVAILABLE", "UNKNOWN"), false);
    }

    private AccountResult projected(AccountPreview account, String status, boolean reauthorization,
            List<OfficialSessionLogoutResult.Item> actual, String error, String missingStatus) {
        Map<String, OfficialSessionLogoutResult.Item> items = new LinkedHashMap<>();
        String wholeAccountError = error;
        for (var item : actual) {
            if (item.sessionId() == null) wholeAccountError = item.errorCode();
            else if (account.sessionIds().contains(item.sessionId())) items.put(item.sessionId(), item);
        }
        for (var exclusion : account.excluded()) items.put(exclusion.session().sessionId(),
                new OfficialSessionLogoutResult.Item(exclusion.session().sessionId(), "NOT_ATTEMPTED", exclusion.reason()));
        List<OfficialSessionLogoutResult.Item> results = new ArrayList<>();
        for (String session : account.sessionIds()) results.add(items.getOrDefault(session,
                new OfficialSessionLogoutResult.Item(session, missingStatus, wholeAccountError)));
        if ("ACCEPTED".equals(status) && results.stream().anyMatch(item -> !"ACCEPTED".equals(item.status()))) status = "UNKNOWN";
        return new AccountResult(account.officialAccountId(), account.displayName(), account.planId(), status,
                reauthorization, List.copyOf(results), error);
    }

    private List<ScopeBinding> scope(UUID user) {
        return jdbc.query("""
                select b.id,b.official_account_id,b.session_id,b.connection_revision,c.revision,a.label
                from session_bindings b join official_accounts a on a.id=b.official_account_id
                left join official_account_connections c on c.official_account_id=b.official_account_id
                where b.user_id=? and b.status='ACTIVE' order by b.official_account_id,b.id
                """, (rs, n) -> new ScopeBinding(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3),
                rs.getObject(4, UUID.class), rs.getObject(5, UUID.class), rs.getString(6)), user);
    }

    private String fingerprint(List<ScopeBinding> bindings) {
        List<List<String>> identities = bindings.stream().map(binding -> List.of(binding.id.toString(), binding.account.toString(),
                binding.session, binding.revision.toString(), binding.currentRevision == null ? "" : binding.currentRevision.toString())).toList();
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsString(identities).getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private Plan owned(UUID owner, UUID user, UUID id, boolean lock) {
        List<Plan> rows = jdbc.query("select * from admin_user_logout_plans where id=?" + (lock ? " for update" : ""), this::plan, id);
        if (rows.isEmpty() || !rows.get(0).owner.equals(owner) || !rows.get(0).preview.userId().equals(user))
            throw new DomainException("USER_LOGOUT_PLAN_FORBIDDEN", "User logout plan is unavailable", HttpStatus.FORBIDDEN);
        return rows.get(0);
    }
    private Plan plan(ResultSet rs, int row) throws SQLException {
        return new Plan(rs.getObject("owner_id", UUID.class), rs.getString("status"), rs.getString("scope_fingerprint"),
                json.readValue(rs.getString("preview_json"), Preview.class), rs.getString("system_status"),
                rs.getTimestamp("started_at") == null ? null : rs.getTimestamp("started_at").toInstant());
    }
    private void dispatch(UUID plan, UUID account, String status, String error) {
        jdbc.update("update admin_user_logout_accounts set dispatch_status=?,error_code=? where plan_id=? and official_account_id=?", status, error, plan, account);
    }
    private AccountPreview unavailable(UUID account, String label, List<String> sessions, String code) {
        return new AccountPreview(account, label, null, "NOT_ATTEMPTED", sessions, List.of(), List.of(), code);
    }
    private String safeCode(String code) { return code != null && code.matches("(?:OFFICIAL|USER_LOGOUT)_[A-Z0-9_]{1,75}") ? code : "USER_LOGOUT_UNAVAILABLE"; }
    private DomainException conflict(String code) { return new DomainException(code, "User logout could not be safely completed; refresh the preview or read the existing result", HttpStatus.CONFLICT); }
    private static OffsetDateTime at(Instant value) { return value.atOffset(ZoneOffset.UTC); }
    private record ScopeBinding(UUID id, UUID account, String session, UUID revision, UUID currentRevision, String label) { }
    private record Plan(UUID owner, String status, String fingerprint, Preview preview, String systemStatus, Instant startedAt) { }
    private record Dispatch(String status, String error) { }
    private record ChildState(String status, Instant expiresAt) { }
    private record Observation(AccountResult result, boolean submissionPending) { }

    public record Preview(UUID planId, UUID userId, String scope, Instant expiresAt, List<AccountPreview> accounts, List<String> warnings) { }
    public record AccountPreview(UUID officialAccountId, String displayName, UUID planId, String status, List<String> sessionIds,
            List<OfficialSessionLogoutPreview.Target> targets, List<OfficialSessionLogoutPreview.Exclusion> excluded, String errorCode) { }
    public record Result(UUID planId, UUID userId, String scope, String status, String systemStatus, List<AccountResult> accounts, String message) { }
    public record AccountResult(UUID officialAccountId, String displayName, UUID planId, String status, boolean requiresReauthorization,
            List<OfficialSessionLogoutResult.Item> results, String errorCode) { }
    public record CurrentResponse(Result operation) { }
}
