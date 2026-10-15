package com.pingche.web.officialsession;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "official_account_connections")
public class OfficialAccountConnection {
    @Id @Column(name = "official_account_id") UUID officialAccountId;
    @Column(name = "requested_account_id", nullable = false, length = 160) String requestedAccountId;
    @Column(name = "proxy_node_id") UUID proxyNodeId;
    @Column(name = "authorization_cipher", nullable = false, columnDefinition = "text") String authorizationCipher;
    @Column(name = "cookie_cipher", columnDefinition = "text") String cookieCipher;
    @Column(name = "snapshot_cipher", columnDefinition = "text") String snapshotCipher;
    @Column(name = "bound_account_id", length = 160) String boundAccountId;
    @Column(name = "bound_user_id", length = 320) String boundUserId;
    @Column(nullable = false, length = 32) String status;
    @Column(nullable = false) UUID revision;
    @Column(name = "refresh_attempt") UUID refreshAttempt;
    @Column(name = "refresh_lease_until") Instant refreshLeaseUntil;
    @Column(name = "imported_at", nullable = false) Instant importedAt;
    @Column(name = "last_attempt_at") Instant lastAttemptAt;
    @Column(name = "last_success_at") Instant lastSuccessAt;
    @Column(name = "last_error_code", length = 64) String lastErrorCode;
    @Version @Column(nullable = false) long version;

    protected OfficialAccountConnection() { }
    OfficialAccountConnection(UUID officialAccountId) { this.officialAccountId = officialAccountId; }
    String aad(String field) { return "official-session:v1:" + officialAccountId + ":" + field; }
    @Override public String toString() { return "OfficialAccountConnection[redacted]"; }
}
