package com.pingche.web.officialsession;

import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** An inert node for tests whose official transport is mocked; never opens any connection. */
final class SyntheticProxyNodeFixture {
    private SyntheticProxyNodeFixture() { }

    static UUID insert(JdbcTemplate jdbc) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into managed_proxy_nodes(id,label,protocol,host,port,config_cipher,has_credentials,enabled,created_at,updated_at,version) values (?,?,?,?,?,?,?,?,?,?,0)",
                id, "Synthetic test node", "HTTP", "proxy.example.test", 8080, "synthetic-unused-cipher", false, true,
                Instant.EPOCH, Instant.EPOCH);
        return id;
    }
}
