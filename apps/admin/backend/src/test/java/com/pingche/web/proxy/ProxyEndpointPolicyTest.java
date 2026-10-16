package com.pingche.web.proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pingche.web.auth.DomainException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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

    @ParameterizedTest
    @ValueSource(strings = {"198.18.0.0", "198.18.0.42", "198.19.255.255", "fdfe:dcba:9876::", "fdfe:dcba:9876:0:ffff:ffff:ffff:ffff"})
    void defaultFakeIpAnswersUsePublicDnsAndPinOnlyItsValidatedAddress(String literal) throws Exception {
        InetAddress fake = InetAddress.getByName(literal);
        InetAddress expected = InetAddress.getByName("1.1.1.1");
        AtomicInteger queries = new AtomicInteger();
        ProxyEndpointPolicy policy = new ProxyEndpointPolicy(
                host -> new InetAddress[] {InetAddress.getByName("8.8.8.8"), fake}, host -> {
                    assertThat(host).isEqualTo("subscription.example.com");
                    queries.incrementAndGet();
                    return new InetAddress[] {expected};
                });
        try {
            assertThat(ProxyEndpointPolicy.isPublic(fake)).isFalse();
            assertThat(policy.resolve("subscription.example.com", false)).isEqualTo(expected);
            assertThat(queries).hasValue(1);
        } finally { policy.destroy(); }
    }

    @ParameterizedTest
    @ValueSource(strings = {"198.18.0.42", "198.19.255.255", "fdfe:dcba:9876::38"})
    void literalFakeAddressesNeverReachPublicDnsEvenWithPrivateNodesEnabled(String literal) throws Exception {
        ProxyEndpointPolicy policy = new ProxyEndpointPolicy(
                host -> new InetAddress[] {InetAddress.getByName(literal)}, host -> {
                    throw new AssertionError("Literal Fake-IP must not reach public DNS");
                });
        try {
            assertThatThrownBy(() -> policy.resolve(literal, false)).isInstanceOf(DomainException.class);
            assertThatThrownBy(() -> policy.resolve(literal, true)).isInstanceOf(DomainException.class);
        } finally { policy.destroy(); }
    }

    @ParameterizedTest
    @ValueSource(strings = {"10.0.0.1", "127.0.0.1", "169.254.169.254", "100.64.0.1", "198.17.255.255", "198.20.0.0",
            "fc00::1", "fdfe:dcba:9876:1::1", "fdfe:dcba:9875:ffff::1", "fdfe:dcbb:9876::1"})
    void nonFakePrivateOrReservedAnswersNeverTriggerPublicDns(String literal) throws Exception {
        InetAddress address = InetAddress.getByName(literal);
        ProxyEndpointPolicy policy = new ProxyEndpointPolicy(host -> new InetAddress[] {address}, host -> {
            throw new AssertionError("Ordinary addresses must not reach public DNS");
        });
        ProxyEndpointPolicy mixed = new ProxyEndpointPolicy(
                host -> new InetAddress[] {address, InetAddress.getByName("198.18.0.42")}, host -> {
                    throw new AssertionError("Mixed ordinary private and Fake-IP answers must not reach public DNS");
                });
        try {
            if (ProxyEndpointPolicy.isPublic(address)) {
                assertThat(policy.resolve("subscription.example.com", false)).isEqualTo(address);
                // A public neighbour is ordinary public data, not a special trigger on its own.
            } else {
                assertThatThrownBy(() -> policy.resolve("subscription.example.com", false)).isInstanceOf(DomainException.class);
                assertThatThrownBy(() -> mixed.resolve("subscription.example.com", false)).isInstanceOf(DomainException.class);
                assertThatThrownBy(() -> mixed.resolve("subscription.example.com", true)).isInstanceOf(DomainException.class);
            }
        } finally { policy.destroy(); mixed.destroy(); }
    }

    @ParameterizedTest
    @ValueSource(strings = {"127.0.0.1", "10.0.0.1", "169.254.169.254", "198.18.0.42", "192.0.2.11", "fdfe:dcba:9876::38", "::1"})
    void publicDnsPrivateFakeOrMixedAnswersStayBlocked(String literal) throws Exception {
        ProxyEndpointPolicy policy = new ProxyEndpointPolicy(
                host -> new InetAddress[] {InetAddress.getByName("198.18.0.42")},
                host -> new InetAddress[] {InetAddress.getByName("1.1.1.1"), InetAddress.getByName(literal)});
        try {
            assertThatThrownBy(() -> policy.resolve("subscription.example.com", false)).isInstanceOf(DomainException.class);
            assertThatThrownBy(() -> policy.resolve("subscription.example.com", true)).isInstanceOf(DomainException.class);
        } finally { policy.destroy(); }
    }

    @Test
    void failedLocalResolutionDoesNotStartPublicDns() {
        ProxyEndpointPolicy policy = new ProxyEndpointPolicy(host -> { throw new UnknownHostException(); }, host -> {
            throw new AssertionError("Unknown hosts must not start public DNS");
        });
        try {
            assertThatThrownBy(() -> policy.resolve("subscription.example.com", false)).isInstanceOf(DomainException.class);
        } finally { policy.destroy(); }
    }

    @Test
    void existingFiveSecondDeadlineInterruptsThePublicLookup() throws Exception {
        CountDownLatch cancelled = new CountDownLatch(1);
        ProxyEndpointPolicy policy = new ProxyEndpointPolicy(
                host -> new InetAddress[] {InetAddress.getByName("198.18.0.42")}, host -> {
                    try { new CountDownLatch(1).await(); }
                    catch (InterruptedException interrupted) {
                        cancelled.countDown();
                        Thread.currentThread().interrupt();
                        throw new UnknownHostException();
                    }
                    throw new AssertionError("Lookup should have been cancelled");
                });
        try {
            assertThatThrownBy(() -> policy.resolve("subscription.example.com", false)).isInstanceOf(DomainException.class);
            assertThat(cancelled.await(1, TimeUnit.SECONDS)).isTrue();
        } finally { policy.destroy(); }
    }
}
