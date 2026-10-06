package com.pingche.web.codex;

import com.pingche.web.auth.DomainException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.http.HttpStatus;

@Component
public class CodexCredentialCipher {

    private static final int GCM_TAG_BITS = 128;
    private static final int IV_BYTES = 12;
    private static final byte[] KEY_CONTEXT =
            "codex-quota-guard/codex-credentials/v1".getBytes(StandardCharsets.UTF_8);

    private final SecretKeySpec key;
    private boolean dedicatedKey = true;
    private final SecureRandom secureRandom = new SecureRandom();

    @Autowired
    public CodexCredentialCipher(
            @Value("${app.codex-credential-encryption-key:}") String configuredSecret,
            @Value("${app.jwt-secret}") String jwtSecret
    ) {
        this(configuredSecret == null || configuredSecret.isBlank()
                ? jwtSecret
                : configuredSecret);
        dedicatedKey = configuredSecret != null && !configuredSecret.isBlank()
                && !configuredSecret.equals(jwtSecret);
    }

    CodexCredentialCipher(String secret) {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException(
                    "CODEX_CREDENTIAL_ENCRYPTION_KEY must contain at least 32 bytes"
            );
        }
        this.key = new SecretKeySpec(deriveKey(secret), "AES");
    }

    public void requireDedicatedKey() {
        if (!dedicatedKey) {
            throw new DomainException("CODEX_ENCRYPTION_KEY_REQUIRED",
                    "请先在服务器配置独立的 CODEX_CREDENTIAL_ENCRYPTION_KEY，再保存 Codex 凭据。",
                    HttpStatus.SERVICE_UNAVAILABLE);
        }
    }

    public EncryptedCredential encrypt(UUID accountId, byte[] plaintext) {
        byte[] iv = new byte[IV_BYTES];
        secureRandom.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            cipher.updateAAD(accountId.toString().getBytes(StandardCharsets.UTF_8));
            byte[] encrypted = cipher.doFinal(plaintext);
            return new EncryptedCredential(
                    Base64.getEncoder().encodeToString(encrypted),
                    Base64.getEncoder().encodeToString(iv)
            );
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Unable to encrypt Codex credentials", exception);
        }
    }

    byte[] decrypt(UUID accountId, String ciphertext, String encodedIv) {
        try {
            byte[] iv = Base64.getDecoder().decode(encodedIv);
            byte[] encrypted = Base64.getDecoder().decode(ciphertext);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            cipher.updateAAD(accountId.toString().getBytes(StandardCharsets.UTF_8));
            return cipher.doFinal(encrypted);
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            throw new IllegalStateException("Unable to decrypt Codex credentials", exception);
        }
    }

    private byte[] deriveKey(String secret) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(KEY_CONTEXT);
            return digest.digest(secret.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public record EncryptedCredential(String ciphertext, String iv) {
    }
}
