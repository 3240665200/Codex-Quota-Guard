package com.pingche.web.codex;

import static org.assertj.core.api.Assertions.assertThat;

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
    }
}
