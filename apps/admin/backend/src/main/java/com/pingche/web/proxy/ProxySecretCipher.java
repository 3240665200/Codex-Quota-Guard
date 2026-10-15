package com.pingche.web.proxy;

import com.pingche.web.auth.DomainException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class ProxySecretCipher {
    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public ProxySecretCipher(@Value("${MANAGED_PROXY_ENCRYPTION_KEY:}") String configuredKey) {
        SecretKeySpec resolved = null;
        try {
            byte[] bytes = Base64.getDecoder().decode(configuredKey);
            if (bytes.length == 32) resolved = new SecretKeySpec(bytes, "AES");
            Arrays.fill(bytes, (byte) 0);
        } catch (IllegalArgumentException ignored) { }
        key = resolved;
    }

    public boolean isReady() { return key != null; }

    public String encrypt(String value, String nodeId) {
        requireReady();
        try {
            byte[] nonce = new byte[12];
            random.nextBytes(nonce);
            byte[] encrypted = cipher(Cipher.ENCRYPT_MODE, nonce, nodeId).doFinal(value.getBytes(StandardCharsets.UTF_8));
            byte[] payload = Arrays.copyOf(nonce, nonce.length + encrypted.length);
            System.arraycopy(encrypted, 0, payload, nonce.length, encrypted.length);
            return Base64.getEncoder().encodeToString(payload);
        } catch (GeneralSecurityException | RuntimeException ignored) { throw unavailable(); }
    }

    public String decrypt(String value, String nodeId) {
        requireReady();
        try {
            byte[] payload = Base64.getDecoder().decode(value);
            if (payload.length < 29) throw new IllegalArgumentException();
            return new String(cipher(Cipher.DECRYPT_MODE, Arrays.copyOf(payload, 12), nodeId)
                    .doFinal(payload, 12, payload.length - 12), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | RuntimeException ignored) { throw unavailable(); }
    }

    private Cipher cipher(int mode, byte[] nonce, String nodeId) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, key, new GCMParameterSpec(128, nonce));
        cipher.updateAAD(("managed-proxy:v1:" + nodeId).getBytes(StandardCharsets.UTF_8));
        return cipher;
    }

    private void requireReady() {
        if (!isReady()) throw new DomainException("PROXY_ENCRYPTION_UNAVAILABLE", "代理节点加密密钥未配置。", HttpStatus.SERVICE_UNAVAILABLE);
    }

    private DomainException unavailable() {
        return new DomainException("PROXY_CREDENTIALS_UNAVAILABLE", "代理节点凭据不可用。", HttpStatus.SERVICE_UNAVAILABLE);
    }
}
