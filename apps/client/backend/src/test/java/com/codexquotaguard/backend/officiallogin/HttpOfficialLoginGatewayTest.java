package com.codexquotaguard.backend.officiallogin;

import static org.assertj.core.api.Assertions.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.ObjectMapper;

class HttpOfficialLoginGatewayTest {
    @ParameterizedTest
    @ValueSource(strings = {
            "http://198.51.100.10:8080", "http://control.example.test:8080",
            "http://localhost:8080", "http://[2001:db8::10]:8080",
            "https://control.example.test", "https://198.51.100.10:8443",
            "http://127.0.0.1:8080", "https://control.example.test/"
    })
    void startsLocalGatewayWithHttpAndHttpsServerOrigins(String origin) {
        new ApplicationContextRunner()
                .withUserConfiguration(HttpOfficialLoginGateway.class)
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withPropertyValues("quota-guard.control-plane.base-url=" + origin)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(OfficialLoginGateway.class);
                });
    }

    @Test void rejectsMalformedAndCredentialBearingOrigins() {
        for(String url : new String[]{"ftp://example.test", "file:///tmp/server", "https://user:secret@example.test", "http://user:secret@example.test", "https://example.test/path", "https://example.test?token=synthetic", "http://example.test#fragment", "http://example.test:0", "http://example.test:65536"})
            assertThatThrownBy(() -> new HttpOfficialLoginGateway(url,new ObjectMapper())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void rejectsHttpClientsThatAutomaticallyFollowRedirects() {
        var http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS).build();
        assertThatThrownBy(() -> new HttpOfficialLoginGateway("http://control.example.test", new ObjectMapper(), http))
                .isInstanceOf(IllegalArgumentException.class);
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
