package com.pingche.web.proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pingche.web.auth.DomainException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class ProxyNodeParserTest {
    private static final String ID = "550e8400-e29b-41d4-a716-446655440000";
    private final ObjectMapper json = new ObjectMapper();
    private final ProxyNodeParser parser = new ProxyNodeParser(json);

    @Test
    void acceptsHttpHttpsAndSocksWithPercentEncodedCredentials() {
        for (String protocol : List.of("http", "https", "socks5")) {
            ProxyNodeDefinition node = parser.parse(protocol + "://alice:p%40ss%3Aword@proxy.example.com:8443#East");
            assertThat(node.protocol()).isEqualTo(protocol.toUpperCase());
            assertThat(node.label()).isEqualTo("East");
            assertThat(node.host()).isEqualTo("proxy.example.com");
            assertThat(node.port()).isEqualTo(8443);
            assertThat(node.hasCredentials()).isTrue();
            JsonNode config = json.readTree(node.outboundJson());
            assertThat(config.get("username").asString()).isEqualTo("alice");
            assertThat(config.get("password").asString()).isEqualTo("p@ss:word");
            assertThat(node.toString()).doesNotContain("alice", "p@ss", "outboundJson");
            if (protocol.equals("https")) {
                assertThat(config.get("tls").get("enabled").asBoolean()).isTrue();
                assertThat(config.get("tls").get("insecure").asBoolean()).isFalse();
            }
        }
    }

    @Test
    void acceptsVlessTlsWebsocketAndRealityVision() {
        ProxyNodeDefinition ws = parser.parse("vless://" + ID
                + "@edge.example.com:443?encryption=none&security=tls&type=ws&sni=tls.example.com&host=cdn.example.com&path=%2Fsocket#WS");
        JsonNode config = json.readTree(ws.outboundJson());
        assertThat(config.get("uuid").asString()).isEqualTo(ID);
        assertThat(config.get("tls").get("server_name").asString()).isEqualTo("tls.example.com");
        assertThat(config.get("transport").get("type").asString()).isEqualTo("ws");
        assertThat(config.get("transport").get("path").asString()).isEqualTo("/socket");
        assertThat(config.get("transport").get("headers").get("Host").asString()).isEqualTo("cdn.example.com");
        ProxyNodeDefinition reality = parser.parse("vless://" + ID
                + "@edge.example.com:443?security=reality&type=tcp&sni=tls.example.com&fp=chrome&pbk=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA&sid=abcdef12&flow=xtls-rprx-vision");
        JsonNode tls = json.readTree(reality.outboundJson()).get("tls");
        assertThat(tls.get("reality").get("enabled").asBoolean()).isTrue();
        assertThat(tls.get("reality").get("short_id").asString()).isEqualTo("abcdef12");
    }

    @Test
    void acceptsVmessAeadJsonTrojanGrpcAndShadowsocksSip002() {
        String vmess = "{\"v\":\"2\",\"ps\":\"VMess\",\"add\":\"edge.example.com\",\"port\":\"443\",\"id\":\""
                + ID + "\",\"aid\":\"0\",\"scy\":\"auto\",\"net\":\"ws\",\"type\":\"none\",\"host\":\"cdn.example.com\",\"path\":\"/ws\",\"tls\":\"tls\",\"sni\":\"tls.example.com\"}";
        assertThat(parser.parse("vmess://" + base64(vmess)).protocol()).isEqualTo("VMESS");
        JsonNode trojan = json.readTree(parser.parse("trojan://secret%40word@edge.example.com:443"
                + "?security=tls&type=grpc&serviceName=Tunnel&sni=tls.example.com").outboundJson());
        assertThat(trojan.get("password").asString()).isEqualTo("secret@word");
        assertThat(trojan.get("transport").get("service_name").asString()).isEqualTo("Tunnel");
        ProxyNodeDefinition ss = parser.parse("ss://" + base64("aes-256-gcm:synthetic-password")
                + "@edge.example.com:8388#SS");
        assertThat(ss.protocol()).isEqualTo("SHADOWSOCKS");
        assertThat(json.readTree(ss.outboundJson()).get("method").asString()).isEqualTo("aes-256-gcm");
        assertThat(parser.parse("ss://aes-128-gcm:synthetic%40password@edge.example.com:8388").hasCredentials()).isTrue();
    }

    @Test
    void decodesUriLinesAndBase64SubscriptionsAtomically() {
        String lines = "https://proxy.example.com:443#HTTP\nss://" + base64("aes-128-gcm:synthetic-password")
                + "@edge.example.com:8388#SS\n";
        assertThat(parser.parseSubscription(lines)).hasSize(2);
        assertThat(parser.parseSubscription(base64(lines))).hasSize(2);
        assertThatThrownBy(() -> parser.parseSubscription(lines + "vless://invalid"))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void rejectsUnsupportedVariantsAmbiguousQueriesAndUntrustedConfigurationsWithoutEchoingInput() {
        List<String> invalid = List.of(
                "http://proxy.example.com:8080?unknown=synthetic-secret",
                "https://proxy.example.com:443/path",
                "http://proxy.example.com:0",
                "http://proxy.example.com:65536",
                "vless://" + ID + "@edge.example.com:443?security=tls&allowInsecure=1",
                "vless://" + ID + "@edge.example.com:443?type=xhttp",
                "vless://" + ID + "@edge.example.com:443?type=tcp&type=ws",
                "vless://" + ID + "@edge.example.com:443?security=reality&pbk=bad",
                "trojan://synthetic-secret@edge.example.com:443?security=none",
                "ss://" + base64("aes-256-gcm:synthetic-secret") + "@edge.example.com:8388?plugin=obfs-local",
                "ss://" + base64("rc4-md5:synthetic-secret") + "@edge.example.com:8388");
        for (String value : invalid) {
            assertThatThrownBy(() -> parser.parse(value)).isInstanceOf(DomainException.class)
                    .hasMessageNotContaining("synthetic-secret").hasMessageNotContaining(value);
        }
        for (String value : List.of("proxies:\n - name: arbitrary\n   type: http", "{\"outbounds\":[{\"type\":\"direct\"}]}")) {
            assertThatThrownBy(() -> parser.parseSubscription(value)).isInstanceOf(DomainException.class);
        }
        assertThatThrownBy(() -> parser.parseSubscription("http://edge.example.com:80\n".repeat(129)))
                .isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> parser.parseSubscription("a".repeat(1_048_577)))
                .isInstanceOf(DomainException.class);
    }

    private static String base64(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
