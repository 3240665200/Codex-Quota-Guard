package com.pingche.web.proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.pingche.web.auth.DomainException;
import com.pingche.web.auth.JwtService;
import com.pingche.web.user.User;
import com.pingche.web.user.UserRepository;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = {
        "MANAGED_PROXY_ENCRYPTION_KEY=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "spring.datasource.url=jdbc:h2:mem:proxy-subscription-api;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ProxySubscriptionApiIntegrationTest {
    private static final String BASE = "/api/v1/admin/proxy-subscriptions";
    private static final String URL = "https://subscription.example.com/list?token=synthetic-private-token";
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired JwtService jwt;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean ProxySubscriptionFetcher fetcher;
    @MockitoBean ManagedProxyGateway gateway;
    private final ObjectMapper json = new ObjectMapper();
    private String token;

    @BeforeEach
    void setup() {
        User admin = users.findByEmail("subscription-admin@example.com").orElseGet(() -> users.saveAndFlush(
                User.bootstrapAdministrator("subscription-admin@example.com", "synthetic-hash", "Subscription admin")));
        token = "Bearer " + jwt.create(admin.getId(), admin.getRole().name(), admin.getSessionVersion());
        when(fetcher.fetch(URL)).thenReturn("http://alice:synthetic-node-secret@proxy.example.com:8080#Stable");
    }

    @Test
    void requiresAdminAndNeverLeaksSubscriptionUrlOrCredentials() throws Exception {
        mvc.perform(get(BASE).secure(true)).andExpect(status().isUnauthorized()).andExpect(header().string("Cache-Control", "no-store"));
        User user = users.findByEmail("subscription-user@example.com").orElseGet(() -> users.saveAndFlush(
                new User("subscription-user@example.com", "synthetic-hash", "Member")));
        mvc.perform(get(BASE).secure(true).header("Authorization", "Bearer " + jwt.create(user.getId(), user.getRole().name(), user.getSessionVersion())))
                .andExpect(status().isForbidden());
        verifyNoInteractions(fetcher);
        var subscription = create();
        String id = subscription.get("id").asString();
        assertThat(subscription.toString()).doesNotContain("synthetic-private-token", "synthetic-node-secret", "alice", URL, "urlCipher");
        assertThat(subscription.get("hasUrl").asBoolean()).isTrue();
        assertThat(subscription.get("sourceHost").asString()).isEqualTo("subscription.example.com");
        String encrypted = jdbc.queryForObject("select url_cipher from managed_proxy_subscriptions where id = cast(? as uuid)", String.class, id);
        assertThat(encrypted).doesNotContain("synthetic-private-token", "https://");
        mvc.perform(get(BASE).secure(true).header("Authorization", token)).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"));
    }

    @Test
    void refreshPreservesIdentityAndManualDisableAndMissingNodesNeverDeleteBindings() throws Exception {
        JsonNode subscription = create();
        String id = subscription.get("id").asString();
        UUID node = sourceNode(id);
        String originalCipher = nodeCipher(node);
        long nodeVersion = jdbc.queryForObject("select version from managed_proxy_nodes where id = ?", Long.class, node);
        mvc.perform(put("/api/v1/admin/proxy-nodes/" + node).secure(true).header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"label\":\"Wrong\",\"protocol\":\"SOCKS5\",\"host\":\"proxy.example.com\",\"port\":1080,\"enabled\":false,\"version\":" + nodeVersion + "}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PROXY_NODE_SUBSCRIPTION_MANAGED"));
        mvc.perform(put("/api/v1/admin/proxy-nodes/" + node + "/enabled").secure(true).header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("enabled", false, "version", nodeVersion))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(false));
        subscription = refresh(id, subscription.get("version").asLong());
        assertThat(sourceNode(id)).isEqualTo(node);
        assertThat(nodeCipher(node)).isEqualTo(originalCipher);
        assertThat(jdbc.queryForObject("select enabled from managed_proxy_nodes where id = ?", Boolean.class, node)).isFalse();
        when(fetcher.fetch(URL)).thenReturn("socks5://other.example.com:1080#New");
        subscription = refresh(id, subscription.get("version").asLong());
        assertThat(jdbc.queryForObject("select source_missing from managed_proxy_nodes where id = ?", Boolean.class, node)).isTrue();
        assertThat(nodeCipher(node)).isEqualTo(originalCipher);
        when(fetcher.fetch(URL)).thenReturn("http://alice:synthetic-node-secret@proxy.example.com:8080#Stable");
        subscription = refresh(id, subscription.get("version").asLong());
        assertThat(jdbc.queryForObject("select source_missing from managed_proxy_nodes where id = ?", Boolean.class, node)).isFalse();
        assertThat(jdbc.queryForObject("select enabled from managed_proxy_nodes where id = ?", Boolean.class, node)).isFalse();
        mvc.perform(delete(BASE + "/" + id).secure(true).header("Authorization", token).param("version", subscription.get("version").asString()))
                .andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("select count(*) from managed_proxy_nodes where id = ? and source_subscription_id is null", Long.class, node)).isEqualTo(1);
        assertThat(nodeCipher(node)).isEqualTo(originalCipher);
    }

    @Test
    void failureAndDuplicateNamesLeavePreviousNodesCredentialsAndUrlUnchanged() throws Exception {
        JsonNode subscription = create();
        String id = subscription.get("id").asString();
        UUID node = sourceNode(id);
        String cipher = nodeCipher(node);
        when(fetcher.fetch(URL)).thenThrow(new DomainException("PROXY_SUBSCRIPTION_FAILED", "订阅获取失败。", HttpStatus.BAD_REQUEST));
        mvc.perform(post(BASE + "/" + id + "/refresh").secure(true).header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("version", subscription.get("version").asLong())))).andExpect(status().isBadRequest());
        assertThat(sourceNode(id)).isEqualTo(node);
        assertThat(nodeCipher(node)).isEqualTo(cipher);
        when(fetcher.fetch(URL)).thenReturn("http://one.example.com:80#Duplicate\nhttp://two.example.com:80#Duplicate");
        mvc.perform(post(BASE + "/" + id + "/refresh").secure(true).header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("version", subscription.get("version").asLong())))).andExpect(status().isBadRequest());
        assertThat(sourceNode(id)).isEqualTo(node);
        assertThat(nodeCipher(node)).isEqualTo(cipher);
    }

    @Test
    void editsUseOptimisticVersionAndUrlRemainsWriteOnly() throws Exception {
        JsonNode subscription = create();
        String id = subscription.get("id").asString();
        String body = json.writeValueAsString(Map.of("label", "Renamed", "version", subscription.get("version").asLong()));
        mvc.perform(put(BASE + "/" + id).secure(true).header("Authorization", token).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.label").value("Renamed")).andExpect(jsonPath("$.url").doesNotExist());
        mvc.perform(put(BASE + "/" + id).secure(true).header("Authorization", token).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict());
    }

    private JsonNode create() throws Exception {
        String result = mvc.perform(post(BASE).secure(true).header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("label", "Synthetic subscription", "url", URL))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.nodeCount").value(1)).andReturn().getResponse().getContentAsString();
        return json.readTree(result);
    }
    private JsonNode refresh(String id, long version) throws Exception {
        return json.readTree(mvc.perform(post(BASE + "/" + id + "/refresh").secure(true).header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("version", version))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }
    private UUID sourceNode(String id) {
        return jdbc.queryForObject("select id from managed_proxy_nodes where source_subscription_id = cast(? as uuid) and source_node_key = 'Stable'", UUID.class, id);
    }
    private String nodeCipher(UUID id) { return jdbc.queryForObject("select config_cipher from managed_proxy_nodes where id = ?", String.class, id); }
}
