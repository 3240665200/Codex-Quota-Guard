package com.pingche.web.proxy;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.pingche.web.auth.DomainException;
import java.net.InetAddress;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class ProxyChainConfigurationTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test void exitDialsThroughPinnedFrontAndOnlyExitRoutesOfficialTraffic() throws Exception {
        var parser = new ProxyNodeParser(json);
        var front = parser.parse("https://alice:synthetic-front@front.example.com:8443");
        var exit = parser.parse("https://bob:synthetic-exit@exit.example.com:9443");
        var config = new SingBoxConfiguration(json).build(front, InetAddress.getByName("8.8.8.8"),
                exit, InetAddress.getByName("1.1.1.1"), 12345, ManagedProxyGateway.Purpose.OFFICIAL_SESSION);
        assertThat(config.path("outbounds").size()).isEqualTo(2);
        var first = config.path("outbounds").get(0);
        var last = config.path("outbounds").get(1);
        assertThat(first.path("tag").asString()).isEqualTo("front");
        assertThat(first.path("server").asString()).isEqualTo("8.8.8.8");
        assertThat(first.path("tls").path("server_name").asString()).isEqualTo("front.example.com");
        assertThat(last.path("tag").asString()).isEqualTo("upstream");
        assertThat(last.path("server").asString()).isEqualTo("1.1.1.1");
        assertThat(last.path("tls").path("server_name").asString()).isEqualTo("exit.example.com");
        assertThat(last.path("detour").asString()).isEqualTo("front");
        assertThat(last.has("connect_timeout")).isFalse();
        assertThat(config.path("route").path("rules").get(0).path("outbound").asString()).isEqualTo("upstream");
        assertThat(config.path("route").path("rules").get(1).path("action").asString()).isEqualTo("reject");
        assertThat(config.toString()).doesNotContain("\"direct\"", "\"selector\"", "\"urltest\"");
    }

    @Test void legacyGatewayCannotSilentlyIgnoreARequestedFront() {
        UUID front = UUID.randomUUID(), exit = UUID.randomUUID();
        ManagedProxyGateway gateway = ignored -> { throw new AssertionError("must not open exit directly"); };
        assertThatThrownBy(() -> gateway.open(front, exit, ManagedProxyGateway.Purpose.OFFICIAL_SESSION))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void chainTestsDoNotWriteSingleNodeMetadataAndRateLimitThePair() {
        UUID front = UUID.randomUUID(), exit = UUID.randomUUID();
        ProxyNodeProbe probe = mock(ProxyNodeProbe.class);
        ProxyNodeRepository nodes = mock(ProxyNodeRepository.class);
        ProxyNode node = mock(ProxyNode.class);
        when(node.isEnabled()).thenReturn(true);
        when(nodes.findById(front)).thenReturn(Optional.of(node));
        when(nodes.findById(exit)).thenReturn(Optional.of(node));
        when(probe.test(front, exit)).thenReturn(new ProxyNodeProbe.Result("1.1.1.1", "Country", "Region", "City"));
        var service = new ProxyChainTestService(nodes, probe, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
        var result = service.test(new ProxyChainTestRequest(front, exit));
        assertThat(result.frontProxyNodeId()).isEqualTo(front);
        assertThat(result.proxyNodeId()).isEqualTo(exit);
        assertThat(result.egressIp()).isEqualTo("1.1.1.1");
        verify(nodes, never()).saveAndFlush(any());
        assertThatThrownBy(() -> service.test(new ProxyChainTestRequest(front, exit)))
                .isInstanceOfSatisfying(DomainException.class, error -> assertThat(error.getCode()).isEqualTo("PROXY_TEST_RATE_LIMITED"));
        assertThatThrownBy(() -> service.test(new ProxyChainTestRequest(exit, exit)))
                .isInstanceOfSatisfying(DomainException.class, error -> assertThat(error.getCode()).isEqualTo("PROXY_CHAIN_INVALID"));
        verify(probe, times(1)).test(front, exit);
    }

    @Test void chainTestsBoundConcurrentWorkAndReleaseCapacityAfterCompletion() throws Exception {
        var nodes=mock(ProxyNodeRepository.class); var node=mock(ProxyNode.class); var probe=mock(ProxyNodeProbe.class);
        when(nodes.findById(any())).thenReturn(Optional.of(node));when(node.isEnabled()).thenReturn(true);
        var entered=new java.util.concurrent.CountDownLatch(2);var release=new java.util.concurrent.CountDownLatch(1);
        when(probe.test(any(),any())).thenAnswer(call -> {entered.countDown();release.await(5,java.util.concurrent.TimeUnit.SECONDS);return new ProxyNodeProbe.Result("1.1.1.1",null,null,null);});
        var service=new ProxyChainTestService(nodes,probe,Clock.fixed(Instant.EPOCH,ZoneOffset.UTC));
        var workers=java.util.concurrent.Executors.newFixedThreadPool(2);
        var first=new ProxyChainTestRequest(UUID.randomUUID(),UUID.randomUUID());
        try {
            var one=workers.submit(() -> service.test(first));
            var two=workers.submit(() -> service.test(new ProxyChainTestRequest(UUID.randomUUID(),UUID.randomUUID())));
            assertThat(entered.await(3,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> service.test(first))
                    .isInstanceOfSatisfying(DomainException.class,error -> assertThat(error.getCode()).isEqualTo("PROXY_TEST_BUSY"));
            assertThatThrownBy(() -> service.test(new ProxyChainTestRequest(UUID.randomUUID(),UUID.randomUUID())))
                    .isInstanceOfSatisfying(DomainException.class,error -> assertThat(error.getCode()).isEqualTo("PROXY_CAPACITY_EXCEEDED"));
            release.countDown();one.get(5,java.util.concurrent.TimeUnit.SECONDS);two.get(5,java.util.concurrent.TimeUnit.SECONDS);
            assertThat(service.test(new ProxyChainTestRequest(null,UUID.randomUUID())).connected()).isTrue();
        } finally {release.countDown();workers.shutdownNow();}
    }

    @Test void chainTestsBoundRecentPairsAndRejectMissingAndDisabledNodesBeforeProbing() {
        var nodes=mock(ProxyNodeRepository.class);var node=mock(ProxyNode.class);var probe=mock(ProxyNodeProbe.class);
        when(nodes.findById(any())).thenReturn(Optional.of(node));when(node.isEnabled()).thenReturn(true);
        when(probe.test(any(),any())).thenReturn(new ProxyNodeProbe.Result("1.1.1.1",null,null,null));
        var service=new ProxyChainTestService(nodes,probe,Clock.fixed(Instant.EPOCH,ZoneOffset.UTC));
        for(int i=0;i<256;i++)service.test(new ProxyChainTestRequest(UUID.randomUUID(),UUID.randomUUID()));
        assertThatThrownBy(() -> service.test(new ProxyChainTestRequest(UUID.randomUUID(),UUID.randomUUID())))
                .isInstanceOfSatisfying(DomainException.class,error -> assertThat(error.getCode()).isEqualTo("PROXY_CAPACITY_EXCEEDED"));
        var other=new ProxyChainTestService(nodes,probe,Clock.fixed(Instant.EPOCH,ZoneOffset.UTC));
        when(node.isEnabled()).thenReturn(false);
        assertThatThrownBy(() -> other.test(new ProxyChainTestRequest(null,UUID.randomUUID())))
                .isInstanceOfSatisfying(DomainException.class,error -> assertThat(error.getCode()).isEqualTo("PROXY_NODE_DISABLED"));
        when(nodes.findById(any())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> other.test(new ProxyChainTestRequest(null,UUID.randomUUID())))
                .isInstanceOfSatisfying(DomainException.class,error -> assertThat(error.getCode()).isEqualTo("PROXY_NODE_UNAVAILABLE"));
        verify(probe,times(256)).test(any(),any());
    }
}
