package com.pingche.web.mail;

import static org.assertj.core.api.Assertions.assertThat;

import com.pingche.web.auth.DomainException;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class MailConnectionMessageTest {
    @Test
    void knownFailureCodesHaveUsefulFixedMessagesWithoutProviderDetails() {
        Map<String, String> cases = Map.of(
                "MAIL_DNS_FAILED", "解析",
                "MAIL_HOST_BLOCKED", "公网",
                "MAIL_AUTH_FAILED", "授权码",
                "MAIL_TLS_FAILED", "证书",
                "MAIL_CONNECTION_TIMEOUT", "超时",
                "MAIL_FOLDER_FAILED", "INBOX",
                "MAIL_CURSOR_UNAVAILABLE", "新邮件",
                "MAIL_OAUTH_FAILED", "OAuth",
                "MAIL_CONNECTION_FAILED", "网络");
        cases.forEach((code, word) -> {
            String message = MailAssistanceService.connectionFailureMessage(
                    new DomainException(code, "private-secret owner@example.com upstream response", HttpStatus.BAD_GATEWAY));
            assertThat(message).contains(code, word).doesNotContain("private-secret", "owner@example.com", "upstream response");
        });
    }

    @Test
    void unknownCodesAndExceptionsNeverBecomeRawUserMessages() {
        for (RuntimeException error : new RuntimeException[] {
                new IllegalStateException("private-secret"),
                new DomainException("private-secret", "private-secret", HttpStatus.BAD_GATEWAY)}) {
            assertThat(MailAssistanceService.connectionFailureMessage(error))
                    .contains("MAIL_CONNECTION_FAILED").doesNotContain("private-secret");
        }
    }

    @Test
    void tlsFailureDoesNotAssertThatTheCertificateWasTheCause() {
        assertThat(MailAssistanceService.connectionFailureMessage(
                new DomainException("MAIL_TLS_FAILED", "Remote host terminated the handshake", HttpStatus.BAD_GATEWAY)))
                .contains("TLS", "加密连接", "证书", "MAIL_TLS_FAILED")
                .doesNotContain("证书验证失败", "Remote host");
    }
}
