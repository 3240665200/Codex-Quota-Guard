package com.pingche.web.codex;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pingche.web.auth.DomainException;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CodexCredentialCipherTest {

    private static final String SECRET =
            "test-encryption-key-that-is-longer-than-thirty-two-bytes";

    @Test
    void encryptsWithRandomIvAndAuthenticatesTheAccountId() {
        CodexCredentialCipher cipher = new CodexCredentialCipher(SECRET);
        UUID accountId = UUID.randomUUID();
        byte[] plaintext = "{\"access_token\":\"private\"}"
                .getBytes(StandardCharsets.UTF_8);

        CodexCredentialCipher.EncryptedCredential first = cipher.encrypt(accountId, plaintext);
        CodexCredentialCipher.EncryptedCredential second = cipher.encrypt(accountId, plaintext);

        assertThat(first.ciphertext()).isNotEqualTo(second.ciphertext());
        assertThat(first.iv()).isNotEqualTo(second.iv());
        assertThat(cipher.decrypt(accountId, first.ciphertext(), first.iv()))
                .isEqualTo(plaintext);
        assertThatThrownBy(() -> cipher.decrypt(UUID.randomUUID(), first.ciphertext(), first.iv()))
                .isInstanceOf(IllegalStateException.class);
        CodexCredentialCipher wrongKey = new CodexCredentialCipher(SECRET + "-different");
        assertThatThrownBy(() -> wrongKey.decrypt(accountId, first.ciphertext(), first.iv()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void newCredentialWritesRequireASeparateServerKey() {
        assertThatThrownBy(() -> new CodexCredentialCipher("", SECRET).requireDedicatedKey())
                .isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> new CodexCredentialCipher(SECRET, SECRET).requireDedicatedKey())
                .isInstanceOf(DomainException.class);
        new CodexCredentialCipher(SECRET, SECRET + "-jwt").requireDedicatedKey();
        assertThatThrownBy(() -> new CodexCredentialCipher("short"))
                .isInstanceOf(IllegalStateException.class);
    }
}
