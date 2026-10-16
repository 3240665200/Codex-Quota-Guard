package com.pingche.web.proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pingche.web.auth.JwtService;
import com.pingche.web.auth.DomainException;
import java.net.URI;
import java.util.UUID;
import com.pingche.web.user.User;
import com.pingche.web.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = {
        "MANAGED_PROXY_ENCRYPTION_KEY=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "spring.datasource.url=jdbc:h2:mem:managed-proxy-api;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ManagedProxyApiIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired JwtService jwt;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean ManagedProxyGateway gateway;
    @MockitoBean ProxyNodeProbe probe;
    private final ObjectMapper json = new ObjectMapper();
    private String adminToken;

    @BeforeEach
    void setup() {
        User admin = users.findByEmail("proxy-admin@example.com").orElseGet(() -> users.saveAndFlush(
                User.bootstrapAdministrator("proxy-admin@example.com", "synthetic-hash", "Proxy admin")));
        adminToken = "Bearer " + jwt.create(admin.getId(), admin.getRole().name(), admin.getSessionVersion());
    }

    @Test
    void adminRoutesRequireAuthenticationAndSupportedTransportAndNeverReturnSecrets() throws Exception {
        mvc.perform(get("/api/v1/admin/proxy-nodes").secure(true)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/admin/proxy-nodes").with(http -> { http.setScheme("ftp"); return http; }).header("Authorization", adminToken)
                        .header("X-Forwarded-Proto", "https").contentType(MediaType.APPLICATION_JSON)
                        .content(request("Node", "http://alice:synthetic-secret@proxy.example.com:8080", true)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PROXY_HTTPS_REQUIRED"))
                .andExpect(header().string("Cache-Control", "no-store"));
        String response = mvc.perform(post("/api/v1/admin/proxy-nodes").secure(true).header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(request("Node", "http://alice:synthetic-secret@proxy.example.com:8080", true)))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.hasCredentials").value(true)).andExpect(jsonPath("$.uri").doesNotExist())
                .andExpect(jsonPath("$.configCipher").doesNotExist()).andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain("synthetic-secret", "alice");
        JsonNode node = json.readTree(response);
        String id = node.get("id").asString();
        String cipher = jdbc.queryForObject("select config_cipher from managed_proxy_nodes where id = cast(? as uuid)", String.class, id);
        assertThat(cipher).doesNotContain("synthetic-secret", "alice");
        mvc.perform(put("/api/v1/admin/proxy-nodes/" + id).secure(true).header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"label\":\"Renamed\",\"protocol\":\"HTTP\",\"host\":\"proxy.example.com\",\"port\":8080,\"enabled\":false,\"version\":" + node.get("version").asLong() + "}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(false));
        assertThat(jdbc.queryForObject("select config_cipher from managed_proxy_nodes where id = cast(? as uuid)", String.class, id)).isEqualTo(cipher);
        mvc.perform(delete("/api/v1/admin/proxy-nodes/" + id).secure(true).header("Authorization", adminToken))
                .andExpect(status().isNoContent());
    }

    @Test
    void subscriptionImportIsRetiredAndDoesNotEchoRejectedInputOrFetchAnything() throws Exception {
        long before = nodeCount();
        String response = mvc.perform(post("/api/v1/admin/proxy-nodes/import").secure(true).header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(java.util.Map.of("content",
                                "http://proxy.example.com:8080\nss://synthetic-invalid-secret"))))
                .andExpect(status().isGone()).andExpect(jsonPath("$.code").value("PROXY_IMPORT_RETIRED"))
                .andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain("synthetic-invalid-secret");
        assertThat(nodeCount()).isEqualTo(before);
        mvc.perform(post("/api/v1/admin/proxy-nodes/import").secure(true).header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"https://proxy.example.com:443#Imported\"}"))
                .andExpect(status().isGone());
        verifyNoInteractions(gateway, probe);
    }

    @Test
    void testPersistsActualExitAndConfigurationChangesInvalidateIt() throws Exception {
        JsonNode created = json.readTree(mvc.perform(post("/api/v1/admin/proxy-nodes").secure(true)
                        .header("Authorization", adminToken).contentType(MediaType.APPLICATION_JSON)
                        .content(request("Exit test", "socks5://proxy.example.com:1080", true)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.testStatus").value("UNTESTED"))
                .andReturn().getResponse().getContentAsString());
        UUID id = UUID.fromString(created.get("id").asString());
        when(probe.test(id)).thenReturn(new ProxyNodeProbe.Result("8.8.8.8", "United States", null, null));
        mvc.perform(post("/api/v1/admin/proxy-nodes/" + id + "/test").secure(true).header("Authorization", adminToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.connected").value(true))
                .andExpect(jsonPath("$.egressIp").value("8.8.8.8")).andExpect(jsonPath("$.testedAt").exists());
        assertThat(jdbc.queryForObject("select egress_ip from managed_proxy_nodes where id = ?", String.class, id)).isEqualTo("8.8.8.8");
        long version = jdbc.queryForObject("select version from managed_proxy_nodes where id = ?", Long.class, id);
        mvc.perform(put("/api/v1/admin/proxy-nodes/" + id).secure(true).header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"label\":\"Changed\",\"protocol\":\"SOCKS5\",\"host\":\"other.example.com\",\"port\":1080,\"enabled\":true,\"version\":" + version + "}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.testStatus").value("UNTESTED"))
                .andExpect(jsonPath("$.egressIp").isEmpty()).andExpect(jsonPath("$.testedAt").isEmpty());
    }

    @Test
    void failedTestPersistsFailureAndDoesNotRetainAStaleEgress() throws Exception {
        JsonNode node = json.readTree(mvc.perform(post("/api/v1/admin/proxy-nodes").secure(true).header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(request("Failure", "http://proxy.example.com:8080", true)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        UUID id = UUID.fromString(node.get("id").asString());
        when(probe.test(id)).thenThrow(new DomainException("PROXY_CONNECT_TIMEOUT", "代理测试超时。", HttpStatus.GATEWAY_TIMEOUT));
        mvc.perform(post("/api/v1/admin/proxy-nodes/" + id + "/test").secure(true).header("Authorization", adminToken))
                .andExpect(status().isGatewayTimeout()).andExpect(jsonPath("$.code").value("PROXY_CONNECT_TIMEOUT"));
        assertThat(jdbc.queryForObject("select test_status from managed_proxy_nodes where id = ?", String.class, id)).isEqualTo("FAILED");
        mvc.perform(post("/api/v1/admin/proxy-nodes/" + id + "/test").secure(true).header("Authorization", adminToken))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void chainTestRequiresAdminAndSupportedTransportAndDoesNotOverwriteNodeMetadata() throws Exception {
        UUID front=createNode("Chain front"), exit=createNode("Chain exit");
        String body=json.writeValueAsString(java.util.Map.of("frontProxyNodeId",front,"proxyNodeId",exit));
        String path="/api/v1/admin/proxy-chains/test";
        mvc.perform(post(path).secure(true).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized()).andExpect(header().string("Cache-Control","no-store"));
        User member=users.saveAndFlush(new User(UUID.randomUUID()+"@example.test","unused","Member"));
        String memberToken="Bearer "+jwt.create(member.getId(),member.getRole().name(),member.getSessionVersion());
        mvc.perform(post(path).secure(true).header("Authorization",memberToken).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden()).andExpect(header().string("Cache-Control","no-store"));
        mvc.perform(post(path).with(http -> {http.setScheme("ftp");return http;}).header("Authorization",adminToken).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PROXY_HTTPS_REQUIRED"));
        when(probe.test(front,exit)).thenReturn(new ProxyNodeProbe.Result("1.1.1.1","Country","Region","City"));
        mvc.perform(post(path).secure(true).header("Authorization",adminToken).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
                .andExpect(jsonPath("$.connected").value(true)).andExpect(jsonPath("$.egressIp").value("1.1.1.1"))
                .andExpect(jsonPath("$.frontProxyNodeId").value(front.toString())).andExpect(jsonPath("$.proxyNodeId").value(exit.toString()));
        assertThat(jdbc.queryForObject("select count(*) from managed_proxy_nodes where id in (?,?) and tested_at is null and test_status='UNTESTED'",Integer.class,front,exit)).isEqualTo(2);
        mvc.perform(post(path).secure(true).header("Authorization",adminToken).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value("PROXY_TEST_RATE_LIMITED"));
        mvc.perform(post(path).secure(true).header("Authorization",adminToken).contentType(MediaType.APPLICATION_JSON).content("{\"proxyNodeId\":\""+exit+"\",\"frontProxyNodeId\":\""+exit+"\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PROXY_CHAIN_INVALID"));
        mvc.perform(post(path).secure(true).header("Authorization",adminToken).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }

    private UUID createNode(String label) throws Exception {
        return UUID.fromString(json.readTree(mvc.perform(post("/api/v1/admin/proxy-nodes").secure(true)
                        .header("Authorization",adminToken).contentType(MediaType.APPLICATION_JSON)
                        .content(request(label,"http://proxy.example.com:8080",true)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("id").asString());
    }

    private String request(String label, String uri, boolean enabled) {
        URI parsed = URI.create(uri);
        var fields = new java.util.LinkedHashMap<String, Object>();
        fields.put("label", label); fields.put("protocol", parsed.getScheme().toUpperCase(java.util.Locale.ROOT));
        fields.put("host", parsed.getHost()); fields.put("port", parsed.getPort()); fields.put("enabled", enabled);
        if (parsed.getUserInfo() != null) {
            String[] credentials = parsed.getUserInfo().split(":", 2);
            fields.put("username", credentials[0]); fields.put("password", credentials[1]);
        }
        return json.writeValueAsString(fields);
    }

    private long nodeCount() {
        Long exists = jdbc.queryForObject("select count(*) from information_schema.tables where table_name = 'managed_proxy_nodes'", Long.class);
        return exists == 0 ? 0 : jdbc.queryForObject("select count(*) from managed_proxy_nodes", Long.class);
    }
}
