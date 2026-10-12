package com.pingche.web.mail;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(properties = "MAIL_ASSISTANCE_ENCRYPTION_KEY=")
@ActiveProfiles("test")
class MailEncryptionDisabledIntegrationTest {
    @Autowired MailSecretCipher cipher;
    @Autowired MailAssistanceService service;
    @Test void optionalMissingKeyStillAllowsApplicationContextToStart() {
        assertThat(cipher.isReady()).isFalse(); assertThat(service).isNotNull();
    }
}
