package com.pingche.web.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pingche.web.auth.DomainException;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class MailSecretCipherTest {
    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);

    @Test
    void roundTripsWithRandomAuthenticatedCiphertextWithoutPlaintext() {
        MailSecretCipher cipher = new MailSecretCipher(KEY);
        String first = cipher.encrypt("synthetic-application-password");
        String second = cipher.encrypt("synthetic-application-password");
        assertThat(first).doesNotContain("synthetic-application-password").isNotEqualTo(second);
        assertThat(cipher.decrypt(first)).isEqualTo("synthetic-application-password");
    }

    @Test
    void missingOrInvalidIndependentKeyDisablesFeatureWithoutPreventingApplicationStartup() {
        for (String key : new String[] {"", "invalid-base64", Base64.getEncoder().encodeToString(new byte[16])}) {
            MailSecretCipher cipher = new MailSecretCipher(key);
            assertThat(cipher.isReady()).isFalse();
            assertThatThrownBy(() -> cipher.encrypt("private-secret")).isInstanceOf(DomainException.class)
                    .hasMessage("Mailbox encryption is unavailable");
        }
    }

    @Test
    void tamperingAndWrongKeyReturnSafeDomainErrors() {
        MailSecretCipher cipher = new MailSecretCipher(KEY);
        String encrypted = cipher.encrypt("private-secret");
        byte[] payload = Base64.getDecoder().decode(encrypted);
        payload[payload.length - 1] ^= 1;
        assertThatThrownBy(() -> cipher.decrypt(Base64.getEncoder().encodeToString(payload)))
                .isInstanceOf(DomainException.class).hasMessage("Mailbox credentials are unavailable");
        byte[] otherKey = new byte[32];
        otherKey[0] = 1;
        assertThatThrownBy(() -> new MailSecretCipher(Base64.getEncoder().encodeToString(otherKey)).decrypt(encrypted))
                .isInstanceOf(DomainException.class).hasMessage("Mailbox credentials are unavailable");
    }
}
