package com.pingche.web.mail;

import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** Real owned-device and queue rows for tests that exercise mail/TOTP after an exclusive grant. */
public final class AssistanceGrantFixture {
    private AssistanceGrantFixture() { }
    public static UUID grant(JdbcTemplate jdbc, UUID userId, Instant now) {
        UUID team = jdbc.queryForObject("select team_id from users where id=?", UUID.class, userId);
        UUID account = jdbc.queryForObject("select official_account_id from teams where id=?", UUID.class, team);
        long version = jdbc.queryForObject("select version from teams where id=?", Long.class, team);
        var connections = jdbc.queryForList("select revision,requested_account_id,bound_user_id from official_account_connections where official_account_id=?", account);
        UUID revision;
        String accountKey, userKey;
        if (connections.isEmpty()) {
            revision = UUID.randomUUID(); accountKey = "fixture-"+account; userKey = "fixture-user";
            jdbc.update("insert into official_account_connections(official_account_id,requested_account_id,bound_account_id,bound_user_id,authorization_cipher,status,revision,imported_at,version) values (?,?,?,?,?,'CONNECTED',?,?,0)",
                    account,accountKey,accountKey,userKey,"synthetic-not-used",revision,now);
        } else {
            revision = (UUID) connections.get(0).get("revision");
            accountKey = (String) connections.get(0).get("requested_account_id"); userKey = (String) connections.get(0).get("bound_user_id");
        }
        UUID device = UUID.randomUUID(), attempt = UUID.randomUUID();
        jdbc.update("insert into meter_devices(id,user_id,token_hash,status,created_at,expires_at,version) values (?,?,?,'ACTIVE',?,?,0)",
                device,userId,UUID.randomUUID().toString().replace("-", "").repeat(2),now,now.plusSeconds(10800));
        var generations = jdbc.queryForList("select generation from session_binding_accounts where official_account_id=?", Long.class, account);
        long generation = generations.isEmpty() ? 1 : generations.get(0)+1;
        jdbc.update("insert into session_binding_attempts(id,user_id,device_id,enrollment_id,team_id,team_version,official_account_id,connection_revision,status,created_at,expires_at,before_session_ids,official_account_key,official_user_key,claim_generation) values (?,?,?,?,?,?,?,?,'READY_FOR_LOGIN',?,?,'[]',?,?,?)",
                attempt,userId,device,device,team,version,account,revision,now,now.plusSeconds(7200),accountKey,userKey,generation);
        if (generations.isEmpty()) jdbc.update("insert into session_binding_accounts(official_account_id,active_attempt_id,generation,updated_at) values (?,?,?,?)",account,attempt,generation,now);
        else jdbc.update("update session_binding_accounts set active_attempt_id=?,generation=?,quarantined=false,recovery_id=null,settle_until=null where official_account_id=?",attempt,generation,account);
        return device;
    }
}
