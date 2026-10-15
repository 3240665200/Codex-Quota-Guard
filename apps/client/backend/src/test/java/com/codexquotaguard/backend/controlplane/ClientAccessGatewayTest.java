package com.codexquotaguard.backend.controlplane;

import static org.assertj.core.api.Assertions.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

class ClientAccessGatewayTest {
    @TempDir Path temporary;

    @Test void readsAccessUsingOnlyTheInstalledDeviceIdentityAndNeverCachesIt() throws Exception {
        UUID device = UUID.randomUUID();
        Path file = temporary.resolve("meter-device-id.txt");
        Files.writeString(file, device.toString());
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/client/access", exchange -> {
            assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer token");
            assertThat(exchange.getRequestHeaders().getFirst("X-Quota-Guard-Device-ID")).isEqualTo(device.toString());
            byte[] body = "{\"allowed\":false,\"bindingId\":null,\"reason\":\"SESSION_BINDING_REQUIRED\"}".getBytes();
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            var gateway = new HttpControlPlaneGateway("http://127.0.0.1:" + server.getAddress().getPort(),
                    new ObjectMapper(), new ClientDeviceIdentity(file));
            assertThat(gateway.clientAccess("token").allowed()).isFalse();
            Files.delete(file);
            assertThat(gateway.clientAccess("token").allowed()).isFalse();
        } finally { server.stop(0); }
    }

    @Test void rejectsInconsistentAccessAndMapsBinding403WithoutLeakingBody() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/client/quota/overview", exchange -> {
            byte[] body = "{\"code\":\"SESSION_BINDING_REQUIRED\",\"message\":\"private upstream detail\"}".getBytes();
            exchange.sendResponseHeaders(403, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            assertThatThrownBy(() -> new HttpControlPlaneGateway("http://127.0.0.1:" + server.getAddress().getPort(),
                    new ObjectMapper()).quotaOverview("token"))
                    .isInstanceOfSatisfying(ControlPlaneException.class,
                            failure -> assertThat(failure.reason().name()).isEqualTo("BINDING_REQUIRED"));
            assertThatThrownBy(() -> new ClientAccess(true, null, null)).isInstanceOf(IllegalArgumentException.class);
        } finally { server.stop(0); }
    }
}
