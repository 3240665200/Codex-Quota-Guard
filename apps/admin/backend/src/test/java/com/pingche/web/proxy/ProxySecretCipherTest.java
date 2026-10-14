package com.pingche.web.proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pingche.web.auth.DomainException;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class ProxySecretCipherTest {
    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);

    @Test
    void encryptsWithRandomNonceAndBindsCiphertextToItsNode() {
        ProxySecretCipher cipher = new ProxySecretCipher(KEY);
        String first = cipher.encrypt("synthetic-node-password", "node-1");
        String second = cipher.encrypt("synthetic-node-password", "node-1");
        assertThat(first).isNotEqualTo(second).doesNotContain("synthetic-node-password");
        assertThat(cipher.decrypt(first, "node-1")).isEqualTo("synthetic-node-password");
        assertThatThrownBy(() -> cipher.decrypt(first, "node-2"))
                .isInstanceOf(DomainException.class).hasMessageNotContaining("synthetic-node-password");
        byte[] bytes = Base64.getDecoder().decode(first);
        bytes[bytes.length - 1] ^= 1;
        assertThatThrownBy(() -> cipher.decrypt(Base64.getEncoder().encodeToString(bytes), "node-1"))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void absentOrWrongLengthIndependentKeyFailsClosedWithoutBreakingStartup() {
        for (String key : new String[] {"", "not-base64", Base64.getEncoder().encodeToString(new byte[16])}) {
            ProxySecretCipher cipher = new ProxySecretCipher(key);
            assertThat(cipher.isReady()).isFalse();
            assertThatThrownBy(() -> cipher.encrypt("synthetic-node-password", "node-1"))
                    .isInstanceOf(DomainException.class).hasMessageNotContaining("synthetic-node-password");
        }
    }
}
