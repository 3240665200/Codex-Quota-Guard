package com.pingche.web.restriction;

import com.pingche.web.auth.DomainException;
import com.pingche.web.officialsession.OfficialConnectionRefreshResponse;
import com.pingche.web.officialsession.OfficialConnectionService;
import com.pingche.web.sessionbinding.SessionBindingService;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Reconciles local bindings against a fresh, identity-verified official session list. Never logs out a session. */
@Service
public class StaleBindingCleanupService {
    private record Candidate(UUID id, String session) { }
    private record Work(UUID account, UUID lease, UUID revision, String owner, String accountId,
            long policyVersion, Instant started, List<Candidate> candidates) { }
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final OfficialConnectionService connections;
    private final SessionBindingService bindings;
    private final TransactionTemplate tx;

    public StaleBindingCleanupService(JdbcTemplate jdbc, Clock clock, OfficialConnectionService connections,
            SessionBindingService bindings, PlatformTransactionManager manager) {
        this.jdbc=jdbc; this.clock=clock; this.connections=connections; this.bindings=bindings;
        tx=new TransactionTemplate(manager);
    }

    public List<UUID> dueAccounts() {
        return jdbc.query("select a.id from official_accounts a join official_account_connections c on c.official_account_id=a.id "
                + "left join stale_binding_checks s on s.official_account_id=a.id "
                + "where exists (select 1 from restriction_policy where id=1 and stale_binding_cleanup_enabled=true) "
                + "and c.bound_account_id is not null and c.bound_user_id is not null "
                + "and exists (select 1 from session_bindings b where b.official_account_id=a.id and b.status='ACTIVE' and b.connection_revision=c.revision) "
                + "and (s.next_check_at is null or s.next_check_at<=?) and (s.lease_until is null or s.lease_until<=?) "
                + "order by a.id limit 32", (rs,n)->rs.getObject(1,UUID.class),at(clock.instant()),at(clock.instant()));
    }

    public void checkAccount(UUID account) {
        Work work=reserve(account);
        if (work==null) return;
        try {
            OfficialConnectionRefreshResponse fresh=connections.refresh(account);
            Set<String> live=completeSessionIds(work,fresh);
            if (live==null) { finish(work,0,"STALE_BINDING_SNAPSHOT_INCOMPLETE",60); return; }
            tx.executeWithoutResult(ignored -> {
                if (!lockAccount(account) || !owns(work)) return;
                boolean current=jdbc.queryForObject("select count(*) from official_account_connections c, restriction_policy p "
                        + "where c.official_account_id=? and c.revision=? and c.bound_user_id=? and c.bound_account_id=? "
                        + "and p.id=1 and p.stale_binding_cleanup_enabled=true and p.version=?",Integer.class,
                        account,work.revision,work.owner,work.accountId,work.policyVersion)==1;
                if (!current) { finish(work,0,"STALE_BINDING_CONTEXT_CHANGED",60); return; }
                int removed=0;
                for (Candidate candidate:work.candidates) {
                    if (live.contains(candidate.session)) continue;
                    // Recheck the exact candidate, never a replacement binding created while fetching.
                    if (jdbc.queryForObject("select count(*) from session_bindings where id=? and official_account_id=? "
                            + "and connection_revision=? and session_id=? and status='ACTIVE'",Integer.class,
                            candidate.id,account,work.revision,candidate.session)!=1) continue;
                    bindings.releaseForAdministrator(candidate.id,account);
                    removed++;
                }
                finish(work,removed,null,60);
            });
        } catch (RuntimeException failure) {
            String code=failure instanceof DomainException domain ? domain.getCode() : "STALE_BINDING_CHECK_FAILED";
            // Return only known, non-sensitive failure categories to the management page.
            code=Set.of("OFFICIAL_RATE_LIMITED","OFFICIAL_REFRESH_RATE_LIMITED","OFFICIAL_AUTH_REQUIRED",
                    "OFFICIAL_REFRESH_BUSY","OFFICIAL_CONNECTION_CHANGED","OFFICIAL_ACCOUNT_MISMATCH",
                    "OFFICIAL_LOGOUT_BUSY","OFFICIAL_SESSION_LOGOUT_BUSY").contains(code) ? code : "STALE_BINDING_CHECK_FAILED";
            finish(work,0,code,"OFFICIAL_RATE_LIMITED".equals(code)?900:60);
        }
    }

    private Work reserve(UUID account) {
        return tx.execute(ignored -> {
            if (!lockAccount(account)) return null;
            var policy=jdbc.query("select version from restriction_policy where id=1 and stale_binding_cleanup_enabled=true",
                    (rs,n)->rs.getLong(1));
            if (policy.isEmpty()) return null;
            Instant now=clock.instant();
            if (jdbc.queryForObject("select count(*) from stale_binding_checks where official_account_id=?",Integer.class,account)==0)
                jdbc.update("insert into stale_binding_checks(official_account_id) values(?)",account);
            var ready=jdbc.query("select official_account_id from stale_binding_checks where official_account_id=? "
                    + "and (next_check_at is null or next_check_at<=?) and (lease_until is null or lease_until<=?) for update",
                    (rs,n)->rs.getObject(1,UUID.class),account,at(now),at(now));
            if (ready.isEmpty()) return null;
            var contexts=jdbc.query("select revision,bound_user_id,bound_account_id from official_account_connections "
                    + "where official_account_id=? and bound_user_id is not null and bound_account_id is not null",
                    (rs,n)->new Work(account,UUID.randomUUID(),rs.getObject("revision",UUID.class),rs.getString("bound_user_id"),
                            rs.getString("bound_account_id"),policy.get(0),now,List.of()),account);
            if (contexts.isEmpty()) return null;
            Work context=contexts.get(0);
            var candidates=jdbc.query("select id,session_id from session_bindings where official_account_id=? "
                    + "and connection_revision=? and status='ACTIVE' order by id",(rs,n)->new Candidate(rs.getObject(1,UUID.class),rs.getString(2)),
                    account,context.revision);
            if (candidates.isEmpty()) return null;
            jdbc.update("update stale_binding_checks set lease_id=?,lease_until=? where official_account_id=?",
                    context.lease,at(now.plusSeconds(120)),account);
            return new Work(account,context.lease,context.revision,context.owner,context.accountId,context.policyVersion,now,candidates);
        });
    }

    private Set<String> completeSessionIds(Work work, OfficialConnectionRefreshResponse fresh) {
        if (fresh==null || fresh.account()==null || fresh.sessions()==null || !fresh.sessions().showSessionManager()
                || fresh.fetchedAt()==null || fresh.fetchedAt().isBefore(work.started)
                || !work.owner.equals(fresh.account().userId()) || !work.accountId.equals(fresh.account().accountId())) return null;
        Set<String> result=new HashSet<>();
        for (var device:fresh.sessions().devices()) {
            // A row with no stable identity might be the supposedly missing session.
            if (device==null || device.sessionId()==null || device.sessionId().isBlank() || !result.add(device.sessionId())) return null;
        }
        return result;
    }

    private boolean lockAccount(UUID account) {
        return !jdbc.query("select id from official_accounts where id=? for update",(rs,n)->rs.getObject(1,UUID.class),account).isEmpty();
    }
    private boolean owns(Work work) {
        return jdbc.queryForObject("select count(*) from stale_binding_checks where official_account_id=? and lease_id=? and lease_until>?",
                Integer.class,work.account,work.lease,at(clock.instant()))==1;
    }
    private void finish(Work work,int removed,String error,long delay) {
        jdbc.update("update stale_binding_checks set lease_id=null,lease_until=null,last_checked_at=?,next_check_at=?,"
                + "last_error_code=?,removed_count=removed_count+? where official_account_id=? and lease_id=?",
                at(clock.instant()),at(clock.instant().plusSeconds(delay)),error,removed,work.account,work.lease);
    }
    private static OffsetDateTime at(Instant instant) { return instant.atOffset(ZoneOffset.UTC); }
}
