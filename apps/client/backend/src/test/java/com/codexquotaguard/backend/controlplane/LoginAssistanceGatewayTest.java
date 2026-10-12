package com.codexquotaguard.backend.controlplane;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class LoginAssistanceGatewayTest {
    private static final UUID LISTENER = UUID.fromString("b350ad52-7b5d-4a5e-bc26-73d17a8dbbaf");
    private static final String RESULT = """
            {"id":"%s","state":"RECEIVED","startedAt":"2026-10-12T00:00:00Z",
             "expiresAt":"2026-10-12T00:03:00Z","listeningAt":"2026-10-12T00:00:01Z",
             "receivedAt":"2026-10-12T00:00:05Z","code":"123456","errorCode":null}
            """.formatted(LISTENER);

    @Test
    void usesOnlyFixedAuthenticatedRoutesAndDoesNotSendUserOrMailboxCredentials() throws Exception {
        var calls = new ArrayList<String>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/client/login-assistance", exchange -> {
            assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer synthetic-session");
            String path = exchange.getRequestURI().getPath();
            calls.add(exchange.getRequestMethod() + " " + path);
            assertThat(exchange.getRequestURI().getQuery()).isNull();
            assertThat(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8))
                    .doesNotContain("userId", "password", "host", "officialAccountId");
            respond(exchange, 200, path.endsWith("login-assistance") ? """
                    {"available":true,"reasonCode":null,"officialAccountId":"00000000-0000-4000-8000-000000000001",
                     "accountLabel":"Shared","recipientEmail":"owner@example.com"}
                    """ : RESULT);
        });
        server.start();
        try {
            var gateway = gateway(server);
            assertThat(gateway.loginAssistanceInfo("synthetic-session").available()).isTrue();
            assertThat(gateway.startLoginAssistance("synthetic-session").id()).isEqualTo(LISTENER);
            var response = gateway.loginAssistanceStatus("synthetic-session", LISTENER);
            assertThat(response.code()).isEqualTo("123456");
            assertThat(response.toString()).doesNotContain("123456", "synthetic-session");
            gateway.cancelLoginAssistance("synthetic-session", LISTENER);
            assertThat(calls).containsExactly(
                    "GET /api/v1/client/login-assistance",
                    "POST /api/v1/client/login-assistance/listeners",
                    "GET /api/v1/client/login-assistance/listeners/" + LISTENER,
                    "DELETE /api/v1/client/login-assistance/listeners/" + LISTENER);
        } finally { server.stop(0); }
    }

    @Test
    void ignoresUntrustedFailureDetailsAndClassifiesBusyRateLimitAndAccessSeparately() throws Exception {
        for (int status : new int[] {401, 403, 404, 409, 429, 503}) {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/api/v1/client/login-assistance/listeners", exchange ->
                    respond(exchange, status, "{\"message\":\"private-password 123456\"}"));
            server.start();
            try {
                assertThatThrownBy(() -> gateway(server).startLoginAssistance("synthetic-session"))
                        .isInstanceOf(ControlPlaneException.class)
                        .satisfies(error -> {
                            var reason = ((ControlPlaneException) error).reason().name();
                            assertThat(reason).isEqualTo(switch (status) {
                                case 401 -> "UNAUTHORIZED";
                                case 409 -> "MAIL_BUSY";
                                case 429 -> "MAIL_RATE_LIMITED";
                                case 403, 404 -> "MAIL_REJECTED";
                                default -> "UNAVAILABLE";
                            });
                            assertThat(error.toString()).doesNotContain("private-password", "123456");
                        });
            } finally { server.stop(0); }
        }
    }

    @Test
    void refusesMalformedListenerDataAndUnexpectedSecretFields() throws Exception {
        for (String body : List.of(RESULT.replace("123456", "1234567"),
                RESULT.replace("RECEIVED", "LISTENING"),
                RESULT.replace("\"errorCode\":null", "\"errorCode\":null,\"password\":\"mail-secret\""))) {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/api/v1/client/login-assistance/listeners", exchange -> respond(exchange, 200, body));
            server.start();
            try {
                assertThatThrownBy(() -> gateway(server).loginAssistanceStatus("synthetic-session", LISTENER))
                        .isInstanceOf(ControlPlaneException.class).hasMessage("UNAVAILABLE");
            } finally { server.stop(0); }
        }
    }

    private HttpControlPlaneGateway gateway(HttpServer server) {
        return new HttpControlPlaneGateway("http://127.0.0.1:" + server.getAddress().getPort(), new ObjectMapper());
    }
    private static void respond(HttpExchange exchange, int status, String body) throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var stream = exchange.getResponseBody()) { stream.write(bytes); }
    }
}
