package com.pingche.web.sessionbinding;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Installation UUIDs identify enrollments; they are not hardware attestations. */
@Service
public class ClientAccessService {
    public static final String DEVICE_HEADER = "X-Quota-Guard-Device-ID";
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public ClientAccessService(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** Reads current persisted state on every request, without refreshing or changing a binding. */
    @Transactional(readOnly = true)
    public ClientAccessResponse access(UUID userId, String installationId) {
        if (installationId == null || installationId.isBlank()) return denied("CLIENT_DEVICE_REQUIRED");
        UUID deviceId;
        try {
            deviceId = UUID.fromString(installationId);
            if (!deviceId.toString().equalsIgnoreCase(installationId)) return denied("CLIENT_DEVICE_INVALID");
        } catch (IllegalArgumentException invalid) {
            return denied("CLIENT_DEVICE_INVALID");
        }

        List<UUID> bindings = jdbc.query("select id from session_bindings where user_id=? and device_id=? and status='ACTIVE'",
                (rs, row) -> rs.getObject("id", UUID.class), userId, deviceId);
        if (bindings.isEmpty()) return denied("SESSION_BINDING_REQUIRED");

        List<UUID> valid = jdbc.query("""
                select b.id from session_bindings b
                join users u on u.id=b.user_id and u.status='ACTIVE'
                join meter_devices d on d.id=b.enrollment_id and d.id=b.device_id
                    and d.user_id=u.id and d.status='ACTIVE' and d.expires_at>?
                join teams t on t.id=u.team_id and t.id=b.team_id and t.version=b.team_version
                    and t.status='ACTIVE' and t.official_account_id=b.official_account_id
                join official_account_connections c on c.official_account_id=b.official_account_id
                    and c.revision=b.connection_revision and c.status='CONNECTED'
                where b.user_id=? and b.device_id=? and b.status='ACTIVE'
                    and b.active_device_id=b.device_id and b.active_session_id=b.session_id
                    and not exists (select 1 from session_binding_accounts g
                        where g.official_account_id=b.official_account_id and g.quarantined=true)
                    and not exists (select 1 from official_session_logout_plans p
                        where p.official_account_id=b.official_account_id and p.status in ('EXECUTING','UNKNOWN'))
                    and (select count(*) from official_account_connections other
                        where other.requested_account_id=c.requested_account_id)=1
                """, (rs, row) -> rs.getObject("id", UUID.class),
                OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC), userId, deviceId);
        if (valid.size() != 1) return denied("CLIENT_BINDING_INVALID");
        return new ClientAccessResponse(true, valid.get(0), null);
    }

    private ClientAccessResponse denied(String reason) { return new ClientAccessResponse(false, null, reason); }
}
