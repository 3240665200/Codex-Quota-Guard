package com.codexquotaguard.backend.controlplane;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.codexquotaguard.backend.usage.CodexUsageEvent;
import com.codexquotaguard.backend.usage.CodexUsageEventSource;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class HttpControlPlaneGatewayTest {

    @Test
    void readsAuthenticatedPersonalStatisticsInUsdWithoutUserSuppliedIdentity() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/client/usage/analytics", exchange -> {
            assertThat(exchange.getRequestMethod()).isEqualTo("GET");
            assertThat(exchange.getRequestURI().getQuery()).isEqualTo("days=7");
            assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer session-access");
            writeJson(exchange, """
                    {"from":"2026-10-03T00:00:00Z","to":"2026-10-10T00:00:00Z",
                     "currency":"USD","timezone":"UTC","granularity":"DAY","trend":[],
                     "summary":{"eventCount":1,"totalTokens":1,"inputTokens":1,"cachedInputTokens":0,
                     "cacheWriteInputTokens":0,"outputTokens":0,"reasoningOutputTokens":null,
                     "breakdownEventCount":1,"costedEventCount":1,"estimatedCostUsd":0.000000000001,
                     "cacheHitRatePercent":0}}
                    """);
        });
        server.start();
        try {
            var gateway = gateway(server);
            var result = gateway.usageAnalytics("session-access", 7);
            assertThat(result.currency()).isEqualTo("USD");
            assertThat(result.summary().estimatedCostUsd()).isEqualByComparingTo("0.000000000001");
            assertThat(result.summary().reasoningOutputTokens()).isNull();
            for (int days : new int[] {0, 2, 31}) {
                assertThatThrownBy(() -> gateway.usageAnalytics("session-access", days))
                        .isInstanceOf(ControlPlaneException.class);
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    void readsOnlyTheAuthenticatedUsersPaginatedLedgerFromAFixedEndpoint() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/client/usage/events", exchange -> {
            assertThat(exchange.getRequestMethod()).isEqualTo("GET");
            assertThat(exchange.getRequestURI().getQuery())
                    .isEqualTo("page=2&size=10&includeMetadata=true&includeTokenBreakdown=true");
            assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer session-access");
            writeJson(exchange, """
                    {"items":[
                    {"id":"%s","occurredAt":"2026-10-09T00:00:00Z",
                    "receivedAt":"2026-10-09T00:00:01Z","totalTokens":12500,"source":"TOKEN_USAGE_RECORD",
                    "model":"gpt-6.1-sol","reasoningEffort":"max","inputTokens":10000,"outputTokens":2500,
                    "cachedInputTokens":5000,"cacheWriteInputTokens":0,"reasoningOutputTokens":1000,
                    "estimatedCostUsd":0.025,"costStatus":"PRICED"},
                    {"id":"%s","occurredAt":"2026-10-09T00:01:00Z",
                    "receivedAt":"2026-10-09T00:01:01Z","totalTokens":500,"source":"TOKEN_USAGE_RECORD",
                    "model":"%s","reasoningEffort":"credential raw log"}],
                    "page":2,"size":10,"totalElements":21,"totalPages":3}
                    """.formatted("a".repeat(64), "b".repeat(64), "x".repeat(97)));
        });
        server.start();
        try {
            var gateway = gateway(server);
            var result = gateway.usageEvents("session-access", 2, 10);
            assertThat(result.items().get(0).totalTokens()).isEqualTo(12500);
            assertThat(result.items().get(0).model()).isEqualTo("gpt-6.1-sol");
            assertThat(result.items().get(0).reasoningEffort()).isEqualTo("max");
            assertThat(result.items().get(0).inputTokens()).isEqualTo(10000);
            assertThat(result.items().get(0).estimatedCostUsd()).isEqualByComparingTo("0.025");
            assertThat(result.items().get(1).totalTokens()).isEqualTo(500);
            assertThat(result.items().get(1).model()).isNull();
            assertThat(result.items().get(1).reasoningEffort()).isNull();
            assertThat(result.toString()).doesNotContain("credential raw log", "x".repeat(97));
            assertThat(result.totalElements()).isEqualTo(21);
            assertThatThrownBy(() -> gateway.usageEvents("session-access", -1, 10))
                    .isInstanceOf(ControlPlaneException.class);
            assertThatThrownBy(() -> gateway.usageEvents("session-access", 0, 101))
                    .isInstanceOf(ControlPlaneException.class);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void acceptsLedgerPagesFromOlderControlPlanesWithoutMetadata() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/client/usage/events", exchange -> writeJson(exchange, """
                {"items":[{"id":"%s","occurredAt":"2026-10-09T00:00:00Z",
                "receivedAt":"2026-10-09T00:00:01Z","totalTokens":12500,"source":"TOKEN_USAGE_RECORD"}],
                "page":0,"size":10,"totalElements":1,"totalPages":1}
                """.formatted("a".repeat(64))));
        server.start();
        try {
            var item = gateway(server).usageEvents("session-access", 0, 10).items().get(0);
            assertThat(item.model()).isNull();
            assertThat(item.reasoningEffort()).isNull();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void enrollsADeviceWithoutReturningMeterTokenToTheRenderer() throws Exception {
        UUID deviceId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/client/meter/enroll", exchange -> {
            assertThat(exchange.getRequestMethod()).isEqualTo("POST");
            assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer session-access");
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(body).contains(deviceId.toString()).doesNotContain("deviceToken");
            writeJson(exchange, """
                    {"deviceId":"%s","deviceToken":"%s","expiresAt":"2026-11-06T00:00:00Z","userId":"%s"}
                    """.formatted(deviceId, "A".repeat(43), userId));
        });
        server.start();
        try {
            var enrollment = gateway(server).enrollMeter("session-access", deviceId);
            assertThat(enrollment.deviceToken()).hasSize(43);
            assertThat(enrollment.userId()).isEqualTo(userId);
            assertThat(enrollment.toString()).doesNotContain(enrollment.deviceToken());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void sendsOnlySafeOfficialQuotaSummaryToCentralBackend() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/client/official-quota", exchange -> {
            assertThat(exchange.getRequestMethod()).isEqualTo("PUT");
            assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer session-access");
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(body).contains("windowDurationMins", "usedPercent", "fetchedAt");
            assertThat(body).doesNotContain("access_token", "refresh_token", "credential");
            writeJson(exchange, "{}");
        });
        server.start();
        try {
            gateway(server).reportOfficialQuota("session-access", new OfficialCodexRateLimits(
                    "plus", List.of(new OfficialCodexRateLimitWindow("codex", "primary", 300,
                    25.5, "2026-10-06T05:00:00Z")), "2026-10-06T00:00:00Z"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void acceptsTheCurrentCentralLoginUserContract() throws Exception {
        UUID userId = UUID.randomUUID();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/auth/login", exchange -> {
            assertThat(exchange.getRequestMethod()).isEqualTo("POST");
            writeJson(exchange, """
                    {"accessToken":"test-access","tokenType":"Bearer","expiresIn":900,
                     "refreshToken":"test-refresh","refreshExpiresIn":86400,
                     "user":{"id":"%s","email":"alice@example.com","displayName":"Alice",
                       "role":"USER","status":"ACTIVE","teamId":null,
                       "fiveHourQuotaPercent":null,"weeklyQuotaPercent":null,
                       "fiveHourTokenLimit":1000,"weeklyTokenLimit":10000,
                       "createdAt":"2026-10-06T00:00:00Z","updatedAt":"2026-10-06T00:00:00Z"}}
                    """.formatted(userId));
        });
        server.start();
        try {
            var session = gateway(server).login("alice@example.com", "test-password");

            assertThat(session.user().id()).isEqualTo(userId);
            assertThat(session.user().email()).isEqualTo("alice@example.com");
            assertThat(session.toString()).doesNotContain("test-access", "test-refresh");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void permitsHttpAndHttpsOrigins() {
        assertThat(HttpControlPlaneGateway.normalizeBaseUri("https://control.example.com:8443"))
                .isEqualTo(URI.create("https://control.example.com:8443/"));
        assertThat(HttpControlPlaneGateway.normalizeBaseUri("http://127.0.0.1:8080"))
                .isEqualTo(URI.create("http://127.0.0.1:8080/"));
        assertThat(HttpControlPlaneGateway.normalizeBaseUri("http://localhost:8080/"))
                .isEqualTo(URI.create("http://localhost:8080/"));
    }

    @Test
    void rejectsCredentialsPathsAndNonHttpSchemes() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> HttpControlPlaneGateway.normalizeBaseUri("https://user:secret@control.example.com"));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> HttpControlPlaneGateway.normalizeBaseUri("https://control.example.com/api"));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> HttpControlPlaneGateway.normalizeBaseUri("file:///C:/secret"));
    }

    @Test
    void permitsHttpForLanPublicAddressesAndHostnamesWithoutApproval() {
        assertThat(HttpControlPlaneGateway.normalizeBaseUri("http://192.168.0.102:8080"))
                .isEqualTo(URI.create("http://192.168.0.102:8080/"));
        assertThat(HttpControlPlaneGateway.normalizeBaseUri("http://10.0.0.5:8080"))
                .isEqualTo(URI.create("http://10.0.0.5:8080/"));
        assertThat(HttpControlPlaneGateway.normalizeBaseUri("http://149.88.90.196:8080"))
                .isEqualTo(URI.create("http://149.88.90.196:8080/"));
        assertThat(HttpControlPlaneGateway.normalizeBaseUri("http://example.com:8080"))
                .isEqualTo(URI.create("http://example.com:8080/"));
        assertThat(HttpControlPlaneGateway.normalizeBaseUri("http://0.0.0.0:8080"))
                .isEqualTo(URI.create("http://127.0.0.1:8080/"));
    }

    @Test
    void acceptsLegacyQuotaWithoutTeamMembersWhenTheServerIgnoresTheOptInParameter() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        UUID teamId = UUID.randomUUID();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/client/quota/overview", exchange -> {
            assertThat(exchange.getRequestMethod()).isEqualTo("GET");
            assertThat(exchange.getRequestURI().toString())
                    .isEqualTo("/api/v1/client/quota/overview?includeTeamMembers=true");
            assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer session-access");
            writeJson(exchange, quotaJson(userId, accountId, teamId, true, null));
        });
        server.start();
        try {
            var gateway = gateway(server);

            var overview = gateway.quotaOverview("session-access");

            assertThat(overview.user().id()).isEqualTo(userId);
            assertThat(overview.policy().fiveHourTokenLimit()).isEqualTo(1_000L);
            assertThat(overview.policy().weeklyTokenLimit()).isEqualTo(10_000L);
            assertThat(overview.usage().measurementStatus()).isEqualTo(QuotaMeasurementStatus.CLIENT_REPORTED);
            assertThat(overview.usage().fiveHourUsedTokens()).isEqualTo(100L);
            assertThat(overview.usage().fiveHourRemainingTokens()).isEqualTo(900L);
            assertThat(overview.teamQuota().fiveHourUsedTokens()).isEqualTo(150L);
            assertThat(overview.teamQuota().fiveHourRemainingTokens()).isEqualTo(850L);
            assertThat(overview.teamMembers()).isNull();
            assertThat(overview.toString()).doesNotContain("session-access", "credential", "refresh");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void preservesAllCurrentTeamMemberUsageFromTheExistingAuthenticatedQuotaRoute() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID disabledUserId = UUID.randomUUID();
        UUID teamId = UUID.randomUUID();
        String members = """
                [{"userId":"%s","displayName":"Alice","status":"ACTIVE",
                  "fiveHourUsedTokens":100,"weeklyUsedTokens":200,"totalUsedTokens":2500,
                  "fiveHourTokenLimit":1000,"weeklyTokenLimit":10000,
                  "fiveHourRemainingTokens":350,"weeklyRemainingTokens":4200,
                  "measuredAt":"2026-10-19T01:02:03Z"},
                 {"userId":"%s","displayName":"Bob","status":"DISABLED",
                  "fiveHourUsedTokens":0,"weeklyUsedTokens":0,"totalUsedTokens":0,
                  "fiveHourTokenLimit":null,"weeklyTokenLimit":null,
                  "fiveHourRemainingTokens":null,"weeklyRemainingTokens":null,"measuredAt":null}]
                """.formatted(userId, disabledUserId);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/client/quota/overview", exchange -> {
            assertThat(exchange.getRequestMethod()).isEqualTo("GET");
            assertThat(exchange.getRequestURI().toString())
                    .isEqualTo("/api/v1/client/quota/overview?includeTeamMembers=true");
            assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer session-access");
            writeJson(exchange, withTeamMembers(quotaJson(userId, UUID.randomUUID(), teamId, true, null), members));
        });
        server.start();
        try {
            var overview = gateway(server).quotaOverview("session-access");
            assertThat(overview.teamMembers()).containsExactly(
                    new TeamMemberUsage(userId, "Alice", "ACTIVE", 100L, 200L, 2500L,
                            1000L, 10000L, 350L, 4200L,
                            Instant.parse("2026-10-19T01:02:03Z")),
                    new TeamMemberUsage(disabledUserId, "Bob", "DISABLED", 0L, 0L, 0L,
                            null, null, null, null, null));

            var mapper = new ObjectMapper();
            assertThat(mapper.readValue(mapper.writeValueAsString(overview), PersonalQuotaOverview.class).teamMembers())
                    .isEqualTo(overview.teamMembers());
            assertThat(mapper.writeValueAsString(overview.teamMembers()))
                    .doesNotContain("email", "accessToken", "refreshToken", "credential");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void preservesAnExplicitEmptyTeamMemberProjection() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID teamId = UUID.randomUUID();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/client/quota/overview", exchange -> writeJson(exchange,
                withTeamMembers(quotaJson(userId, UUID.randomUUID(), teamId, true, null), "[]")));
        server.start();
        try {
            assertThat(gateway(server).quotaOverview("session-access").teamMembers()).isEmpty();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void failsClosedWhenMemberPersonalAllowanceFieldsAreMissing() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID teamId = UUID.randomUUID();
        String members = """
                [{"userId":"%s","displayName":"Alice","status":"ACTIVE",
                  "fiveHourUsedTokens":100,"weeklyUsedTokens":200,"totalUsedTokens":2500,"measuredAt":null}]
                """.formatted(userId);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/client/quota/overview", exchange -> writeJson(exchange,
                withTeamMembers(quotaJson(userId, UUID.randomUUID(), teamId, true, null), members)));
        server.start();
        try {
            assertThatThrownBy(() -> gateway(server).quotaOverview("session-access"))
                    .isInstanceOfSatisfying(ControlPlaneException.class,
                            failure -> assertThat(failure.reason()).isEqualTo(ControlPlaneException.Reason.UNAVAILABLE));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void failsClosedWhenTeamMemberUsageContainsPrivateFields() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID teamId = UUID.randomUUID();
        String members = """
                [{"userId":"%s","displayName":"Alice","status":"ACTIVE",
                  "fiveHourUsedTokens":100,"weeklyUsedTokens":200,"totalUsedTokens":2500,
                  "fiveHourTokenLimit":1000,"weeklyTokenLimit":10000,
                  "fiveHourRemainingTokens":350,"weeklyRemainingTokens":4200,
                  "measuredAt":null,"email":"private-member@example.com"}]
                """.formatted(userId);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/client/quota/overview", exchange -> writeJson(exchange,
                withTeamMembers(quotaJson(userId, UUID.randomUUID(), teamId, true, null), members)));
        server.start();
        try {
            assertThatThrownBy(() -> gateway(server).quotaOverview("session-access"))
                    .isInstanceOfSatisfying(ControlPlaneException.class,
                            failure -> assertThat(failure.reason()).isEqualTo(ControlPlaneException.Reason.UNAVAILABLE));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void failsClosedWhenTheCentralQuotaEnvelopeContainsAnUnexpectedCredentialField() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        UUID teamId = UUID.randomUUID();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/client/quota/overview", exchange -> {
            String quota = quotaJson(userId, accountId, teamId, true, null);
            writeJson(exchange, quota.substring(0, quota.length() - 1) + ",\"credential\":\"must-not-be-accepted\"}");
        });
        server.start();
        try {
            assertThatThrownBy(() -> gateway(server).quotaOverview("session-access"))
                    .isInstanceOf(ControlPlaneException.class)
                    .extracting(exception -> ((ControlPlaneException) exception).reason())
                    .isEqualTo(ControlPlaneException.Reason.UNAVAILABLE);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void doesNotExposeCredentialDownloadInTheGateway() {
        assertThat(java.util.Arrays.stream(HttpControlPlaneGateway.class.getDeclaredMethods())
                .map(java.lang.reflect.Method::getName))
                .doesNotContain("codexCredential");
    }

    @Test
    void reportsOnlyIdempotentTokenEventsWithoutManagedAccountAssignment() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        UUID teamId = UUID.randomUUID();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/client/usage/events", exchange -> {
            String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(exchange.getRequestMethod()).isEqualTo("POST");
            assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer session-access");
            assertThat(request).contains("codex-local-usage:v1:abc", "totalTokens", "TOKEN_USAGE_RECORD");
            assertThat(request).contains("\"model\":\"gpt-6.1-sol\"", "\"reasoningEffort\":\"max\"");
            assertThat(request).doesNotContain("codexAccountId");
            assertThat(request).contains("\"inputTokens\":3", "\"outputTokens\":7", "\"cachedInputTokens\":0");
            assertThat(request).doesNotContain("credential");
            writeJson(exchange, "{\"acceptedEvents\":1}");
        });
        server.start();
        try {
            var gateway = gateway(server);
            var event = new CodexUsageEvent(
                    "codex-local-usage:v1:abc",
                    Instant.parse("2026-10-06T00:00:00Z"),
                    3L,
                    0L,
                    0L,
                    7L,
                    0L,
                    10L,
                    CodexUsageEventSource.TOKEN_USAGE_RECORD, "gpt-6.1-sol", "max"
            );

            assertThat(gateway.reportUsage("session-access", List.of(event))).isEqualTo(1);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void treatsAUsageReportPolicyConflictAsAccessDenial() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/client/usage/events", exchange -> {
            byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(409, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            var event = new CodexUsageEvent(
                    "codex-local-usage:v1:abc",
                    Instant.parse("2026-10-06T00:00:00Z"),
                    0L,
                    0L,
                    0L,
                    1L,
                    0L,
                    1L,
                    CodexUsageEventSource.TOKEN_USAGE_RECORD
            );
            assertThatThrownBy(() -> gateway(server).reportUsage("session-access", List.of(event)))
                    .isInstanceOf(ControlPlaneException.class)
                    .extracting(exception -> ((ControlPlaneException) exception).reason())
                    .isEqualTo(ControlPlaneException.Reason.ACCESS_DENIED);
        } finally {
            server.stop(0);
        }
    }

    private static HttpControlPlaneGateway gateway(HttpServer server) {
        return new HttpControlPlaneGateway(
                "http://127.0.0.1:" + server.getAddress().getPort(),
                new ObjectMapper()
        );
    }

    private static String quotaJson(
            UUID userId,
            UUID accountId,
            UUID teamId,
            boolean allowed,
            String blockReason
    ) {
        String reason = blockReason == null ? "null" : "\"" + blockReason + "\"";
        return """
                {"user":{"id":"%s","email":"alice@example.com","displayName":"Alice",
                "role":"USER","status":"ACTIVE","teamId":"%s","fiveHourQuotaPercent":null,
                "weeklyQuotaPercent":null,"fiveHourTokenLimit":1000,"weeklyTokenLimit":10000,
                "createdAt":"2026-10-06T00:00:00Z","updatedAt":"2026-10-06T00:00:00Z"},
                "team":{"id":"%s","name":"Design"},
                "teamQuota":{"id":"%s","name":"Design","status":"ACTIVE","memberCount":2,
                "fiveHourTokenLimit":1000,"weeklyTokenLimit":10000,"totalTokenLimit":20000,
                "fiveHourUsedTokens":150,"weeklyUsedTokens":500,"totalUsedTokens":500,
                "fiveHourRemainingTokens":850,"weeklyRemainingTokens":9500,
                "totalRemainingTokens":19500,"measuredAt":null},
                "policy":{"fiveHourTokenLimit":1000,"weeklyTokenLimit":10000,"fiveHourSource":"USER","weeklySource":"USER"},
                "usage":{"measurementStatus":"CLIENT_REPORTED","fiveHourUsedTokens":100,"weeklyUsedTokens":200,
                "fiveHourRemainingTokens":900,"weeklyRemainingTokens":9800,"fiveHourResetsAt":null,
                "weeklyResetsAt":null,"measuredAt":"2026-10-06T00:00:00Z"},"allowed":%s,"blockReason":%s}
                """.formatted(userId, teamId, teamId, teamId, allowed, reason).replaceAll("\\s+", "");
    }

    private static String withTeamMembers(String quota, String members) {
        return quota.substring(0, quota.length() - 1) + ",\"teamMembers\":" + members + "}";
    }

    private static void writeJson(com.sun.net.httpserver.HttpExchange exchange, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }
}
