package com.codexquotaguard.backend.officiallogin;

import static org.assertj.core.api.Assertions.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class HttpOfficialLoginGatewayTest {
    @Test void rejectsNonSecureRemoteAndCredentialBearingOrigins() {
        for(String url : new String[]{"http://example.test","http://localhost:8080","https://user:secret@example.test","https://example.test/path","https://example.test?token=synthetic"})
            assertThatThrownBy(() -> new HttpOfficialLoginGateway(url,new ObjectMapper())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void sendsOwnedJobActionsWithoutRedirectsAndKeepsSecretsOutOfUrls() throws Exception {
        UUID id=UUID.randomUUID(),account=UUID.randomUUID(),helper=UUID.randomUUID();
        AtomicReference<String> path=new AtomicReference<>(),body=new AtomicReference<>(),token=new AtomicReference<>();
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/", exchange -> {
            path.set(exchange.getRequestURI().toString());body.set(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));token.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] response=("{\"id\":\""+id+"\",\"officialAccountId\":\""+account+"\",\"accountName\":\"Synthetic\",\"state\":\"COMPLETED\",\"expiresAt\":\"2026-10-15T00:10:00Z\",\"errorCode\":null,\"proxyNodeId\":null}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type","application/json");exchange.sendResponseHeaders(200,response.length);exchange.getResponseBody().write(response);exchange.close();
        });server.start();
        try {
            var gateway=new HttpOfficialLoginGateway("http://127.0.0.1:"+server.getAddress().getPort(),new ObjectMapper());
            var result=gateway.complete("synthetic-system",id,new OfficialLoginGateway.Completion(helper,"Bearer synthetic-official",null,"synthetic-account"));
            assertThat(result.state()).isEqualTo("COMPLETED");
            assertThat(path.get()).isEqualTo("/api/v1/admin/browser-logins/"+id+"/complete");
            assertThat(token.get()).isEqualTo("Bearer synthetic-system");
            assertThat(body.get()).contains("Bearer synthetic-official","synthetic-account");
            assertThat(result.toString()).doesNotContain("synthetic-official");
        } finally { server.stop(0); }
    }

    @Test void refusesRedirectsAndOversizedResponses() throws Exception {
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/", exchange -> { exchange.getResponseHeaders().add("Location","https://example.test/credential-trap");exchange.sendResponseHeaders(302,-1);exchange.close(); });server.start();
        try {
            var gateway=new HttpOfficialLoginGateway("http://127.0.0.1:"+server.getAddress().getPort(),new ObjectMapper());
            assertThatThrownBy(() -> gateway.jobs("synthetic-system")).isInstanceOf(RuntimeException.class).hasMessageNotContaining("credential-trap");
        } finally { server.stop(0); }
    }
}
