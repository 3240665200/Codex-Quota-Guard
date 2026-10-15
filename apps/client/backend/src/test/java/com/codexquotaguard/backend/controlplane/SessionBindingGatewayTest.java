package com.codexquotaguard.backend.controlplane;

import static org.assertj.core.api.Assertions.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class SessionBindingGatewayTest {
    @Test void bindingConfigurationDenialDoesNotInvalidateTheProductLogin() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var status = new java.util.concurrent.atomic.AtomicInteger(403);
        server.createContext("/api/v1/client/session-bindings/current", exchange -> {
            byte[] bytes = "{\"code\":\"SESSION_BINDING_ACCESS_DENIED\",\"message\":\"private-upstream-detail\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status.get(), bytes.length);
            exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
        try {
            var gateway = new HttpControlPlaneGateway("http://127.0.0.1:" + server.getAddress().getPort(), new ObjectMapper());
            UUID device = UUID.randomUUID();
            assertThatThrownBy(() -> gateway.sessionBindingCurrent("valid-product-token", device, device))
                    .isInstanceOfSatisfying(ControlPlaneException.class,
                            failure -> assertThat(failure.reason()).isEqualTo(ControlPlaneException.Reason.INVALID_REQUEST));
            status.set(401);
            assertThatThrownBy(() -> gateway.sessionBindingCurrent("expired-product-token", device, device))
                    .isInstanceOfSatisfying(ControlPlaneException.class,
                            failure -> assertThat(failure.reason()).isEqualTo(ControlPlaneException.Reason.UNAUTHORIZED));
        } finally { server.stop(0); }
    }

    @Test void usesFixedAuthenticatedRoutesAndOnlySafeMetadata() throws Exception {
        UUID device = UUID.randomUUID(); UUID id = UUID.randomUUID();
        var requestPath = new AtomicReference<String>();
        var requestBody = new AtomicReference<String>();
        var authorization = new AtomicReference<String>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/client/session-bindings", exchange -> {
            requestPath.set(exchange.getRequestURI().toString());
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            String json = exchange.getRequestURI().getPath().endsWith("/current")
                    ? "{\"attempt\":null,\"binding\":null}"
                    : """
                    {"id":"%s","deviceId":"%s","enrollmentId":"%s","officialAccountId":"%s",
                     "status":"QUEUED","queuePosition":1,"loginAllowed":false,"candidateSessionId":null,
                     "candidateDisplayName":null,"candidatePlatform":null,"expiresAt":"2026-10-15T10:02:00Z",
                     "errorCode":null,"quarantined":false,"binding":null}
                    """.formatted(id, device, device, id);
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
        try {
            var gateway = new HttpControlPlaneGateway("http://127.0.0.1:" + server.getAddress().getPort(), new ObjectMapper());
            assertThat(gateway.sessionBindingCurrent("native-access", device, device).binding()).isNull();
            assertThat(requestPath.get()).isEqualTo("/api/v1/client/session-bindings/current?deviceId=" + device + "&enrollmentId=" + device);
            assertThat(gateway.startSessionBinding("native-access", device, device).status()).isEqualTo("QUEUED");
            assertThat(requestBody.get()).contains(device.toString()).doesNotContain("userId", "accessToken");
            assertThat(authorization.get()).isEqualTo("Bearer native-access");
            for (String action : new String[] { "prepare", "detect", "cancel", "confirm" }) {
                gateway.sessionBindingAction("native-access", id, action, "candidate-session");
                assertThat(requestPath.get()).isEqualTo("/api/v1/client/session-bindings/attempts/" + id + "/" + action);
                assertThat(requestBody.get()).isEqualTo("confirm".equals(action) ? "{\"sessionId\":\"candidate-session\"}" : "{}");
            }
            assertThatThrownBy(() -> gateway.sessionBindingAction("native-access", id, "../../logout", null)).isInstanceOf(ControlPlaneException.class);
        } finally { server.stop(0); }
    }
}
