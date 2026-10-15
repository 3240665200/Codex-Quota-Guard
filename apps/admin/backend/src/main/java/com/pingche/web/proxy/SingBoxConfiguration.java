package com.pingche.web.proxy;

import com.pingche.web.auth.DomainException;
import java.net.InetAddress;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@Component
public class SingBoxConfiguration {
    private final ObjectMapper json;
    public SingBoxConfiguration(ObjectMapper json) { this.json = json; }

    JsonNode build(ProxyNodeDefinition node, InetAddress pinned, int port) {
        ObjectNode upstream = (ObjectNode) json.readTree(node.outboundJson());
        JsonNode authenticatedHost = upstream.get("server");
        JsonNode authenticatedPort = upstream.get("server_port");
        if (authenticatedHost == null || !authenticatedHost.isString()
                || !node.host().equals(authenticatedHost.asString())
                || authenticatedPort == null || !authenticatedPort.isIntegralNumber()
                || !Integer.toString(node.port()).equals(authenticatedPort.toString())) {
            throw new DomainException("PROXY_CREDENTIALS_UNAVAILABLE", "代理节点配置完整性校验失败。", HttpStatus.SERVICE_UNAVAILABLE);
        }
        upstream.put("server", pinned.getHostAddress()); upstream.put("tag", "upstream");
        upstream.put("connect_timeout", "10s");
        return json.valueToTree(Map.of(
                "log", Map.of("disabled", true),
                "inbounds", List.of(Map.of("type", "http", "tag", "local", "listen", "127.0.0.1",
                        "listen_port", port, "set_system_proxy", false)),
                "outbounds", List.of(upstream),
                "route", Map.of("rules", List.of(
                        Map.of("domain", List.of("chatgpt.com"), "port", List.of(443), "network", List.of("tcp"),
                                "action", "route", "outbound", "upstream"),
                        Map.of("action", "reject")))));
    }
}
