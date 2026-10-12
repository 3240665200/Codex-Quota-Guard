package com.pingche.web.mail;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class LoginMailMatcherTest {
    private static final Instant ARMED = Instant.parse("2026-10-12T01:00:00Z");
    private static final String BODY = """
            ChatGPT Log-in Code
            We noticed a suspicious log-in on your account. If that was you, enter this code:
            123456
            If this wasn't you, reset your password to secure your account.
            """;
    private final LoginMailMatcher matcher = new LoginMailMatcher();

    @Test
    void acceptsOnlyTheConfiguredLoginTemplateAndDoesNotRejectItsSafetyReminder() {
        assertThat(matcher.findCode(message(BODY), "sender@example.com", "owner@example.com", ARMED))
                .contains("123456");
    }

    @Test
    void rejectsMailReceivedBeforeListeningWasReady() {
        LoginMailMessage old = new LoginMailMessage(List.of("sender@example.com"),
                List.of("owner@example.com"), "ChatGPT Log-in Code", BODY, ARMED.minusSeconds(1));
        assertThat(matcher.findCode(old, "sender@example.com", "owner@example.com", ARMED)).isEmpty();
    }

    @Test
    void acceptsNewUidMailWhoseServerReceiptTimeHasOnlySecondPrecision() {
        LoginMailMessage sameSecond = new LoginMailMessage(List.of("sender@example.com"),
                List.of("owner@example.com"), "ChatGPT Log-in Code", BODY, ARMED);
        assertThat(matcher.findCode(sameSecond, "sender@example.com", "owner@example.com", ARMED.plusNanos(123_000_000)))
                .contains("123456");
    }

    @Test
    void rejectsDifferentActualSenderEvenIfDisplayNameLooksCorrect() {
        LoginMailMessage spoof = new LoginMailMessage(List.of("attacker@example.com"),
                List.of("owner@example.com"), "ChatGPT Log-in Code", BODY, ARMED.plusSeconds(1));
        assertThat(matcher.findCode(spoof, "sender@example.com", "owner@example.com", ARMED)).isEmpty();
    }

    @Test
    void rejectsMailForAnotherRecipientOrRecipientSubstring() {
        LoginMailMessage wrong = new LoginMailMessage(List.of("sender@example.com"),
                List.of("other-owner@example.com"), "ChatGPT Log-in Code", BODY, ARMED.plusSeconds(1));
        assertThat(matcher.findCode(wrong, "sender@example.com", "owner@example.com", ARMED)).isEmpty();
    }

    @Test
    void rejectsMultipleCodesAndLongerNumbers() {
        assertThat(matcher.findCode(message(BODY + "\n654321"), "sender@example.com", "owner@example.com", ARMED)).isEmpty();
        assertThat(matcher.findCode(message(BODY.replace("123456", "1234567")), "sender@example.com", "owner@example.com", ARMED)).isEmpty();
    }

    @Test
    void rejectsSixDigitNumberThatDoesNotImmediatelyFollowTheLoginPrompt() {
        String misplaced = BODY.replace("123456", "Please review this message. Reference number: 123456");
        assertThat(matcher.findCode(message(misplaced), "sender@example.com", "owner@example.com", ARMED)).isEmpty();
    }

    @Test
    void rejectsResetRecoveryUnknownAndInexactTemplates() {
        for (String body : List.of("Reset your password 123456", "Account recovery code 123456",
                "Your verification code is 123456", BODY.replace("ChatGPT Log-in Code", "Account recovery"),
                BODY.replace("We noticed a suspicious log-in on your account.", "A code was requested."))) {
            assertThat(matcher.findCode(message(body), "sender@example.com", "owner@example.com", ARMED)).isEmpty();
        }
    }

    @Test
    void rejectsOversizedOrMissingBodyAndMissingReceiptTime() {
        assertThat(matcher.findCode(message(BODY + "x".repeat(300_000)), "sender@example.com", "owner@example.com", ARMED)).isEmpty();
        assertThat(matcher.findCode(message(null), "sender@example.com", "owner@example.com", ARMED)).isEmpty();
        LoginMailMessage missingTime = new LoginMailMessage(List.of("sender@example.com"),
                List.of("owner@example.com"), "ChatGPT Log-in Code", BODY, null);
        assertThat(matcher.findCode(missingTime, "sender@example.com", "owner@example.com", ARMED)).isEmpty();
    }

    private LoginMailMessage message(String body) {
        return new LoginMailMessage(List.of("sender@example.com"), List.of("owner@example.com"),
                "ChatGPT Log-in Code", body, ARMED.plusSeconds(1));
    }
}
