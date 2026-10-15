package com.pingche.web.officialsession;

import com.pingche.web.auth.DomainException;
import jakarta.validation.constraints.*;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class BrowserLoginService {
    private final JdbcTemplate jdbc;
    private final OfficialConnectionService connections;
    private final Clock clock;
    private final TransactionTemplate transaction;

    public BrowserLoginService(JdbcTemplate jdbc, OfficialConnectionService connections, Clock clock,
            PlatformTransactionManager manager) {
        this.jdbc = jdbc; this.connections = connections; this.clock = clock;
        transaction = new TransactionTemplate(manager);
    }

    public record StartRequest(@NotNull @PositiveOrZero Long version, UUID proxyNodeId) { }
    public record ClaimRequest(@NotNull UUID helperId) { }
    public record CompleteRequest(@NotNull UUID helperId, @NotBlank @Size(max=16384) String authorization,
            @Size(max=32768) String cookie, @NotBlank @Size(max=160) String requestedAccountId) {
        @Override public String toString() { return "BrowserLoginCompletion[redacted]"; }
    }
    public record Job(UUID id, UUID officialAccountId, String accountName, String state, Instant expiresAt,
            String errorCode, UUID proxyNodeId) { }
    private record Row(Job job, UUID owner, UUID helper, long version) { }

    public Job create(UUID owner, UUID account, StartRequest request) {
        expire();
        return tx(() -> {
            List<String> names = jdbc.query("select label from official_accounts where id=? for update", (rs,n) -> rs.getString(1), account);
            if (names.isEmpty()) throw fail("OFFICIAL_ACCOUNT_NOT_FOUND", HttpStatus.NOT_FOUND);
            var connection = connections.read(account);
            if (!connection.encryptionReady()) throw fail("OFFICIAL_ENCRYPTION_UNAVAILABLE", HttpStatus.SERVICE_UNAVAILABLE);
            if (request.version() == null || request.version() != connection.version())
                throw fail("OFFICIAL_CONNECTION_VERSION_CONFLICT", HttpStatus.CONFLICT);
            if (request.proxyNodeId() != null && jdbc.queryForObject(
                    "select count(*) from managed_proxy_nodes where id=? and enabled=true", Integer.class,request.proxyNodeId()) != 1)
                throw fail("OFFICIAL_PROXY_UNAVAILABLE",HttpStatus.BAD_REQUEST);
            if (jdbc.queryForObject("select count(*) from official_browser_login_jobs where official_account_id=? and state in ('WAITING_FOR_HELPER','WAITING_FOR_LOGIN','SAVING')", Integer.class, account) > 0)
                throw fail("BROWSER_LOGIN_BUSY", HttpStatus.CONFLICT);
            UUID id = UUID.randomUUID();
            jdbc.update("insert into official_browser_login_jobs(id,owner_id,official_account_id,account_name,proxy_node_id,connection_version,state,expires_at,created_at) values(?,?,?,?,?,?,'WAITING_FOR_HELPER',?,?)",
                    id, owner, account, names.get(0), request.proxyNodeId(), request.version(), at(clock.instant().plusSeconds(600)), at(clock.instant()));
            return row(owner,id,false).job();
        });
    }

    public List<Job> list(UUID owner) {
        expire();
        return jdbc.query("select * from official_browser_login_jobs where owner_id=? and state in ('WAITING_FOR_HELPER','WAITING_FOR_LOGIN','SAVING') order by created_at desc limit 30",
                (rs,n) -> map(rs).job(), owner);
    }
    public Job get(UUID owner, UUID id) { expire(); return row(owner,id,false).job(); }

    public Job claim(UUID owner, UUID id, UUID helper) {
        expire();
        return tx(() -> {
            Row row = row(owner,id,true);
            if (!row.job().expiresAt().isAfter(clock.instant())) throw fail("BROWSER_LOGIN_EXPIRED",HttpStatus.CONFLICT);
            if ("WAITING_FOR_LOGIN".equals(row.job().state()) && helper.equals(row.helper())) return row.job();
            if (!"WAITING_FOR_HELPER".equals(row.job().state())) throw fail("BROWSER_LOGIN_NOT_ACTIVE", HttpStatus.CONFLICT);
            jdbc.update("update official_browser_login_jobs set state='WAITING_FOR_LOGIN',helper_id=? where id=?", helper,id);
            return row(owner,id,false).job();
        });
    }

    public Job cancel(UUID owner, UUID id) {
        expire();
        return tx(() -> {
            Row row = row(owner,id,true);
            if ("SAVING".equals(row.job().state())) throw fail("BROWSER_LOGIN_SAVING", HttpStatus.CONFLICT);
            if (List.of("WAITING_FOR_HELPER","WAITING_FOR_LOGIN").contains(row.job().state()))
                jdbc.update("update official_browser_login_jobs set state='CANCELLED' where id=?",id);
            return row(owner,id,false).job();
        });
    }

    public Job complete(UUID owner, UUID id, CompleteRequest request) {
        new OfficialSessionCredentials(request.authorization(),request.cookie(),request.requestedAccountId()).validate();
        expire();
        Row work = tx(() -> {
            Row row = row(owner,id,true);
            if (!row.job().expiresAt().isAfter(clock.instant())) throw fail("BROWSER_LOGIN_EXPIRED",HttpStatus.CONFLICT);
            if (!"WAITING_FOR_LOGIN".equals(row.job().state()) || !request.helperId().equals(row.helper()))
                throw fail("BROWSER_LOGIN_NOT_ACTIVE", HttpStatus.CONFLICT);
            jdbc.update("update official_browser_login_jobs set state='SAVING',expires_at=? where id=?", at(clock.instant().plusSeconds(120)),id);
            return row;
        });
        String state = "COMPLETED", error = null;
        try {
            // Fence the credential write under the same job row lock used by expiry/cancellation.
            // A delayed helper must not write after a newer login has been allowed to start.
            tx(() -> {
                Row current = row(owner,id,true);
                if (!"SAVING".equals(current.job().state()) || !request.helperId().equals(current.helper())
                        || !current.job().expiresAt().isAfter(clock.instant()))
                    throw fail("BROWSER_LOGIN_EXPIRED",HttpStatus.CONFLICT);
                connections.save(work.job().officialAccountId(), new OfficialConnectionRequest(request.authorization(),request.cookie(),
                        request.requestedAccountId(),work.job().proxyNodeId(),work.version()));
                return null;
            });
            connections.refresh(work.job().officialAccountId());
        } catch (DomainException failure) {
            state="BROWSER_LOGIN_EXPIRED".equals(failure.getCode()) ? "EXPIRED" : "FAILED";
            error = failure.getCode().matches("(?:OFFICIAL|BROWSER_LOGIN)_[A-Z_]{1,50}") ? failure.getCode() : "OFFICIAL_UNAVAILABLE";
        } catch (RuntimeException ignored) { state="FAILED"; error="OFFICIAL_UNAVAILABLE"; }
        jdbc.update("update official_browser_login_jobs set state=?,error_code=? where id=? and state='SAVING'",state,error,id);
        return get(owner,id);
    }

    private Row row(UUID owner, UUID id, boolean lock) {
        List<Row> rows = jdbc.query("select * from official_browser_login_jobs where id=? and owner_id=?" + (lock?" for update":""),
                (rs,n) -> map(rs),id,owner);
        if (rows.isEmpty()) throw fail("BROWSER_LOGIN_NOT_FOUND",HttpStatus.NOT_FOUND);
        return rows.get(0);
    }
    private Row map(ResultSet rs) throws SQLException {
        return new Row(new Job(rs.getObject("id",UUID.class),rs.getObject("official_account_id",UUID.class),rs.getString("account_name"),
                rs.getString("state"),rs.getObject("expires_at",OffsetDateTime.class).toInstant(),rs.getString("error_code"),rs.getObject("proxy_node_id",UUID.class)),
                rs.getObject("owner_id",UUID.class),rs.getObject("helper_id",UUID.class),rs.getLong("connection_version"));
    }
    private void expire() {
        jdbc.update("update official_browser_login_jobs set state='EXPIRED',error_code='BROWSER_LOGIN_EXPIRED' where expires_at<=? and state in ('WAITING_FOR_HELPER','WAITING_FOR_LOGIN','SAVING')",at(clock.instant()));
    }
    private OffsetDateTime at(Instant value) { return value.atOffset(ZoneOffset.UTC); }
    private <T> T tx(Supplier<T> work) { return transaction.execute(status -> work.get()); }
    private DomainException fail(String code, HttpStatus status) { return new DomainException(code,"Official browser login could not be completed",status); }
}
