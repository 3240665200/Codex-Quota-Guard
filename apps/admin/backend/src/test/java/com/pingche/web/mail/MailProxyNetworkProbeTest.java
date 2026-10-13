package com.pingche.web.mail;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.Socket;
import java.nio.charset.StandardCharsets;
import javax.net.SocketFactory;
import javax.net.ssl.SSLSocket;
import java.util.Properties;
import org.eclipse.angus.mail.util.SocketFetcher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.ObjectMapper;

/** Explicit public DNS/TLS probe only: no LOGIN, mailbox selection, or user credential. */
@EnabledIfEnvironmentVariable(named = "CQG_MAIL_PROXY_PROBE", matches = "1")
class MailProxyNetworkProbeTest {
    @Test
    void realNeteaseGreetingWorksWithCurrentProxyAndStrictHostnameValidation() throws Exception {
        String host = "imap.163.com";
        MailTargetPolicy policy = new MailTargetPolicy(new MailDnsResolver(new ObjectMapper()));
        SocketFactory factory = policy.pinnedSocketFactory(host, 993);
        Properties properties = new Properties();
        properties.put("mail.imaps.socketFactory", factory);
        properties.put("mail.imaps.socketFactory.fallback", "false");
        properties.put("mail.imaps.ssl.checkserveridentity", "true");
        properties.put("mail.imaps.ssl.protocols", "TLSv1.3 TLSv1.2");
        properties.put("mail.imaps.connectiontimeout", "10000");
        properties.put("mail.imaps.timeout", "10000");
        try (Socket socket = SocketFetcher.getSocket(host, 993, properties, "mail.imaps", true)) {
            assertThat(socket).isInstanceOf(SSLSocket.class);
            byte[] greeting = new byte[128];
            int received = socket.getInputStream().read(greeting);
            assertThat(received).isPositive();
            assertThat(new String(greeting, 0, received, StandardCharsets.US_ASCII)).startsWith("* OK");
        } finally { MailTargetPolicy.closeSockets(factory); }
    }
}
