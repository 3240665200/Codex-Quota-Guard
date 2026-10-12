package com.pingche.web.mail;

import com.pingche.web.auth.DomainException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class MailSecretCipher {
    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public MailSecretCipher(@Value("${MAIL_ASSISTANCE_ENCRYPTION_KEY:}") String configuredKey) {
        SecretKeySpec resolved = null;
        try {
            byte[] bytes = Base64.getDecoder().decode(configuredKey);
            if (bytes.length == 32) resolved = new SecretKeySpec(bytes, "AES");
        } catch (IllegalArgumentException ignored) {
            // Missing/invalid optional feature configuration does not break the rest of the application.
        }
        key = resolved;
    }

    public boolean isReady() { return key != null; }
    public String encrypt(String value) { return encrypt(value, "mail-test"); }
    public String decrypt(String value) { return decrypt(value, "mail-test"); }

    public String encrypt(String value, String context) {
        requireReady();
        try {
            byte[] nonce = new byte[12];
            random.nextBytes(nonce);
            Cipher cipher = cipher(Cipher.ENCRYPT_MODE, nonce, context);
            byte[] ciphertext = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            byte[] payload = new byte[nonce.length + ciphertext.length];
            System.arraycopy(nonce, 0, payload, 0, nonce.length);
            System.arraycopy(ciphertext, 0, payload, nonce.length, ciphertext.length);
            return Base64.getEncoder().encodeToString(payload);
        } catch (GeneralSecurityException | RuntimeException ignored) {
            throw unavailable();
        }
    }

    public String decrypt(String value, String context) {
        requireReady();
        try {
            byte[] payload = Base64.getDecoder().decode(value);
            if (payload.length < 29) throw new IllegalArgumentException();
            byte[] nonce = java.util.Arrays.copyOf(payload, 12);
            return new String(cipher(Cipher.DECRYPT_MODE, nonce, context)
                    .doFinal(payload, 12, payload.length - 12), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | RuntimeException ignored) {
            throw unavailable();
        }
    }

    private Cipher cipher(int mode, byte[] nonce, String context) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, key, new GCMParameterSpec(128, nonce));
        cipher.updateAAD(context.getBytes(StandardCharsets.UTF_8));
        return cipher;
    }

    private void requireReady() {
        if (!isReady()) throw new DomainException("MAIL_ENCRYPTION_UNAVAILABLE",
                "Mailbox encryption is unavailable", HttpStatus.SERVICE_UNAVAILABLE);
    }

    private DomainException unavailable() {
        return new DomainException("MAIL_CREDENTIALS_UNAVAILABLE", "Mailbox credentials are unavailable",
                HttpStatus.SERVICE_UNAVAILABLE);
    }
}
