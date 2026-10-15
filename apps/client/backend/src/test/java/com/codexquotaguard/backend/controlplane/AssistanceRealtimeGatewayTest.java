package com.codexquotaguard.backend.controlplane;

import static org.assertj.core.api.Assertions.*;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayInputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class AssistanceRealtimeGatewayTest {
    @Test void currentListenerRestoresOnlyThroughAFixedAuthenticatedRead() throws Exception {
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/api/v1/client/login-assistance/listeners/current", exchange -> {
            assertThat(exchange.getRequestMethod()).isEqualTo("GET");
            assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer synthetic-token");
            assertThat(exchange.getRequestURI().getQuery()).isNull();
            byte[] bytes="{\"listener\":null}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","application/json");
            exchange.sendResponseHeaders(200,bytes.length);
            try(var output=exchange.getResponseBody()){output.write(bytes);}
        });
        server.start();
        try {
            var gateway=new HttpControlPlaneGateway("http://127.0.0.1:"+server.getAddress().getPort(),new ObjectMapper());
            assertThat(gateway.currentLoginAssistance("synthetic-token").listener()).isNull();
        } finally {server.stop(0);}
    }

    @Test void streamsOnlyFixedNotificationsAndNeverReturnsAnUpstreamPayload() throws Exception {
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/api/v1/client/login-assistance/events", exchange -> {
            assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer synthetic-token");
            assertThat(exchange.getRequestHeaders().getFirst("Accept")).isEqualTo("text/event-stream");
            assertThat(exchange.getRequestURI().getQuery()).isNull();
            exchange.getResponseHeaders().set("Content-Type","text/event-stream;charset=UTF-8");
            exchange.sendResponseHeaders(200,0);
            try(var output=exchange.getResponseBody()) {
                output.write("event:connected\ndata:{}\n\nevent:heartbeat\ndata:{}\n\nevent:state_changed\ndata:{}\n\n".getBytes(StandardCharsets.UTF_8));
            }
        });
        server.start();
        try {
            var gateway=new HttpControlPlaneGateway("http://127.0.0.1:"+server.getAddress().getPort(),new ObjectMapper());
            try(var stream=gateway.loginAssistanceEvents("synthetic-token")) {
                assertThat(stream.next()).isEqualTo(AssistanceEventStream.Signal.CONNECTED);
                assertThat(stream.next()).isEqualTo(AssistanceEventStream.Signal.HEARTBEAT);
                assertThat(stream.next()).isEqualTo(AssistanceEventStream.Signal.STATE_CHANGED);
                assertThat(stream.next()).isNull();
            }
        } finally {server.stop(0);}
    }

    @Test void rejectsUnexpectedPayloadsAndBoundsPartialFramesWithoutEchoingThem() {
        for(String payload:new String[]{"event:state_changed\ndata:{\"code\":\"123456\"}\n\n",
                "event:secret\ndata:{}\n\n","event:connected\ndata:"+"x".repeat(2049),
                "event:connected\ndata:{}"}) {
            try(var stream=new AssistanceEventStream(new ByteArrayInputStream(payload.getBytes(StandardCharsets.UTF_8)))) {
                assertThatThrownBy(stream::next).isInstanceOf(java.io.IOException.class)
                        .hasMessage("Assistance event stream unavailable.").hasNoCause();
            }
        }
    }

    @Test void closingTheSubscriptionOnlyClosesTheStream() {
        AtomicBoolean closed=new AtomicBoolean();
        var input=new ByteArrayInputStream(new byte[0]) {@Override public void close(){closed.set(true);}};
        new AssistanceEventStream(input).close();
        assertThat(closed).isTrue();
    }

    @Test void htmlAndRedirectResponsesAreNotAcceptedAsEvents() throws Exception {
        for(int status:new int[]{200,302,401,503}) {
            HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            server.createContext("/api/v1/client/login-assistance/events",exchange -> {
                exchange.getResponseHeaders().set("Content-Type","text/html");
                exchange.getResponseHeaders().set("Location","http://127.0.0.1:1/private");
                exchange.sendResponseHeaders(status,0);
                try(var output=exchange.getResponseBody()){output.write("private-secret 123456".getBytes(StandardCharsets.UTF_8));}
            });
            server.start();
            try {
                var gateway=new HttpControlPlaneGateway("http://127.0.0.1:"+server.getAddress().getPort(),new ObjectMapper());
                assertThatThrownBy(()->gateway.loginAssistanceEvents("synthetic-token"))
                        .isInstanceOf(ControlPlaneException.class).hasMessage(status==401?"UNAUTHORIZED":"UNAVAILABLE")
                        .hasNoCause();
            } finally {server.stop(0);}
        }
    }
}
