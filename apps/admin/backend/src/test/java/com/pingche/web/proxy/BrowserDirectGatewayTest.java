package com.pingche.web.proxy;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.pingche.web.auth.DomainException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntFunction;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class BrowserDirectGatewayTest {
    @Test
    void explicitDirectBrowserUsesOnlyFixedTlsEgressWithoutReadingNodesOrDecryptionKeys() {
        var nodes = mock(ProxyNodeRepository.class); var cipher = mock(ProxySecretCipher.class);
        var endpoints = mock(ProxyEndpointPolicy.class); var runner = mock(SingBoxProcessRunner.class);
        var lease = mock(ProxyLease.class); AtomicReference<JsonNode> config = new AtomicReference<>();
        when(runner.open(any(), eq(ManagedProxyGateway.Purpose.BROWSER_LOGIN))).thenAnswer(invocation -> {
            IntFunction<JsonNode> build = invocation.getArgument(0); config.set(build.apply(12345)); return lease;
        });
        var gateway = new SingBoxManagedProxyGateway(nodes, cipher, endpoints, new SingBoxConfiguration(new ObjectMapper()), runner, false);
        assertThat(gateway.open(null, ManagedProxyGateway.Purpose.BROWSER_LOGIN)).isSameAs(lease);
        assertThat(config.get().path("outbounds").get(0).path("type").asString()).isEqualTo("direct");
        assertThat(config.get().path("route").path("rules").get(0).path("domain").toString()).contains("chatgpt.com", "auth.openai.com");
        assertThat(config.get().path("route").path("rules").get(0).path("port").get(0).asInt()).isEqualTo(443);
        assertThat(config.get().path("route").path("rules").get(1).path("action").asString()).isEqualTo("reject");
        verifyNoInteractions(nodes, cipher, endpoints);
        assertThatThrownBy(() -> gateway.open(null)).isInstanceOf(DomainException.class);
        verifyNoInteractions(nodes, cipher, endpoints);
    }
}
