package com.pingche.web.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.pingche.web.auth.DomainException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import javax.net.SocketFactory;
import org.junit.jupiter.api.Test;

class MailTargetPolicyDnsTest {
    private final MailDnsResolver resolver = mock(MailDnsResolver.class);
    private final MailTargetPolicy policy = new MailTargetPolicy(resolver);

    @Test
    void publicResolverResultIsPinnedWithoutOpeningAnyNetwork() throws Exception {
        when(resolver.resolve("imap.example.com")).thenReturn(new InetAddress[] {ip("8.8.8.8")});
        SocketFactory factory = policy.pinnedSocketFactory("imap.example.com", 993);
        assertThat(factory).isInstanceOf(MailTargetPolicy.PinnedSocketFactory.class);
        verify(resolver).resolve("imap.example.com");
        try (Socket socket = factory.createSocket()) {
            assertThatThrownBy(() -> socket.connect(new InetSocketAddress("127.0.0.1", 12345), 1))
                    .isInstanceOf(java.io.IOException.class);
            assertThat(socket.isConnected()).isFalse();
        }
        MailTargetPolicy.closeSockets(factory);
    }

    @Test
    void privateFakeMixedAndEmptyAnswersStillFailClosed() throws Exception {
        for (String denied : new String[] {"127.0.0.1", "10.0.0.1", "169.254.169.254", "198.18.0.68", "198.19.255.255", "::1", "fd00::1"}) {
            when(resolver.resolve("imap.example.com")).thenReturn(new InetAddress[] {ip("8.8.8.8"), ip(denied)});
            assertThatThrownBy(() -> policy.pinnedSocketFactory("imap.example.com", 993))
                    .isInstanceOf(DomainException.class);
        }
        when(resolver.resolve("imap.example.com")).thenReturn(new InetAddress[0]);
        assertThatThrownBy(() -> policy.pinnedSocketFactory("imap.example.com", 993))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void invalidHostnameIsRejectedBeforeAnyResolver() {
        assertThatThrownBy(() -> policy.pinnedSocketFactory("localhost", 993)).isInstanceOf(DomainException.class);
        verifyNoInteractions(resolver);
    }

    private static InetAddress ip(String literal) throws Exception { return InetAddress.getByName(literal); }
}
