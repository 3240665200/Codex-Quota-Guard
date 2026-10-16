package com.pingche.web.proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.doReturn;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.pingche.web.auth.DomainException;
import com.pingche.web.auth.JwtService;
import com.pingche.web.user.User;
import com.pingche.web.user.UserRepository;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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
    @Autowired ProxySubscriptionService subscriptions;
    @Autowired ProxySecretCipher cipher;
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
        assertThat(subscription.get("allowInsecureTrojan").asBoolean()).isFalse();
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
        UUID account = UUID.randomUUID();
        jdbc.update("insert into official_accounts(id,label,estimated_total_tokens,created_at,updated_at,version) values (?,?,0,current_timestamp,current_timestamp,0)",
                account, "Subscription binding " + account);
        jdbc.update("insert into official_account_connections(official_account_id,requested_account_id,proxy_node_id,authorization_cipher,status,revision,imported_at,version) values (?,?,?,?,'UNVERIFIED',?,current_timestamp,0)",
                account, "synthetic-account", node, "synthetic-unused-cipher", UUID.randomUUID());
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
        long missingVersion = jdbc.queryForObject("select version from managed_proxy_nodes where id = ?", Long.class, node);
        mvc.perform(put("/api/v1/admin/proxy-nodes/" + node + "/enabled").secure(true).header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("enabled", true, "version", missingVersion))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PROXY_NODE_SOURCE_MISSING"));
        assertThat(nodeCipher(node)).isEqualTo(originalCipher);
        assertThat(jdbc.queryForObject("select proxy_node_id from official_account_connections where official_account_id = ?", UUID.class, account)).isEqualTo(node);
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
        String subscriptionCipher = jdbc.queryForObject("select url_cipher from managed_proxy_subscriptions where id = cast(? as uuid)", String.class, id);
        when(fetcher.fetch(URL)).thenThrow(new DomainException("PROXY_SUBSCRIPTION_FAILED", "订阅获取失败。", HttpStatus.BAD_REQUEST));
        mvc.perform(post(BASE + "/" + id + "/refresh").secure(true).header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("version", subscription.get("version").asLong())))).andExpect(status().isBadRequest());
        assertThat(sourceNode(id)).isEqualTo(node);
        assertThat(nodeCipher(node)).isEqualTo(cipher);
        doReturn("a".repeat(1_048_577)).when(fetcher).fetch(URL);
        mvc.perform(post(BASE + "/" + id + "/refresh").secure(true).header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("version", subscription.get("version").asLong())))).andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("select url_cipher from managed_proxy_subscriptions where id = cast(? as uuid)", String.class, id)).isEqualTo(subscriptionCipher);
        assertThat(jdbc.queryForObject("select version from managed_proxy_subscriptions where id = cast(? as uuid)", Long.class, id)).isEqualTo(subscription.get("version").asLong());
        assertThat(nodeCipher(node)).isEqualTo(cipher);
        doReturn("http://one.example.com:80#Duplicate\nhttp://two.example.com:80#Duplicate").when(fetcher).fetch(URL);
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

    @Test
    void changingUrlWaitsForManualRefreshAndRefreshUpdatesConfigurationWithoutChangingNodeId() throws Exception {
        JsonNode subscription = create();
        String id = subscription.get("id").asString();
        UUID node = sourceNode(id);
        String oldCipher = nodeCipher(node);
        String replacement = "https://new-subscription.example.com/list?token=synthetic-new-token";
        String result = mvc.perform(put(BASE + "/" + id).secure(true).header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("label", "Changed URL", "url", replacement, "version", subscription.get("version").asLong()))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("URL_UPDATED"))
                .andReturn().getResponse().getContentAsString();
        assertThat(result).doesNotContain("synthetic-new-token", replacement);
        verify(fetcher, times(1)).fetch(URL);
        verify(fetcher, times(0)).fetch(replacement);
        assertThat(nodeCipher(node)).isEqualTo(oldCipher);
        when(fetcher.fetch(replacement)).thenReturn("socks5://updated:synthetic-updated-password@new-proxy.example.com:1080#Stable");
        refresh(id, json.readTree(result).get("version").asLong());
        assertThat(sourceNode(id)).isEqualTo(node);
        assertThat(nodeCipher(node)).isNotEqualTo(oldCipher);
        assertThat(jdbc.queryForObject("select protocol from managed_proxy_nodes where id = ?", String.class, node)).isEqualTo("SOCKS5");
    }

    @Test
    void inFlightRefreshCannotOverwriteAnEditAndRejectsAnotherRefresh() throws Exception {
        JsonNode subscription = create();
        String id = subscription.get("id").asString();
        long version = subscription.get("version").asLong();
        UUID node = sourceNode(id);
        String originalCipher = nodeCipher(node);
        CountDownLatch downloading = new CountDownLatch(1);
        CountDownLatch completeDownload = new CountDownLatch(1);
        when(fetcher.fetch(URL)).thenAnswer(call -> {
            downloading.countDown();
            assertThat(completeDownload.await(15, TimeUnit.SECONDS)).isTrue();
            return "http://changed.example.com:8181#Stable";
        });
        CompletableFuture<ProxySubscriptionResponse> pending = CompletableFuture.supplyAsync(
                () -> subscriptions.refresh(UUID.fromString(id), version));
        try {
            assertThat(downloading.await(10, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> subscriptions.refresh(UUID.fromString(id), version))
                    .isInstanceOfSatisfying(DomainException.class,
                            failure -> assertThat(failure.getCode()).isEqualTo("PROXY_SUBSCRIPTION_BUSY"));
            mvc.perform(put(BASE + "/" + id).secure(true).header("Authorization", token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(Map.of("label", "Edited during download", "version", version))))
                    .andExpect(status().isOk());
        } finally {
            completeDownload.countDown();
        }
        assertThatThrownBy(() -> pending.get(10, TimeUnit.SECONDS))
                .hasRootCauseInstanceOf(DomainException.class)
                .rootCause().satisfies(failure -> assertThat(((DomainException) failure).getCode())
                        .isEqualTo("PROXY_SUBSCRIPTION_VERSION_CONFLICT"));
        assertThat(nodeCipher(node)).isEqualTo(originalCipher);
        assertThat(jdbc.queryForObject("select label from managed_proxy_subscriptions where id = cast(? as uuid)", String.class, id))
                .isEqualTo("Edited during download");
    }

    @Test
    void deletingASubscriptionDuringDownloadKeepsDetachedNodesAndRejectsTheStaleRefresh() throws Exception {
        JsonNode subscription = create();
        String id = subscription.get("id").asString();
        long version = subscription.get("version").asLong();
        UUID node = sourceNode(id);
        String originalCipher = nodeCipher(node);
        CountDownLatch downloading = new CountDownLatch(1);
        CountDownLatch completeDownload = new CountDownLatch(1);
        when(fetcher.fetch(URL)).thenAnswer(call -> {
            downloading.countDown();
            assertThat(completeDownload.await(15, TimeUnit.SECONDS)).isTrue();
            return "http://changed.example.com:8181#Stable";
        });
        CompletableFuture<ProxySubscriptionResponse> pending = CompletableFuture.supplyAsync(
                () -> subscriptions.refresh(UUID.fromString(id), version));
        try {
            assertThat(downloading.await(10, TimeUnit.SECONDS)).isTrue();
            mvc.perform(delete(BASE + "/" + id).secure(true).header("Authorization", token)
                    .param("version", Long.toString(version))).andExpect(status().isNoContent());
        } finally {
            completeDownload.countDown();
        }
        assertThatThrownBy(() -> pending.get(10, TimeUnit.SECONDS))
                .hasRootCauseInstanceOf(DomainException.class)
                .rootCause().satisfies(failure -> assertThat(((DomainException) failure).getCode())
                        .isEqualTo("PROXY_SUBSCRIPTION_NOT_FOUND"));
        assertThat(jdbc.queryForObject("select count(*) from managed_proxy_subscriptions where id = cast(? as uuid)", Long.class, id))
                .isZero();
        assertThat(jdbc.queryForObject("select count(*) from managed_proxy_nodes where id = ? and source_subscription_id is null", Long.class, node))
                .isEqualTo(1);
        assertThat(nodeCipher(node)).isEqualTo(originalCipher);
    }

    @Test
    void invalidUrlsAndUnsupportedTransportFailBeforeFetchingAndDoNotEchoInput() throws Exception {
        for (String url : new String[] {"http://public.example.com/list?token=synthetic-secret", "https://user:synthetic-secret@public.example.com/list",
                "https://127.0.0.1/list", "https://localhost/list", "https://public.example.com:8443/list", "https://public.example.com/list#synthetic-secret"}) {
            String response = mvc.perform(post(BASE).secure(true).header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(Map.of("label", "Rejected", "url", url))))
                    .andExpect(status().isBadRequest()).andExpect(header().string("Cache-Control", "no-store"))
                    .andReturn().getResponse().getContentAsString();
            assertThat(response).doesNotContain(url, "synthetic-secret");
        }
        mvc.perform(post(BASE).with(http -> { http.setScheme("ftp"); return http; }).header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("label", "Transport", "url", URL))))
                .andExpect(status().isForbidden());
        verifyNoInteractions(fetcher);
    }

    @Test
    void insecureTrojanRequiresExplicitPermissionAndRefreshKeepsThatSubscriptionSetting() throws Exception {
        String body = Base64.getEncoder().encodeToString(insecureTrojan("Stable").getBytes(StandardCharsets.UTF_8));
        when(fetcher.fetch(URL)).thenReturn(body);
        long before = jdbc.queryForObject("select count(*) from managed_proxy_subscriptions", Long.class);
        for (Map<String, Object> request : java.util.List.of(
                Map.<String, Object>of("label", "Default strict", "url", URL),
                Map.<String, Object>of("label", "Explicit strict", "url", URL, "allowInsecureTrojan", false))) {
            String error = mvc.perform(post(BASE).secure(true).header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(request))).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("PROXY_TROJAN_TLS_CONFIRMATION_REQUIRED"))
                    .andExpect(header().string("Cache-Control", "no-store")).andReturn().getResponse().getContentAsString();
            assertThat(error).doesNotContain(URL, "synthetic-private-token", "synthetic-trojan-password", "edge.example.com", "tls.example.com");
        }
        assertThat(jdbc.queryForObject("select count(*) from managed_proxy_subscriptions", Long.class)).isEqualTo(before);
        JsonNode subscription = createAllowed("Allowed Trojan");
        String id = subscription.get("id").asString();
        UUID node = sourceNode(id);
        assertThat(subscription.get("allowInsecureTrojan").asBoolean()).isTrue();
        assertThat(subscription.toString()).doesNotContain("synthetic-trojan-password", "synthetic-private-token", URL);
        assertThat(configuration(node).get("tls").get("insecure").asBoolean()).isTrue();
        assertThat(configuration(node).get("tls").get("server_name").asString()).isEqualTo("tls.example.com");
        subscription = json.readTree(mvc.perform(put(BASE + "/" + id).secure(true).header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("label", "Renamed with permission", "version", subscription.get("version").asLong()))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.allowInsecureTrojan").value(true))
                .andReturn().getResponse().getContentAsString());
        subscription = refresh(id, subscription.get("version").asLong());
        assertThat(sourceNode(id)).isEqualTo(node);
        assertThat(subscription.get("allowInsecureTrojan").asBoolean()).isTrue();
        assertThat(configuration(node).get("tls").get("insecure").asBoolean()).isTrue();
    }

    @Test
    void enablingTrojanPermissionWaitsForManualRefresh() throws Exception {
        JsonNode subscription = create();
        String id = subscription.get("id").asString();
        UUID node = sourceNode(id);
        String originalCipher = nodeCipher(node);
        subscription = updatePermission(subscription, true);
        verify(fetcher, times(1)).fetch(URL);
        assertThat(nodeCipher(node)).isEqualTo(originalCipher);
        when(fetcher.fetch(URL)).thenReturn(insecureTrojan("Stable"));
        subscription = refresh(id, subscription.get("version").asLong());
        assertThat(sourceNode(id)).isEqualTo(node);
        assertThat(configuration(node).get("tls").get("insecure").asBoolean()).isTrue();
        assertThat(subscription.get("allowInsecureTrojan").asBoolean()).isTrue();
    }

    @Test
    void disablingPermissionOrDeletingSourceRevokesOnlyItsInsecureTrojanAndPreservesBindings() throws Exception {
        for (boolean deleteSource : java.util.List.of(false, true)) {
            when(fetcher.fetch(URL)).thenReturn(insecureTrojan("Stable") + "\n"
                    + "trojan://synthetic-verified-password@verified.example.com:443?allowInsecure=0#Verified\n"
                    + "https://standard.example.com:443#Standard");
            JsonNode subscription = createAllowed("Revocation " + deleteSource);
            String id = subscription.get("id").asString();
            UUID unsafe = sourceNode(id);
            UUID verified = sourceNode(id, "Verified");
            UUID standard = sourceNode(id, "Standard");
            for (UUID node : java.util.List.of(unsafe, verified, standard)) recordSyntheticEgress(node);
            UUID account = bindAccount(unsafe);
            String unsafeCipher = nodeCipher(unsafe);
            String verifiedCipher = nodeCipher(verified);
            String standardCipher = nodeCipher(standard);
            when(fetcher.fetch(URL)).thenReturn(insecureTrojan("Stable"));
            JsonNode other = createAllowed("Unaffected " + deleteSource);
            UUID otherNode = sourceNode(other.get("id").asString());
            String otherCipher = nodeCipher(otherNode);
            if (deleteSource) {
                mvc.perform(delete(BASE + "/" + id).secure(true).header("Authorization", token)
                        .param("version", subscription.get("version").asString())).andExpect(status().isNoContent());
                assertThat(jdbc.queryForObject("select count(*) from managed_proxy_subscriptions where id = cast(? as uuid)", Long.class, id)).isZero();
                assertThat(jdbc.queryForObject("select count(*) from managed_proxy_nodes where id = ? and source_subscription_id is null", Long.class, unsafe)).isEqualTo(1);
            } else {
                subscription = updatePermission(subscription, false);
                assertThat(subscription.get("allowInsecureTrojan").asBoolean()).isFalse();
                assertThat(subscription.get("enabledNodeCount").asLong()).isEqualTo(2);
                mvc.perform(post(BASE + "/" + id + "/refresh").secure(true).header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("version", subscription.get("version").asLong()))))
                        .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PROXY_TROJAN_TLS_CONFIRMATION_REQUIRED"));
            }
            assertThat(nodeCipher(unsafe)).isNotEqualTo(unsafeCipher);
            assertThat(configuration(unsafe).get("tls").get("insecure").asBoolean()).isFalse();
            assertThat(configuration(unsafe).get("tls").get("server_name").asString()).isEqualTo("tls.example.com");
            assertThat(jdbc.queryForObject("select enabled from managed_proxy_nodes where id = ?", Boolean.class, unsafe)).isFalse();
            assertThat(jdbc.queryForMap("select egress_ip,country,region,city,tested_at,test_status from managed_proxy_nodes where id = ?", unsafe))
                    .containsEntry("egress_ip", null).containsEntry("country", null).containsEntry("region", null)
                    .containsEntry("city", null).containsEntry("tested_at", null).containsEntry("test_status", "UNTESTED");
            assertThat(jdbc.queryForObject("select proxy_node_id from official_account_connections where official_account_id = ?", UUID.class, account)).isEqualTo(unsafe);
            for (UUID unchanged : java.util.List.of(verified, standard)) {
                assertThat(jdbc.queryForObject("select enabled from managed_proxy_nodes where id = ?", Boolean.class, unchanged)).isTrue();
                assertThat(jdbc.queryForObject("select test_status from managed_proxy_nodes where id = ?", String.class, unchanged)).isEqualTo("CONNECTED");
            }
            assertThat(nodeCipher(verified)).isEqualTo(verifiedCipher);
            assertThat(nodeCipher(standard)).isEqualTo(standardCipher);
            assertThat(nodeCipher(otherNode)).isEqualTo(otherCipher);
            assertThat(configuration(otherNode).get("tls").get("insecure").asBoolean()).isTrue();
            assertThat(jdbc.queryForObject("select enabled from managed_proxy_nodes where id = ?", Boolean.class, otherNode)).isTrue();
            long nodeVersion = jdbc.queryForObject("select version from managed_proxy_nodes where id = ?", Long.class, unsafe);
            mvc.perform(put("/api/v1/admin/proxy-nodes/" + unsafe + "/enabled").secure(true).header("Authorization", token)
                    .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("enabled", true, "version", nodeVersion))))
                    .andExpect(status().isOk());
            assertThat(configuration(unsafe).get("tls").get("insecure").asBoolean()).isFalse();
        }
    }

    @Test
    void decryptionFailureRollsBackPermissionRevocationAndSourceDeletion() throws Exception {
        for (boolean deleteSource : java.util.List.of(false, true)) {
            when(fetcher.fetch(URL)).thenReturn(insecureTrojan("Stable") + "\n" + insecureTrojan("Corrupted"));
            JsonNode subscription = createAllowed("Rollback " + deleteSource);
            String id = subscription.get("id").asString();
            UUID unsafe = sourceNode(id);
            UUID corrupted = sourceNode(id, "Corrupted");
            String unsafeCipher = nodeCipher(unsafe);
            jdbc.update("update managed_proxy_nodes set config_cipher = ? where id = ?", "synthetic-invalid-cipher", corrupted);
            var request = deleteSource ? delete(BASE + "/" + id).param("version", subscription.get("version").asString())
                    : put(BASE + "/" + id).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of(
                            "label", "Must roll back", "version", subscription.get("version").asLong(), "allowInsecureTrojan", false)));
            mvc.perform(request.secure(true).header("Authorization", token)).andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value("PROXY_CREDENTIALS_UNAVAILABLE"));
            assertThat(nodeCipher(unsafe)).isEqualTo(unsafeCipher);
            assertThat(configuration(unsafe).get("tls").get("insecure").asBoolean()).isTrue();
            assertThat(jdbc.queryForObject("select enabled from managed_proxy_nodes where id = ?", Boolean.class, unsafe)).isTrue();
            assertThat(jdbc.queryForObject("select count(*) from managed_proxy_nodes where source_subscription_id = cast(? as uuid)", Long.class, id)).isEqualTo(2);
            assertThat(jdbc.queryForObject("select allow_insecure_trojan from managed_proxy_subscriptions where id = cast(? as uuid)", Boolean.class, id)).isTrue();
            assertThat(jdbc.queryForObject("select version from managed_proxy_subscriptions where id = cast(? as uuid)", Long.class, id))
                    .isEqualTo(subscription.get("version").asLong());
        }
    }

    @Test
    void changingTrojanPermissionDuringRefreshRejectsStalePermissiveDefinitions() throws Exception {
        when(fetcher.fetch(URL)).thenReturn(insecureTrojan("Stable"));
        JsonNode subscription = createAllowed("Concurrent permission");
        String id = subscription.get("id").asString();
        long version = subscription.get("version").asLong();
        UUID node = sourceNode(id);
        CountDownLatch downloading = new CountDownLatch(1);
        CountDownLatch completeDownload = new CountDownLatch(1);
        when(fetcher.fetch(URL)).thenAnswer(call -> {
            downloading.countDown();
            assertThat(completeDownload.await(15, TimeUnit.SECONDS)).isTrue();
            return insecureTrojan("Stable");
        });
        CompletableFuture<ProxySubscriptionResponse> pending = CompletableFuture.supplyAsync(
                () -> subscriptions.refresh(UUID.fromString(id), version));
        String revokedCipher;
        try {
            assertThat(downloading.await(10, TimeUnit.SECONDS)).isTrue();
            updatePermission(subscription, false);
            revokedCipher = nodeCipher(node);
        } finally {
            completeDownload.countDown();
        }
        assertThatThrownBy(() -> pending.get(10, TimeUnit.SECONDS)).hasRootCauseInstanceOf(DomainException.class)
                .rootCause().satisfies(failure -> assertThat(((DomainException) failure).getCode()).isEqualTo("PROXY_SUBSCRIPTION_VERSION_CONFLICT"));
        assertThat(nodeCipher(node)).isEqualTo(revokedCipher);
        assertThat(configuration(node).get("tls").get("insecure").asBoolean()).isFalse();
        assertThat(jdbc.queryForObject("select enabled from managed_proxy_nodes where id = ?", Boolean.class, node)).isFalse();
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
        return sourceNode(id, "Stable");
    }
    private UUID sourceNode(String id, String key) {
        return jdbc.queryForObject("select id from managed_proxy_nodes where source_subscription_id = cast(? as uuid) and source_node_key = ?", UUID.class, id, key);
    }
    private JsonNode createAllowed(String label) throws Exception {
        return json.readTree(mvc.perform(post(BASE).secure(true).header("Authorization", token).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("label", label, "url", URL, "allowInsecureTrojan", true))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.allowInsecureTrojan").value(true))
                .andReturn().getResponse().getContentAsString());
    }
    private JsonNode updatePermission(JsonNode subscription, boolean allowed) throws Exception {
        return json.readTree(mvc.perform(put(BASE + "/" + subscription.get("id").asString()).secure(true).header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("label", subscription.get("label").asString(),
                        "version", subscription.get("version").asLong(), "allowInsecureTrojan", allowed))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.allowInsecureTrojan").value(allowed))
                .andReturn().getResponse().getContentAsString());
    }
    private JsonNode configuration(UUID id) { return json.readTree(cipher.decrypt(nodeCipher(id), id.toString())); }
    private static String insecureTrojan(String label) {
        return "trojan://synthetic-trojan-password@edge.example.com:443?allowInsecure=1&peer=tls.example.com&sni=tls.example.com#" + label;
    }
    private void recordSyntheticEgress(UUID node) {
        jdbc.update("update managed_proxy_nodes set egress_ip = '192.0.2.1', country = 'Synthetic', region = 'Synthetic', city = 'Synthetic', tested_at = current_timestamp, test_status = 'CONNECTED' where id = ?", node);
    }
    private UUID bindAccount(UUID node) {
        UUID account = UUID.randomUUID();
        jdbc.update("insert into official_accounts(id,label,estimated_total_tokens,created_at,updated_at,version) values (?,?,0,current_timestamp,current_timestamp,0)", account, "Trojan binding " + account);
        jdbc.update("insert into official_account_connections(official_account_id,requested_account_id,proxy_node_id,authorization_cipher,status,revision,imported_at,version) values (?,?,?,?,'UNVERIFIED',?,current_timestamp,0)",
                account, "synthetic-account", node, "synthetic-unused-cipher", UUID.randomUUID());
        return account;
    }
    private String nodeCipher(UUID id) { return jdbc.queryForObject("select config_cipher from managed_proxy_nodes where id = ?", String.class, id); }
}
