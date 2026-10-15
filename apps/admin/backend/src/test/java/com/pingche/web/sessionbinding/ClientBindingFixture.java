package com.pingche.web.sessionbinding;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** Real persisted prerequisites for quota/analytics tests; never replaces the access service or filter. */
public final class ClientBindingFixture {
    private ClientBindingFixture() { }

    public static RequestPostProcessor forUser(JdbcTemplate jdbc, UUID userId) {
        return request -> {
            request.addHeader(ClientAccessService.DEVICE_HEADER, bind(jdbc, userId, null).toString());
            return request;
        };
    }

    public static UUID bind(JdbcTemplate jdbc, UUID userId, UUID requestedAccount) {
        Instant now = Instant.now();
        UUID teamId = jdbc.queryForObject("select team_id from users where id=?", UUID.class, userId);
        if (teamId == null) {
            teamId = UUID.randomUUID();
            jdbc.update("insert into teams (id,name,description,status,five_hour_quota_percent,weekly_quota_percent,created_at,updated_at,version) values (?,?,?,'ACTIVE',100,100,?,?,0)", teamId, "Gate fixture " + teamId, "Synthetic test context", now, now);
            jdbc.update("update users set team_id=? where id=?", teamId, userId);
        }
        UUID accountId = jdbc.queryForObject("select official_account_id from teams where id=?", UUID.class, teamId);
        if (requestedAccount != null) accountId = requestedAccount;
        if (accountId == null) {
            accountId = UUID.randomUUID();
            jdbc.update("insert into official_accounts (id,label,estimated_total_tokens,created_at,updated_at,version) values (?,?,0,?,?,0)", accountId, "Gate fixture " + accountId, now, now);
        }
        // Supplying login prerequisites does not change the quota limits under test.
        jdbc.update("update teams set official_account_id=? where id=?", accountId, teamId);
        List<UUID> revisions = jdbc.query("select revision from official_account_connections where official_account_id=?", (rs, row) -> rs.getObject("revision", UUID.class), accountId);
        UUID revision = revisions.isEmpty() ? UUID.randomUUID() : revisions.get(0);
        if (revisions.isEmpty()) {
            jdbc.update("insert into official_account_connections (official_account_id,requested_account_id,authorization_cipher,bound_account_id,bound_user_id,status,revision,imported_at,version) values (?,?,?,?,?,'CONNECTED',?,?,0)", accountId, "fixture-" + accountId, "synthetic-no-secret", "fixture-" + accountId, "synthetic-user", revision, now);
        }
        long version = jdbc.queryForObject("select version from teams where id=?", Long.class, teamId);
        UUID deviceId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        UUID bindingId = UUID.randomUUID();
        String sessionId = "fixture-" + bindingId;
        jdbc.update("insert into meter_devices (id,user_id,token_hash,status,created_at,expires_at,version) values (?,?,?,'ACTIVE',?,?,0)", deviceId, userId, UUID.randomUUID().toString().replace("-", "").repeat(2), now, now.plusSeconds(3600));
        jdbc.update("insert into session_binding_attempts (id,user_id,device_id,enrollment_id,team_id,team_version,official_account_id,connection_revision,status,created_at,expires_at) values (?,?,?,?,?,?,?,?,'BOUND',?,?)", attemptId, userId, deviceId, deviceId, teamId, version, accountId, revision, now, now.plusSeconds(1800));
        jdbc.update("insert into session_bindings (id,attempt_id,user_id,device_id,enrollment_id,team_id,team_version,official_account_id,connection_revision,session_id,active_device_id,active_session_id,status,match_method,verification_level,created_at) values (?,?,?,?,?,?,?,?,?,?,?,?,'ACTIVE','SERIAL_LOGIN_DIFF','USER_CONFIRMED_INFERENCE',?)", bindingId, attemptId, userId, deviceId, deviceId, teamId, version, accountId, revision, sessionId, deviceId, sessionId, now);
        return deviceId;
    }

    public static void clearBindings(JdbcTemplate jdbc) {
        jdbc.update("delete from session_bindings");
        jdbc.update("delete from session_binding_attempts");
        jdbc.update("delete from session_binding_accounts");
        jdbc.update("delete from official_session_logout_plans");
    }
}
