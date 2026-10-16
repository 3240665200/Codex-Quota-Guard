package com.pingche.web.proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pingche.web.auth.DomainException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class ProxySubscriptionParserTest {
    private final ObjectMapper json = new ObjectMapper();
    private final ProxyNodeParser parser = new ProxyNodeParser(json);

    @Test
    void preservesReservedCharactersInClashBasicProxyCredentials() {
        var definition = parser.parseSubscription("proxies: [{name: Credentials, type: socks5, server: proxy.example.com, port: 1080, username: 'synthetic:user%@', password: 'synthetic:pass%@'}]").get(0);
        var config = json.readTree(definition.outboundJson());
        assertThat(config.get("username").asString()).isEqualTo("synthetic:user%@");
        assertThat(config.get("password").asString()).isEqualTo("synthetic:pass%@");
        for (String scheme : new String[] {"http", "https", "socks5"}) {
            var uri = parser.parse(scheme + "://synthetic%3Auser%25%40:synthetic%3Apass%25%40@proxy.example.com:8443#Credentials");
            assertThat(json.readTree(uri.outboundJson()).get("username").asString()).isEqualTo("synthetic:user%@");
            assertThat(json.readTree(uri.outboundJson()).get("password").asString()).isEqualTo("synthetic:pass%@");
        }
    }

    @Test
    void importsOnlyClashProxyDefinitionsAndMapsSupportedTransportFields() {
        var definitions = parser.parseSubscription("""
                mixed-port: 7890
                proxies:
                  - {name: Basic, type: http, server: proxy.example.com, port: 8443, tls: true, username: user, password: synthetic-password}
                  - name: WS
                    type: vmess
                    server: edge.example.com
                    port: 443
                    uuid: 550e8400-e29b-41d4-a716-446655440000
                    alterId: 0
                    cipher: auto
                    tls: true
                    servername: tls.example.com
                    network: ws
                    ws-opts: {path: /socket, headers: {Host: cdn.example.com}}
                  - {name: Socks, type: socks5, server: proxy.example.com, port: 1080}
                  - {name: SS, type: ss, server: proxy.example.com, port: 8388, cipher: aes-128-gcm, password: synthetic-password}
                  - {name: Trojan, type: trojan, server: proxy.example.com, port: 443, password: synthetic-password, network: grpc, grpc-opts: {grpc-service-name: Tunnel}}
                  - name: Reality
                    type: vless
                    server: proxy.example.com
                    port: 443
                    uuid: 550e8400-e29b-41d4-a716-446655440000
                    tls: true
                    servername: tls.example.com
                    client-fingerprint: chrome
                    flow: xtls-rprx-vision
                    reality-opts: {public-key: AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA, short-id: abcdef12}
                rules: [MATCH,DIRECT]
                proxy-providers: {ignored: {url: 'https://provider.example.com/synthetic-token'}}
                """);
        assertThat(definitions).hasSize(6);
        assertThat(definitions.get(0).protocol()).isEqualTo("HTTPS");
        assertThat(json.readTree(definitions.get(1).outboundJson()).get("transport").get("headers").get("Host").asString())
                .isEqualTo("cdn.example.com");
        assertThat(json.readTree(definitions.get(4).outboundJson()).get("transport").get("service_name").asString()).isEqualTo("Tunnel");
        assertThat(json.readTree(definitions.get(5).outboundJson()).get("tls").get("reality").get("enabled").asBoolean()).isTrue();
    }

    @Test
    void rejectsDuplicateNamesUnsafeYamlAndUnsupportedVariantsAtomicallyWithoutSecrets() {
        for (String value : new String[] {
                "proxies: [{name: Duplicate, type: http, server: proxy.example.com, port: 80}, {name: Duplicate, type: socks5, server: proxy.example.com, port: 1080}]",
                "proxies: [{name: Bad, type: hysteria2, server: proxy.example.com, port: 443, password: synthetic-secret}]",
                "proxies: [{name: Bad, type: http, server: proxy.example.com, port: 443, tls: true, skip-cert-verify: true}]",
                "proxies: [{name: Bad, type: ss, server: proxy.example.com, port: 443, cipher: aes-128-gcm, password: synthetic-secret, plugin: obfs}]",
                "proxies: [{name: Bad, type: http, server: proxy.example.com, port: 80, port: 443}]",
                "proxies: !!java.net.URL ['https://synthetic-secret.example.com']",
                "proxies: &a [*a]",
                "proxies: [".repeat(100) + "]".repeat(100),
                "http://proxy.example.com:80#Duplicate\nsocks5://proxy.example.com:1080#Duplicate"
        }) {
            assertThatThrownBy(() -> parser.parseSubscription(value)).isInstanceOf(DomainException.class)
                    .hasMessageNotContaining("synthetic-secret");
        }
    }
}
