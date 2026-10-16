package com.pingche.web.proxy;

import static org.assertj.core.api.Assertions.*;
import java.net.InetAddress;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class BrowserProxyPurposeTest {
    @Test
    void browserPurposeAddsOnlyFixedOfficialLoginDomainsAndExistingPurposeStaysUnchanged() throws Exception {
        ObjectMapper json = new ObjectMapper();
        var node = new ProxyNodeParser(json).parse("https://alice:synthetic-password@proxy.example.com:8443");
        var config = new SingBoxConfiguration(json);
        var pinned = InetAddress.getByAddress(new byte[]{8,8,8,8});
        var legacy = config.build(node, pinned, 12345);
        var browser = config.build(node, pinned, 12345, ManagedProxyGateway.Purpose.BROWSER_LOGIN);
        var probe = config.build(node, pinned, 12345, ManagedProxyGateway.Purpose.NODE_TEST);
        assertThat(probe.path("route").path("rules").get(0).path("domain").toString()).isEqualTo("[\"ipwho.is\"]");
        assertThat(probe.toString()).doesNotContain("chatgpt.com", "auth.openai.com", "\"direct\"");
        assertThat(legacy.path("route").path("rules").get(0).path("domain").size()).isEqualTo(1);
        assertThat(browser.path("route").path("rules").get(0).path("domain").toString())
                .contains("auth.openai.com", "chatgpt.com", "challenges.cloudflare.com").doesNotContain("google.com", "localhost");
        assertThat(browser.path("route").path("rules").get(1).path("action").asString()).isEqualTo("reject");
        assertThat(browser.toString()).doesNotContain("\"direct\"", "domain_suffix");
    }
}
