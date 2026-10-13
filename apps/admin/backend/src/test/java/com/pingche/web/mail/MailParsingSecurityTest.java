package com.pingche.web.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pingche.web.auth.DomainException;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import java.io.IOException;
import java.net.InetAddress;
import java.util.Base64;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class MailParsingSecurityTest {
    private final MailBodyReader reader = new MailBodyReader();

    @Test
    void htmlIsParsedOfflineWithoutScriptsAndMimeAlternativesDoNotDuplicateCode() throws Exception {
        assertThat(reader.htmlText("<html><head><script>654321</script></head><body><h1>ChatGPT Log-in Code</h1><p>123456</p><img src='https://untrusted.example.com/'></body></html>"))
                .contains("ChatGPT Log-in Code", "123456").doesNotContain("654321", "untrusted");
        MimeBodyPart plain = new MimeBodyPart(); plain.setText("123456", "UTF-8");
        MimeBodyPart html = new MimeBodyPart(); html.setContent("<p>123456</p>", "text/html; charset=UTF-8");
        MimeMultipart alternative = new MimeMultipart("alternative"); alternative.addBodyPart(plain); alternative.addBodyPart(html);
        MimeMessage message = new MimeMessage(Session.getInstance(new Properties())); message.setContent(alternative); message.saveChanges();
        assertThat(reader.body(message, 0, new MailBodyReader.Budget())).isEqualTo("123456");
    }

    @Test
    void attachmentsOversizedBodyAndDeepMimeAreBounded() throws Exception {
        MimeBodyPart attachment = new MimeBodyPart(); attachment.setText("654321"); attachment.setDisposition(Part.ATTACHMENT);
        assertThat(reader.body(attachment, 0, new MailBodyReader.Budget())).isEmpty();
        MimeBodyPart huge = new MimeBodyPart(); huge.setText("a".repeat(300_000));
        assertThatThrownBy(() -> reader.body(huge, 0, new MailBodyReader.Budget())).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> reader.body(attachment, 9, new MailBodyReader.Budget())).isInstanceOf(IOException.class);
    }

    @Test
    void emptyPlainAlternativeFallsBackToHtmlBody() throws Exception {
        MimeBodyPart plain = new MimeBodyPart(); plain.setText(" ", "UTF-8");
        MimeBodyPart html = new MimeBodyPart(); html.setContent("<p>ChatGPT Log-in Code 123456</p>", "text/html; charset=UTF-8");
        MimeMultipart multipart = new MimeMultipart("alternative"); multipart.addBodyPart(plain); multipart.addBodyPart(html);
        MimeMessage message = new MimeMessage(Session.getInstance(new Properties())); message.setContent(multipart); message.saveChanges();
        assertThat(reader.body(message, 0, new MailBodyReader.Budget())).contains("ChatGPT Log-in Code 123456");
    }

    @Test
    void loopbackPrivateMetadataAndIpv6LocalTargetsAreRejectedWithoutNetwork() throws Exception {
        MailTargetPolicy policy = new MailTargetPolicy();
        for (String host : new String[] {"localhost", "127.0.0.1", "169.254.169.254", "http://imap.example.com", "mail.local"})
            assertThatThrownBy(() -> policy.validateHost(host)).isInstanceOf(DomainException.class);
        for (String ip : new String[] {"127.0.0.1", "169.254.169.254", "10.0.0.1", "172.16.1.1", "192.168.1.1", "100.64.0.1", "::1", "fe80::1", "fd00::1", "198.51.100.1", "203.0.113.1", "2001:db8::1"})
            assertThat(MailTargetPolicy.isPublic(InetAddress.getByName(ip))).as(ip).isFalse();
        assertThat(MailTargetPolicy.isPublic(InetAddress.getByName("8.8.8.8"))).isTrue();
        policy.validateHost("imap.gmail.com");
    }

    @Test
    void cipherContextsPreventExchangingAccountsOrResults() {
        MailSecretCipher cipher = new MailSecretCipher(Base64.getEncoder().encodeToString(new byte[32]));
        String ciphertext = cipher.encrypt("synthetic-secret", "mailbox:account-1:password");
        assertThatThrownBy(() -> cipher.decrypt(ciphertext, "mailbox:account-2:password")).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> cipher.decrypt(ciphertext, "mail-result:another-user")).isInstanceOf(DomainException.class);
    }

    @Test
    void pinnedRawSocketsCanBeClosedBeforeFolderLocksWithoutOpeningNetworkConnection() throws Exception {
        MailTargetPolicy.PinnedSocketFactory factory = new MailTargetPolicy.PinnedSocketFactory(InetAddress.getByName("8.8.8.8"), 993);
        java.net.Socket first = factory.createSocket(); java.net.Socket second = factory.createSocket();
        MailTargetPolicy.closeSockets(factory);
        assertThat(first.isClosed()).isTrue(); assertThat(second.isClosed()).isTrue();
        assertThatThrownBy(factory::createSocket).isInstanceOf(IOException.class);
    }

    @Test
    void oauthUsesOnlyFixedOfficialEndpointsAndCatalogMatchesModes() {
        MailOAuthClient oauth = new MailOAuthClient(new ObjectMapper());
        assertThat(oauth.endpoint(MailboxConfig.AuthMode.GOOGLE_OAUTH2, "evil.example").toString()).isEqualTo("https://oauth2.googleapis.com/token");
        assertThat(oauth.endpoint(MailboxConfig.AuthMode.MICROSOFT_OAUTH2, null).toString()).isEqualTo("https://login.microsoftonline.com/common/oauth2/v2.0/token");
        assertThatThrownBy(() -> oauth.endpoint(MailboxConfig.AuthMode.MICROSOFT_OAUTH2, "evil.example/path")).isInstanceOf(DomainException.class);
        MailProviderCatalog catalog = new MailProviderCatalog();
        assertThat(catalog.supports("OUTLOOK", MailboxConfig.AuthMode.PASSWORD)).isFalse();
        assertThat(catalog.supports("OUTLOOK", MailboxConfig.AuthMode.MICROSOFT_OAUTH2)).isTrue();
        assertThat(catalog.supports("GMAIL", MailboxConfig.AuthMode.GOOGLE_OAUTH2)).isTrue();
        assertThat(catalog.list()).hasSize(15);
        assertThat(catalog.supports("FASTMAIL", MailboxConfig.AuthMode.PASSWORD)).isTrue();
        assertThat(catalog.supports("GMX", MailboxConfig.AuthMode.PASSWORD)).isTrue();
        assertThat(catalog.supports("YANDEX", MailboxConfig.AuthMode.PASSWORD)).isTrue();
    }
}
