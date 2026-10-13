package com.pingche.web.mail;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.util.Date;
import java.util.Properties;
import org.junit.jupiter.api.Test;

/** Synthetic mail only: no customer message, address or real login code is stored here. */
class LoginMailMimeCompatibilityTest {
    private static final Instant STARTED = Instant.parse("2026-10-13T01:00:00Z");
    private final MailBodyReader reader = new MailBodyReader();
    private final LoginMailMatcher matcher = new LoginMailMatcher();

    @Test
    void decodesChineseSubjectAndBase64PlainTextBeforeExtractingOnlyTheCode() throws Exception {
        MimeMessage message = message("你的临时 ChatGPT 登录代码");
        message.setText("你好！\n请使用以下验证码完成登录：\n123456\n如果不是你本人操作，请忽略这封邮件。", "UTF-8");
        message.setHeader("Content-Transfer-Encoding", "base64");
        assertCode(message);
    }

    @Test
    void decodesEnglishQuotedPrintableHtmlWithCaseHyphenAndWhitespaceVariations() throws Exception {
        MimeMessage message = message("Your temporary ChatGPT sign-in code");
        message.setContent("""
                <html><head><style>.hidden { color: #654321; }</style></head><body>
                <h1>CHATGPT Sign&#8209;in Code</h1>
                <p>Your verification code is:&nbsp;</p><div><strong>123456</strong></div>
                <p>If you did not request this login, you can reset your password.</p>
                <img src="https://untrusted.example.com/track" alt="654321">
                </body></html>
                """, "text/html; charset=UTF-8");
        message.setHeader("Content-Transfer-Encoding", "quoted-printable");
        assertCode(message);
    }

    @Test
    void choosesOneMimeAlternativeAndDoesNotTreatDuplicateRepresentationsAsDifferentCodes() throws Exception {
        MimeMessage message = message("ChatGPT 登录验证码");
        MimeBodyPart plain = new MimeBodyPart();
        plain.setText("ChatGPT 登录验证码：123456\n请勿将此验证码转发给他人。", "UTF-8");
        MimeBodyPart html = new MimeBodyPart();
        html.setContent("<h1>ChatGPT 登录验证码</h1><p>验证码：123456</p>", "text/html; charset=UTF-8");
        MimeMultipart alternatives = new MimeMultipart("alternative");
        alternatives.addBodyPart(plain);
        alternatives.addBodyPart(html);
        message.setContent(alternatives);
        assertCode(message);
    }

    @Test
    void handlesTraditionalChineseHtmlAndFullWidthDigitsWithoutReadingAttachments() throws Exception {
        MimeMessage message = message("你的 ChatGPT 臨時登入代碼");
        MimeBodyPart html = new MimeBodyPart();
        html.setContent("<h1>登入 ChatGPT</h1><p>請輸入以下驗證碼：</p><div>１２３４５６</div>", "text/html; charset=UTF-8");
        MimeBodyPart attachment = new MimeBodyPart();
        attachment.setText("654321", "UTF-8");
        attachment.setFileName("unrelated.txt");
        MimeMultipart mixed = new MimeMultipart("mixed");
        mixed.addBodyPart(html);
        mixed.addBodyPart(attachment);
        message.setContent(mixed);
        assertCode(message);
    }

    @Test
    void doesNotReturnAResetCodeFromEncodedHtmlMail() throws Exception {
        MimeMessage message = message("重置你的 ChatGPT 密码");
        message.setContent("<h1>ChatGPT 密码重置</h1><p>验证码：123456</p><footer>登录帮助</footer>", "text/html; charset=UTF-8");
        message.setHeader("Content-Transfer-Encoding", "base64");
        LoginMailMessage parsed = reader.read(roundTrip(message));
        assertThat(matcher.findCode(parsed, "sender@example.com", "owner@example.com", STARTED)).isEmpty();
    }

    private MimeMessage message(String subject) throws Exception {
        MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
        message.setFrom(new InternetAddress("sender@example.com", "ChatGPT", "UTF-8"));
        message.setRecipients(Message.RecipientType.TO, "owner@example.com");
        message.setSubject(subject, "UTF-8");
        return message;
    }

    private MimeMessage roundTrip(MimeMessage source) throws Exception {
        source.saveChanges();
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        source.writeTo(encoded);
        return new MimeMessage(Session.getInstance(new Properties()), new ByteArrayInputStream(encoded.toByteArray())) {
            // IMAP supplies INTERNALDATE. A local .eml has no equivalent guaranteed receipt time.
            @Override public Date getReceivedDate() { return Date.from(STARTED.plusSeconds(1)); }
        };
    }

    private void assertCode(MimeMessage source) throws Exception {
        LoginMailMessage parsed = reader.read(roundTrip(source));
        assertThat(parsed).isNotNull();
        assertThat(matcher.findCode(parsed, "sender@example.com", "owner@example.com", STARTED)).contains("123456");
    }
}
