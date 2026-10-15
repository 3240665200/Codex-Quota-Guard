package com.pingche.web.officialsession;

import com.pingche.web.auth.DomainException;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Call only after locking the official_accounts row in the current transaction. */
@Component
public class OfficialSessionLogoutFence {
    private final JdbcTemplate jdbc;

    public OfficialSessionLogoutFence(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void assertAvailable(UUID accountId) {
        // A timed-out request can still have changed the official service. Expiry cannot release this fence.
        if (jdbc.queryForObject("select count(*) from official_session_logout_plans where official_account_id=? "
                + "and status in ('EXECUTING','UNKNOWN')", Integer.class, accountId) > 0)
            throw new DomainException("OFFICIAL_SESSION_LOGOUT_BUSY",
                    "An official session logout must finish or be reviewed before this account can change", HttpStatus.CONFLICT);
    }
}
