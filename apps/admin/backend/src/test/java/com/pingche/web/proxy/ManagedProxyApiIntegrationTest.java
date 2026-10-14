package com.pingche.web.proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pingche.web.auth.JwtService;
import com.pingche.web.user.User;
import com.pingche.web.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
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
    private final ObjectMapper json = new ObjectMapper();
    private String adminToken;

    @BeforeEach
    void setup() {
        User admin = users.findByEmail("proxy-admin@example.com").orElseGet(() -> users.saveAndFlush(
                User.bootstrapAdministrator("proxy-admin@example.com", "synthetic-hash", "Proxy admin")));
        adminToken = "Bearer " + jwt.create(admin.getId(), admin.getRole().name(), admin.getSessionVersion());
    }

    @Test
    void adminRoutesRequireAuthenticationHttpsAndNeverReturnSecrets() throws Exception {
        mvc.perform(get("/api/v1/admin/proxy-nodes").secure(true)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/admin/proxy-nodes").header("Authorization", adminToken)
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
                        .contentType(MediaType.APPLICATION_JSON).content("{\"label\":\"Renamed\",\"uri\":\"\",\"enabled\":false,\"version\":" + node.get("version").asLong() + "}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(false));
        assertThat(jdbc.queryForObject("select config_cipher from managed_proxy_nodes where id = cast(? as uuid)", String.class, id)).isEqualTo(cipher);
        mvc.perform(delete("/api/v1/admin/proxy-nodes/" + id).secure(true).header("Authorization", adminToken))
                .andExpect(status().isNoContent());
    }

    @Test
    void subscriptionImportIsAtomicAndDoesNotEchoRejectedInput() throws Exception {
        long before = nodeCount();
        String response = mvc.perform(post("/api/v1/admin/proxy-nodes/import").secure(true).header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(java.util.Map.of("content",
                                "http://proxy.example.com:8080\nss://synthetic-invalid-secret"))))
                .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain("synthetic-invalid-secret");
        assertThat(nodeCount()).isEqualTo(before);
        mvc.perform(post("/api/v1/admin/proxy-nodes/import").secure(true).header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"https://proxy.example.com:443#Imported\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.importedCount").value(1));
    }

    private String request(String label, String uri, boolean enabled) {
        return json.writeValueAsString(java.util.Map.of("label", label, "uri", uri, "enabled", enabled));
    }

    private long nodeCount() {
        Long exists = jdbc.queryForObject("select count(*) from information_schema.tables where table_name = 'managed_proxy_nodes'", Long.class);
        return exists == 0 ? 0 : jdbc.queryForObject("select count(*) from managed_proxy_nodes", Long.class);
    }
}
