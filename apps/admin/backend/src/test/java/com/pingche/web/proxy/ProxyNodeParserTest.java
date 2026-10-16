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
    void mapsTrojanPeerToSniAndRejectsConflictsWithoutEchoingSecrets() {
        for (String query : List.of("peer=tls.example.com", "sni=tls.example.com&peer=TLS.example.com")) {
            JsonNode tls = json.readTree(parser.parse("trojan://synthetic-secret@edge.example.com:443?" + query).outboundJson()).get("tls");
            assertThat(tls.get("server_name").asString()).isEqualTo("tls.example.com");
            assertThat(tls.get("insecure").asBoolean()).isFalse();
        }
        String conflicting = "trojan://synthetic-secret@edge.example.com:443?peer=peer.example.com&sni=tls.example.com";
        assertThatThrownBy(() -> parser.parse(conflicting)).isInstanceOfSatisfying(DomainException.class, error -> {
            assertThat(error.getCode()).isEqualTo("PROXY_TROJAN_SNI_CONFLICT");
            assertThat(error.getMessage()).doesNotContain("synthetic-secret", "edge.example.com", "peer.example.com", "tls.example.com", conflicting);
        });
        assertThatThrownBy(() -> parser.parseSubscription(base64(conflicting), true))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.getCode()).isEqualTo("PROXY_TROJAN_SNI_CONFLICT"));
    }

    @Test
    void permitsTrojanInsecureOnlyForExplicitlyAllowedSubscriptions() {
        for (String value : List.of("1", "true")) {
            String uri = "trojan://synthetic-secret@edge.example.com:443?allowInsecure=" + value
                    + "&peer=tls.example.com&sni=tls.example.com#Synthetic";
            for (Runnable parse : List.<Runnable>of(() -> parser.parse(uri), () -> parser.parseSubscription(uri),
                    () -> parser.parseSubscription(base64(uri)), () -> parser.parseSubscription(uri, false))) {
                assertThatThrownBy(parse::run).isInstanceOfSatisfying(DomainException.class, error -> {
                    assertThat(error.getCode()).isEqualTo("PROXY_TROJAN_TLS_CONFIRMATION_REQUIRED");
                    assertThat(error.getMessage()).doesNotContain("synthetic-secret", "edge.example.com", "tls.example.com", uri);
                });
            }
            JsonNode config = json.readTree(parser.parseSubscription(base64(uri), true).get(0).outboundJson());
            assertThat(config.get("tls").get("insecure").asBoolean()).isTrue();
            assertThat(config.get("tls").get("server_name").asString()).isEqualTo("tls.example.com");
            assertThat(config.has("peer")).isFalse();
            assertThat(config.has("allowInsecure")).isFalse();
        }
        String lines = java.util.stream.IntStream.range(0, 20).mapToObj(index ->
                "trojan://synthetic-password@edge" + index + ".example.com:443?allowInsecure=1&peer=tls.example.com&sni=tls.example.com#Synthetic" + index)
                .collect(java.util.stream.Collectors.joining("\n"));
        assertThat(parser.parseSubscription(base64(lines), true)).hasSize(20).allSatisfy(node -> {
            JsonNode config = json.readTree(node.outboundJson());
            assertThat(node.protocol()).isEqualTo("TROJAN");
            assertThat(config.get("tls").get("insecure").asBoolean()).isTrue();
        });
    }

    @Test
    void keepsTrojanCertificateVerificationForFalseOrOmittedValuesAndRejectsUnknownValues() {
        for (String query : List.of("", "?allowInsecure=0", "?allowInsecure=false")) {
            String uri = "trojan://synthetic-secret@edge.example.com:443" + query;
            assertThat(json.readTree(parser.parse(uri).outboundJson()).get("tls").get("insecure").asBoolean()).isFalse();
            assertThat(json.readTree(parser.parseSubscription(uri, true).get(0).outboundJson()).get("tls").get("insecure").asBoolean()).isFalse();
        }
        for (String value : List.of("", "yes", "TRUE", "2", "-1", "null")) {
            String uri = "trojan://synthetic-secret@edge.example.com:443?allowInsecure=" + value;
            assertThatThrownBy(() -> parser.parseSubscription(uri, true)).isInstanceOfSatisfying(DomainException.class,
                    error -> assertThat(error.getCode()).isEqualTo("PROXY_NODE_INVALID"));
        }
    }

    @Test
    void trojanPermissionDoesNotRelaxOtherProtocolsOrClashTlsRules() {
        for (String invalid : List.of(
                "vless://" + ID + "@edge.example.com:443?security=tls&allowInsecure=1",
                "vless://" + ID + "@edge.example.com:443?security=tls&peer=tls.example.com",
                "vmess://" + base64("{\"add\":\"edge.example.com\",\"port\":443,\"id\":\"" + ID + "\",\"allowInsecure\":1}"),
                "trojan://synthetic-secret@edge.example.com:443?security=none&allowInsecure=1",
                "trojan://synthetic-secret@edge.example.com:443?allowInsecure=1&allowInsecure=0",
                "proxies: [{name: Unsafe, type: trojan, server: edge.example.com, port: 443, password: synthetic-secret, skip-cert-verify: true}]",
                "proxies: [{name: Unsafe, type: http, server: edge.example.com, port: 443, tls: true, skip-cert-verify: true}]")) {
            assertThatThrownBy(() -> parser.parseSubscription(invalid, true)).isInstanceOf(DomainException.class)
                    .hasMessageNotContaining("synthetic-secret").hasMessageNotContaining("edge.example.com");
        }
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
