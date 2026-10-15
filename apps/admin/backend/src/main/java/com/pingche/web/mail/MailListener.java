package com.pingche.web.mail;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "mail_login_listeners")
public class MailListener {
    @Id UUID id;
    @Column(name = "official_account_id", nullable = false) UUID officialAccountId;
    @Column(name = "owner_user_id", nullable = false) UUID ownerUserId;
    @Column(name = "team_id", nullable = false) UUID teamId;
    @Column(name = "config_revision", nullable = false) UUID configRevision;
    @Column(name = "active_account_id", unique = true) UUID activeAccountId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16) State state;
    @Column(name = "started_at", nullable = false) Instant startedAt;
    @Column(name = "expires_at", nullable = false) Instant expiresAt;
    @Column(name = "listening_at") Instant listeningAt;
    @Column(name = "received_at") Instant receivedAt;
    @Column(name = "result_expires_at") Instant resultExpiresAt;
    @Column(name = "code_cipher", columnDefinition = "text") String codeCipher;
    @Column(name = "error_code", length = 64) String errorCode;
    @Column(name = "uid_validity") Long uidValidity;
    @Column(name = "next_uid") Long nextUid;
    @Version @Column(nullable = false) long version;

    protected MailListener() { }
    MailListener(UUID userId, UUID teamId, UUID accountId, UUID revision, Instant now) {
        id = UUID.randomUUID(); ownerUserId = userId; this.teamId = teamId;
        officialAccountId = accountId; activeAccountId = accountId; configRevision = revision;
        state = State.CONNECTING; startedAt = now; expiresAt = now.plusSeconds(600);
    }
    boolean active() { return state == State.CONNECTING || state == State.LISTENING; }
    void stop(State next, String error) {
        state = next; activeAccountId = null; errorCode = error; codeCipher = null; resultExpiresAt = null; receivedAt = null;
    }
    String aad() { return "mail-result:" + id + ":" + ownerUserId + ":" + officialAccountId; }
    public enum State { CONNECTING, LISTENING, RECEIVED, CANCELLED, TIMED_OUT, FAILED }
    @Override public String toString() { return "MailListener[id=" + id + ", state=" + state + "]"; }
}
