package com.pingche.web.proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pingche.web.auth.DomainException;
import java.net.InetAddress;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class SingBoxConfigurationTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void rejectsPublicMetadataChangedWithoutAuthenticatedConfiguration() throws Exception {
        ProxyNodeDefinition original = new ProxyNodeParser(json).parse("https://alice:synthetic-password@proxy.example.com:8443");
        InetAddress pinned = InetAddress.getByAddress(new byte[] {8, 8, 8, 8});
        ProxyNodeDefinition alteredHost = new ProxyNodeDefinition(original.label(), original.protocol(), "attacker.example.com",
                original.port(), original.outboundJson(), true);
        ProxyNodeDefinition alteredPort = new ProxyNodeDefinition(original.label(), original.protocol(), original.host(),
                443, original.outboundJson(), true);
        for (ProxyNodeDefinition altered : java.util.List.of(alteredHost, alteredPort)) {
            assertThatThrownBy(() -> new SingBoxConfiguration(json).build(altered, pinned, 12345))
                    .isInstanceOf(DomainException.class).hasMessageNotContaining("synthetic-password");
        }
    }

    @Test
    void exposesOnlyLoopbackAndRoutesOnlyFixedOfficialTlsTargetWithoutDirectFallback() throws Exception {
        ProxyNodeDefinition node = new ProxyNodeParser(json).parse("https://alice:synthetic-password@proxy.example.com:8443");
        JsonNode config = new SingBoxConfiguration(json).build(node, InetAddress.getByAddress(new byte[] {8, 8, 8, 8}), 12345);
        assertThat(config.get("log").get("disabled").asBoolean()).isTrue();
        JsonNode inbound = config.get("inbounds").get(0);
        assertThat(inbound.get("listen").asString()).isEqualTo("127.0.0.1");
        assertThat(inbound.get("listen_port").asInt()).isEqualTo(12345);
        assertThat(inbound.get("set_system_proxy").asBoolean()).isFalse();
        assertThat(config.get("outbounds").size()).isEqualTo(1);
        JsonNode upstream = config.get("outbounds").get(0);
        assertThat(upstream.get("server").asString()).isEqualTo("8.8.8.8");
        assertThat(upstream.get("tls").get("server_name").asString()).isEqualTo("proxy.example.com");
        JsonNode allow = config.get("route").get("rules").get(0);
        assertThat(allow.get("domain").get(0).asString()).isEqualTo("chatgpt.com");
        assertThat(allow.get("domain").size()).isEqualTo(1);
        assertThat(allow.get("port").get(0).asInt()).isEqualTo(443);
        assertThat(config.get("route").get("rules").get(1).get("action").asString()).isEqualTo("reject");
        assertThat(config.toString()).doesNotContain("\"direct\"", "\"selector\"", "\"urltest\"", "experimental");
    }
}
