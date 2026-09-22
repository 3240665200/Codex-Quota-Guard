package com.pingche.web.codex;

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
@Table(name = "codex_accounts")
public class CodexAccount {

    @Id
    private UUID id;

    @Column(nullable = false, unique = true, length = 320)
    private String email;

    @Column(name = "display_name", nullable = false, length = 120)
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status;

    @Column(name = "credential_ciphertext", nullable = false, columnDefinition = "text")
    private String credentialCiphertext;

    @Column(name = "credential_iv", nullable = false, length = 24)
    private String credentialIv;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(nullable = false)
    private long version;

    protected CodexAccount() {
    }

    public CodexAccount(
            UUID id,
            String email,
            String displayName,
            Status status,
            String credentialCiphertext,
            String credentialIv,
            Instant now
    ) {
        this.id = id;
        this.email = email;
        this.displayName = displayName;
        this.status = status;
        this.credentialCiphertext = credentialCiphertext;
        this.credentialIv = credentialIv;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public String getDisplayName() {
        return displayName;
    }

    public Status getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    String getCredentialCiphertext() {
        return credentialCiphertext;
    }

    String getCredentialIv() {
        return credentialIv;
    }

    public void updateProfile(String email, String displayName, Status status, Instant now) {
        this.email = email;
        this.displayName = displayName;
        this.status = status;
        this.updatedAt = now;
    }

    public void replaceCredential(String ciphertext, String iv, Instant now) {
        this.credentialCiphertext = ciphertext;
        this.credentialIv = iv;
        this.updatedAt = now;
    }

    public enum Status {
        ACTIVE,
        DISABLED
    }
}
