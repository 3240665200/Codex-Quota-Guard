package com.pingche.web.proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pingche.web.auth.DomainException;
import java.net.InetAddress;
import org.junit.jupiter.api.Test;

class ProxyEndpointPolicyTest {
    @Test
    void rejectsMixedPublicPrivateDnsAnswersAndPinsValidatedAddress() throws Exception {
        InetAddress publicAddress = InetAddress.getByAddress(new byte[] {8, 8, 8, 8});
        InetAddress privateAddress = InetAddress.getByAddress(new byte[] {127, 0, 0, 1});
        ProxyEndpointPolicy safe = new ProxyEndpointPolicy(host -> new InetAddress[] {publicAddress});
        assertThat(safe.resolve("proxy.example.com", false)).isEqualTo(publicAddress);
        ProxyEndpointPolicy mixed = new ProxyEndpointPolicy(host -> new InetAddress[] {publicAddress, privateAddress});
        assertThatThrownBy(() -> mixed.resolve("proxy.example.com", false)).isInstanceOf(DomainException.class);
        assertThat(mixed.resolve("proxy.example.com", true)).isEqualTo(publicAddress);
    }

    @Test
    void privateNodesNeedExplicitOperatorSettingAndUnsafeAddressesStayRejected() throws Exception {
        for (String literal : new String[] {"127.0.0.1", "10.0.0.1", "169.254.169.254", "100.64.0.1", "::1", "fc00::1"}) {
            InetAddress address = InetAddress.getByName(literal);
            ProxyEndpointPolicy policy = new ProxyEndpointPolicy(host -> new InetAddress[] {address});
            assertThatThrownBy(() -> policy.resolve("proxy.example.com", false)).isInstanceOf(DomainException.class);
        }
        ProxyEndpointPolicy loopback = new ProxyEndpointPolicy(host -> new InetAddress[] {InetAddress.getByName("127.0.0.1")});
        assertThat(loopback.resolve("localhost", true).isLoopbackAddress()).isTrue();
        for (String literal : new String[] {"0.0.0.0", "224.0.0.1", "::"}) {
            ProxyEndpointPolicy unsafe = new ProxyEndpointPolicy(host -> new InetAddress[] {InetAddress.getByName(literal)});
            assertThatThrownBy(() -> unsafe.resolve("proxy.example.com", true)).isInstanceOf(DomainException.class);
        }
    }
}
