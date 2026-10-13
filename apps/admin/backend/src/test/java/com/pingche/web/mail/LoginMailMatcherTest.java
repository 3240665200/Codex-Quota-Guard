package com.pingche.web.mail;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

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
    void acceptsTheExistingLoginTemplateAndDoesNotRejectItsSafetyReminder() {
        assertThat(matcher.findCode(message(BODY), "sender@example.com", "owner@example.com", ARMED))
                .contains("123456");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("supportedLoginMessages")
    void acceptsBilingualLoginMessagesWithoutRequiringOneExactTemplate(String description, String subject, String body) {
        assertThat(matcher.findCode(message(subject, body), "sender@example.com", "owner@example.com", ARMED))
                .contains("123456");
    }

    private static Stream<Arguments> supportedLoginMessages() {
        return Stream.of(
                Arguments.of("English changed security wording", "ChatGPT Log-in Code",
                        BODY.replace("We noticed a suspicious log-in on your account.", "A code was requested.")),
                Arguments.of("English login label", "Your ChatGPT login code", "Your login code is: 123456"),
                Arguments.of("English sign-in label", "ChatGPT sign-in code", "Sign-in code: 123456"),
                Arguments.of("English generic code with explicit login subject", "Your ChatGPT login code",
                        "Your one-time code is: 123456\nThis code expires shortly."),
                Arguments.of("English short code label", "Your ChatGPT login code", "Code: 123456"),
                Arguments.of("English following-code prompt", "ChatGPT login code", "Enter the following code: 123456"),
                Arguments.of("English body establishes product and purpose", "Security verification",
                        "ChatGPT\nEnter this code to sign in: 123456"),
                Arguments.of("English lowercase and Unicode hyphen", "CHATGPT SIGN\u2011IN CODE",
                        "your sign\u2013in code\u00a0is\uff1a\n\t123456"),
                Arguments.of("Simplified Chinese login label", "ChatGPT 登录验证码", "您的登录验证码为：123456"),
                Arguments.of("Simplified Chinese temporary code", "ChatGPT 临时登录代码",
                        "您的临时登录代码是：123456\n请勿与他人分享。"),
                Arguments.of("Simplified Chinese input prompt", "ChatGPT 登录验证码",
                        "我们检测到您的帐户有可疑的登录尝试。如果这是您本人，请输入以下代码：\n123456\n如果不是您，请重置密码。"),
                Arguments.of("Traditional Chinese login label", "ChatGPT 登入驗證碼", "您的登入驗證碼為：123456"),
                Arguments.of("Traditional Chinese one-time code", "ChatGPT 一次性登錄代碼",
                        "您的一次性登錄代碼為：123456\n若非本人操作，請重設密碼。"),
                Arguments.of("Chinese body establishes product and purpose", "安全验证",
                        "请输入以下验证码以登录 ChatGPT：123456"),
                Arguments.of("Chinese generic code with explicit login subject", "ChatGPT 登录验证码",
                        "一次性验证码：123456"),
                Arguments.of("Chinese fullwidth numbers", "ChatGPT 登录验证码", "验证码：１２３４５６"),
                Arguments.of("Code in subject", "Your ChatGPT login code: 123456",
                        "Use this code to finish signing in. If this wasn't you, reset your password."),
                Arguments.of("Strong login subject with code-only body", "ChatGPT 登录验证码", "\n123456\n"),
                Arguments.of("Body only with missing subject", null, "ChatGPT login code: 123456"),
                Arguments.of("Repeated same code in subject and body", "ChatGPT login code: 123456",
                        "Your login code is 123456.\nLogin code: 123456"),
                Arguments.of("Repeated same code in plain and HTML representations", "ChatGPT Log-in Code",
                        BODY + "\n" + BODY));
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
    void rejectsMultipleDifferentCodesAndLongerNumbers() {
        assertThat(matcher.findCode(message(BODY + "\n654321"), "sender@example.com", "owner@example.com", ARMED)).isEmpty();
        assertThat(matcher.findCode(message(BODY.replace("123456", "1234567")), "sender@example.com", "owner@example.com", ARMED)).isEmpty();
    }

    @Test
    void rejectsSixDigitNumberThatDoesNotImmediatelyFollowTheLoginPrompt() {
        String misplaced = BODY.replace("123456", "Please review this message. Reference number: 123456");
        assertThat(matcher.findCode(message(misplaced), "sender@example.com", "owner@example.com", ARMED)).isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("unrelatedOrAmbiguousMessages")
    void rejectsNonLoginPurposesAndNumbersWithoutACodeLabel(String description, String subject, String body) {
        assertThat(matcher.findCode(message(subject, body), "sender@example.com", "owner@example.com", ARMED)).isEmpty();
    }

    private static Stream<Arguments> unrelatedOrAmbiguousMessages() {
        return Stream.of(
                Arguments.of("Generic verification without login purpose", "ChatGPT verification",
                        "Your verification code is 123456"),
                Arguments.of("Another service", "OtherService login code", "Your login code is 123456"),
                Arguments.of("Product name substring", "FakeChatGPT login code", "Your login code is 123456"),
                Arguments.of("Billing reference", "ChatGPT invoice", "Your invoice number is 123456"),
                Arguments.of("Marketing coupon", "ChatGPT offers", "Your promotional code is 123456"),
                Arguments.of("Login mention in newsletter", "ChatGPT newsletter",
                        "Login to see our offers. Promotional code: 123456"),
                Arguments.of("Password reset subject despite login text", "ChatGPT password reset",
                        "Your ChatGPT login code: 123456"),
                Arguments.of("Product inside password reset subject", "Reset your ChatGPT password",
                        "Your login code is 123456"),
                Arguments.of("Product inside recovery subject", "Recover your OpenAI account",
                        "Your ChatGPT login code is 123456"),
                Arguments.of("Recovery subject despite login text", "ChatGPT account recovery",
                        "Your ChatGPT login code: 123456"),
                Arguments.of("Chinese reset subject despite login text", "ChatGPT 重置密码",
                        "登录验证码：123456"),
                Arguments.of("Traditional Chinese recovery subject", "ChatGPT 帳戶復原",
                        "登入代碼：123456"),
                Arguments.of("Password reset is the main body purpose", "ChatGPT login code",
                        "Reset your password\nEnter this code: 123456"),
                Arguments.of("Account recovery is the main body purpose", "ChatGPT login code",
                        "Account recovery\nYour recovery code is 123456"),
                Arguments.of("Chinese reset is the main body purpose", "ChatGPT 登录验证码",
                        "请使用以下验证码重置您的密码：123456"),
                Arguments.of("Login subject cannot turn reference into code", "ChatGPT login code",
                        "Please review this message. Reference number: 123456"),
                Arguments.of("Subject label cannot cross into a body reference", "ChatGPT login code",
                        "123456 is the reference number for this request."),
                Arguments.of("Instruction must immediately precede code", "ChatGPT login code",
                        "Enter this code: Please review this message. Reference number: 123456"),
                Arguments.of("Chinese instruction must immediately precede code", "ChatGPT 登录验证码",
                        "请输入以下代码：请核对订单。订单编号：123456"),
                Arguments.of("Conflicting subject and body codes", "ChatGPT login code: 123456",
                        "Your login code is 654321"),
                Arguments.of("Alphanumeric prefix", "ChatGPT Log-in Code", BODY.replace("123456", "A123456")),
                Arguments.of("Alphanumeric suffix", "ChatGPT Log-in Code", BODY.replace("123456", "123456Z")),
                Arguments.of("Unicode letter suffix", "ChatGPT Log-in Code", BODY.replace("123456", "123456é")),
                Arguments.of("Unicode digit suffix", "ChatGPT Log-in Code", BODY.replace("123456", "123456\u0667")),
                Arguments.of("Number in URL path", "ChatGPT login code", "Enter this code: https://example.test/123456"),
                Arguments.of("Number in host name", "ChatGPT Log-in Code", BODY.replace("123456", "123456.example.test")),
                Arguments.of("Reset purpose after login heading", "ChatGPT login code",
                        "ChatGPT login\nUse this password reset code: 123456"),
                Arguments.of("Longer fullwidth number", "ChatGPT 登录验证码", "验证码：１２３４５６７"));
    }

    @Test
    void rejectsMultipleActualSendersAndSenderSubstrings() {
        for (List<String> senders : List.of(List.of("sender@example.com", "other@example.com"),
                List.of("sender@example.com.attacker.test"))) {
            LoginMailMessage wrong = new LoginMailMessage(senders, List.of("owner@example.com"),
                    "ChatGPT 登录验证码", "登录验证码：123456", ARMED.plusSeconds(1));
            assertThat(matcher.findCode(wrong, "sender@example.com", "owner@example.com", ARMED)).isEmpty();
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

    @Test
    void rejectsOversizedSubjectBeforeMatching() {
        assertThat(matcher.findCode(message("ChatGPT login code " + "x".repeat(4096), BODY),
                "sender@example.com", "owner@example.com", ARMED)).isEmpty();
    }

    private LoginMailMessage message(String body) {
        return message("ChatGPT Log-in Code", body);
    }

    private LoginMailMessage message(String subject, String body) {
        return new LoginMailMessage(List.of("sender@example.com"), List.of("owner@example.com"),
                subject, body, ARMED.plusSeconds(1));
    }
}
