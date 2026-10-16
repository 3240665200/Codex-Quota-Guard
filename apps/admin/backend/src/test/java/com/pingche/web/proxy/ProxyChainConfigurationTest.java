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
}
