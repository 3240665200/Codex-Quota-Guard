package com.pingche.web.proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pingche.web.auth.DomainException;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class SimpleProxyNodeTest {
    private final ObjectMapper json = new ObjectMapper();
    private final ProxyNodeParser parser = new ProxyNodeParser(json);

    @Test
    void createsOnlySimpleProtocolsFromSeparateFieldsWithoutUriEscapingCredentials() {
        for (String protocol : List.of("HTTP", "HTTPS", "SOCKS5")) {
            var request = new ProxyNodeRequest("Node", protocol, "proxy.example.com", 8443,
                    "a:user", "p@ss:word+%", true, null);
            ProxyNodeDefinition result = parser.parse(request, null);
            assertThat(result.protocol()).isEqualTo(protocol);
            assertThat(result.host()).isEqualTo("proxy.example.com");
            assertThat(result.hasCredentials()).isTrue();
            var outbound = json.readTree(result.outboundJson());
            assertThat(outbound.get("username").asString()).isEqualTo("a:user");
            assertThat(outbound.get("password").asString()).isEqualTo("p@ss:word+%");
            if (protocol.equals("HTTPS")) {
                assertThat(outbound.get("tls").get("insecure").asBoolean()).isFalse();
                assertThat(outbound.get("tls").get("server_name").asString()).isEqualTo("proxy.example.com");
            }
            assertThat(request.toString()).doesNotContain("a:user", "p@ss");
        }
    }

    @Test
    void omittedCredentialsPreserveExistingSecretsAndEmptyPairClearsThem() {
        String previous = parser.parse("http://alice:synthetic-secret@old.example.com:8080").outboundJson();
        var retained = parser.parse(new ProxyNodeRequest("Node", "SOCKS5", "new.example.com", 1080,
                null, null, true, 0L), previous);
        assertThat(json.readTree(retained.outboundJson()).get("password").asString()).isEqualTo("synthetic-secret");
        var cleared = parser.parse(new ProxyNodeRequest("Node", "HTTP", "new.example.com", 8080,
                "", "", true, 0L), previous);
        assertThat(cleared.hasCredentials()).isFalse();
        assertThat(cleared.outboundJson()).doesNotContain("username", "password", "synthetic-secret");
    }

    @Test
    void rejectsOtherProtocolsInvalidHostsPortsAndPartialCredentialsWithoutEchoingSecrets() {
        for (var request : List.of(
                new ProxyNodeRequest("Node", "VLESS", "proxy.example.com", 443, null, null, true, null),
                new ProxyNodeRequest("Node", "HTTP", "http://user:secret@example.com", 80, null, null, true, null),
                new ProxyNodeRequest("Node", "HTTP", "example.com/path", 80, null, null, true, null),
                new ProxyNodeRequest("Node", "HTTP", "proxy.example.com", 0, null, null, true, null),
                new ProxyNodeRequest("Node", "HTTP", "proxy.example.com", 65536, null, null, true, null),
                new ProxyNodeRequest("Node", "HTTP", "proxy.example.com", 80, "user", null, true, null),
                new ProxyNodeRequest("Node", "HTTP", "proxy.example.com", 80, "user", "secret\r\n", true, null))) {
            assertThatThrownBy(() -> parser.parse(request, null)).isInstanceOf(DomainException.class)
                    .hasMessageNotContaining("secret");
        }
    }
}
