package com.codexquotaguard.backend.controlplane;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class HttpControlPlaneGatewayTest {

    @Test
    void permitsHttpsAndDevelopmentLoopbackOnly() {
        assertThat(HttpControlPlaneGateway.normalizeBaseUri("https://control.example.com:8443"))
                .isEqualTo(URI.create("https://control.example.com:8443/"));
        assertThat(HttpControlPlaneGateway.normalizeBaseUri("http://127.0.0.1:8080"))
                .isEqualTo(URI.create("http://127.0.0.1:8080/"));
        assertThat(HttpControlPlaneGateway.normalizeBaseUri("http://localhost:8080/"))
                .isEqualTo(URI.create("http://localhost:8080/"));
    }

    @Test
    void rejectsRemotePlainHttpAndAnythingOtherThanAnOrigin() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> HttpControlPlaneGateway.normalizeBaseUri("http://149.88.90.196:8080"));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> HttpControlPlaneGateway.normalizeBaseUri("https://user:secret@control.example.com"));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> HttpControlPlaneGateway.normalizeBaseUri("https://control.example.com/api"));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> HttpControlPlaneGateway.normalizeBaseUri("file:///C:/secret"));
    }

    @Test
    void permitsPrivateLanHttpOnlyWithExplicitApproval() {
        assertThat(HttpControlPlaneGateway.normalizeBaseUri("http://192.168.0.102:8080", true))
                .isEqualTo(URI.create("http://192.168.0.102:8080/"));
        assertThat(HttpControlPlaneGateway.normalizeBaseUri("http://10.0.0.5:8080", true))
                .isEqualTo(URI.create("http://10.0.0.5:8080/"));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> HttpControlPlaneGateway.normalizeBaseUri("http://149.88.90.196:8080", true));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> HttpControlPlaneGateway.normalizeBaseUri("http://example.com:8080", true));
    }

    @Test
    void acceptsCurrentUserFieldsWhileRejectingUnexpectedQuotaFields() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID teamId = UUID.randomUUID();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/auth/login", exchange -> writeJson(exchange, """
                {"accessToken":"server-access-secret","tokenType":"Bearer","expiresIn":900,
                 "refreshToken":"server-refresh-secret","refreshExpiresIn":86400,
                 "user":{"id":"%s","email":"alice@example.com","displayName":"Alice",
                 "role":"USER","status":"ACTIVE","assignedCodexAccountId":null,
                 "teamId":"%s","fiveHourQuotaPercent":75,"weeklyQuotaPercent":90,
                 "createdAt":"2026-01-01T00:00:00Z","updatedAt":"2026-01-01T00:00:00Z"}}
                """.formatted(userId, teamId)));
        server.createContext("/api/v1/client/quota/overview", exchange -> writeJson(exchange, """
                {"assigned":false,"user":{"id":"%s","email":"alice@example.com","displayName":"Alice",
                "role":"USER","status":"ACTIVE"},"codexAccount":null,"team":null,
                "policy":{"fiveHourLimitPercent":80,"weeklyLimitPercent":90,"fiveHourSource":"USER","weeklySource":"DEFAULT"},
                "usage":{"measurementStatus":"NOT_MEASURED","fiveHourUsedPercent":null,"weeklyUsedPercent":null,
                "fiveHourRemainingPercent":null,"weeklyRemainingPercent":null,"fiveHourResetsAt":null,
                "weeklyResetsAt":null,"measuredAt":null},"allowed":false,"blockReason":"USAGE_NOT_MEASURED",
                "credential":"must-not-be-accepted"}
                """.formatted(userId)));
        server.start();
        try {
            var gateway = new HttpControlPlaneGateway(
                    "http://127.0.0.1:" + server.getAddress().getPort(), new ObjectMapper()
            );

            var session = gateway.login("alice@example.com", "correct-password");

            assertThat(session.user().id()).isEqualTo(userId);
            assertThat(session.user().teamId()).isEqualTo(teamId);
            assertThat(session.user().fiveHourQuotaPercent()).isEqualTo(75);
            assertThat(session.user().weeklyQuotaPercent()).isEqualTo(90);
            assertThat(session.toString()).doesNotContain("server-access-secret", "server-refresh-secret");
            assertThatThrownBy(() -> gateway.quotaOverview(session.accessToken()))
                    .isInstanceOf(ControlPlaneException.class)
                    .extracting(exception -> ((ControlPlaneException) exception).reason())
                    .isEqualTo(ControlPlaneException.Reason.UNAVAILABLE);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void acceptsTheExactCentralPersonalQuotaContractWithoutCredentials() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        UUID teamId = UUID.randomUUID();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/client/quota/overview", exchange -> {
            assertThat(exchange.getRequestMethod()).isEqualTo("GET");
            assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer session-access");
            writeJson(exchange, """
                    {"assigned":true,"user":{"id":"%s","email":"alice@example.com","displayName":"Alice",
                    "role":"USER","status":"ACTIVE"},"codexAccount":{"id":"%s","displayName":"Team account",
                    "status":"ACTIVE","updatedAt":"2026-01-01T00:00:00Z"},"team":{"id":"%s","name":"Design"},
                    "policy":{"fiveHourLimitPercent":75,"weeklyLimitPercent":85,"fiveHourSource":"TEAM","weeklySource":"DEFAULT"},
                    "usage":{"measurementStatus":"CURRENT","fiveHourUsedPercent":20,"weeklyUsedPercent":10,
                    "fiveHourRemainingPercent":80,"weeklyRemainingPercent":90,"fiveHourResetsAt":"2026-01-01T05:00:00Z",
                    "weeklyResetsAt":"2026-01-08T00:00:00Z","measuredAt":"2026-01-01T00:00:00Z"},
                    "allowed":false,"blockReason":"FIVE_HOUR_QUOTA_EXCEEDED"}
                    """.formatted(userId, accountId, teamId));
        });
        server.start();
        try {
            var gateway = new HttpControlPlaneGateway(
                    "http://127.0.0.1:" + server.getAddress().getPort(), new ObjectMapper()
            );

            var overview = gateway.quotaOverview("session-access");

            assertThat(overview.assigned()).isTrue();
            assertThat(overview.user().id()).isEqualTo(userId);
            assertThat(overview.codexAccount().id()).isEqualTo(accountId);
            assertThat(overview.team().id()).isEqualTo(teamId.toString());
            assertThat(overview.team().name()).isEqualTo("Design");
            assertThat(overview.policy().fiveHourSource()).isEqualTo("TEAM");
            assertThat(overview.usage().measurementStatus()).isEqualTo(QuotaMeasurementStatus.CURRENT);
            assertThat(overview.usage().fiveHourRemainingPercent()).isEqualTo(80.0);
            assertThat(overview.allowed()).isFalse();
            assertThat(overview.blockReason()).isEqualTo("FIVE_HOUR_QUOTA_EXCEEDED");
            assertThat(overview.toString()).doesNotContain("session-access", "credential", "refresh");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void preservesNullUsageValuesWhenTheCentralServiceHasNotMeasuredUsage() throws Exception {
        UUID userId = UUID.randomUUID();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/client/quota/overview", exchange -> writeJson(exchange, """
                {"assigned":false,"user":{"id":"%s","email":"alice@example.com","displayName":"Alice",
                "role":"USER","status":"ACTIVE"},"codexAccount":null,"team":null,
                "policy":{"fiveHourLimitPercent":75,"weeklyLimitPercent":85,"fiveHourSource":"USER","weeklySource":"DEFAULT"},
                "usage":{"measurementStatus":"NOT_MEASURED","fiveHourUsedPercent":null,"weeklyUsedPercent":null,
                "fiveHourRemainingPercent":null,"weeklyRemainingPercent":null,"fiveHourResetsAt":null,
                "weeklyResetsAt":null,"measuredAt":null},"allowed":false,"blockReason":"USAGE_NOT_MEASURED"}
                """.formatted(userId)));
        server.start();
        try {
            var gateway = new HttpControlPlaneGateway(
                    "http://127.0.0.1:" + server.getAddress().getPort(), new ObjectMapper()
            );

            var overview = gateway.quotaOverview("session-access");

            assertThat(overview.usage().measurementStatus()).isEqualTo(QuotaMeasurementStatus.NOT_MEASURED);
            assertThat(overview.usage().fiveHourUsedPercent()).isNull();
            assertThat(overview.usage().weeklyRemainingPercent()).isNull();
        } finally {
            server.stop(0);
        }
    }

    private static void writeJson(com.sun.net.httpserver.HttpExchange exchange, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }
}
