package com.pingche.web.officialsession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pingche.web.auth.DomainException;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class OfficialSessionCipherTest {
    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);

    @Test
    void independentKeyEncryptsWithRandomNonceAndAccountFieldBinding() {
        OfficialSessionCipher cipher = new OfficialSessionCipher(KEY);
        String context = "official-session:v1:account-one:authorization";
        String first = cipher.encrypt("Bearer synthetic-secret", context);
        String second = cipher.encrypt("Bearer synthetic-secret", context);
        assertThat(first).doesNotContain("synthetic-secret").isNotEqualTo(second);
        assertThat(cipher.decrypt(first, context)).isEqualTo("Bearer synthetic-secret");
        for (String wrongContext : new String[] {
                "official-session:v1:account-two:authorization", "official-session:v1:account-one:cookie"}) {
            assertThatThrownBy(() -> cipher.decrypt(first, wrongContext))
                    .isInstanceOf(DomainException.class).hasMessage("Official authorization is unavailable");
        }
    }

    @Test
    void invalidOptionalKeyDisablesOnlyThisFeatureAndNeverEchoesSecrets() {
        for (String key : new String[] {"", "private-invalid-key", Base64.getEncoder().encodeToString(new byte[16])}) {
            OfficialSessionCipher cipher = new OfficialSessionCipher(key);
            assertThat(cipher.isReady()).isFalse();
            assertThatThrownBy(() -> cipher.encrypt("private-secret", "context"))
                    .isInstanceOfSatisfying(DomainException.class, error -> {
                        assertThat(error.getCode()).isEqualTo("OFFICIAL_ENCRYPTION_UNAVAILABLE");
                        assertThat(error.getMessage()).doesNotContain("private-secret", key.isEmpty() ? "sentinel" : key);
                        assertThat(error.getCause()).isNull();
                    });
        }
    }

    @Test
    void corruptCiphertextWrongKeyAndMalformedPayloadFailClosed() {
        OfficialSessionCipher cipher = new OfficialSessionCipher(KEY);
        String encoded = cipher.encrypt("private-secret", "context");
        byte[] bytes = Base64.getDecoder().decode(encoded);
        bytes[bytes.length - 1] ^= 1;
        for (String invalid : new String[] {Base64.getEncoder().encodeToString(bytes), "bad", ""}) {
            assertThatThrownBy(() -> cipher.decrypt(invalid, "context"))
                    .isInstanceOf(DomainException.class).hasMessage("Official authorization is unavailable");
        }
        byte[] other = new byte[32];
        other[0] = 1;
        assertThatThrownBy(() -> new OfficialSessionCipher(Base64.getEncoder().encodeToString(other))
                .decrypt(encoded, "context")).isInstanceOf(DomainException.class);
    }
}
