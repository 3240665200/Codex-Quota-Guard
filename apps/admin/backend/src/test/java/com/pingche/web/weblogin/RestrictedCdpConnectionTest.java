package com.pingche.web.weblogin;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class RestrictedCdpConnectionTest {
    @Test
    void rejectsNonOwnedDebuggerAddressesBeforeAnyNetworkRequest() {
        var http = mock(HttpClient.class);
        var connection = new RestrictedCdpConnection(new ObjectMapper(), http, event -> {});
        for (String uri : List.of("ws://localhost:9222/devtools/browser/owned", "wss://127.0.0.1:9222/devtools/browser/owned",
                "ws://127.0.0.1:9222/devtools/page/owned", "ws://127.0.0.1:9222/devtools/browser/owned?x=1", "ws://127.0.0.1:9222/devtools/browser/owned#x")) {
            assertThatThrownBy(() -> connection.connect(URI.create(uri))).isInstanceOf(IllegalStateException.class);
        }
        verifyNoInteractions(http);
    }

    @Test
    void boundedFragmentTransportAllowsOnlyInternalMethodsAndRedactsProtocolErrors() {
        var http = mock(HttpClient.class); var builder = mock(WebSocket.Builder.class); var socket = mock(WebSocket.class);
        when(http.newWebSocketBuilder()).thenReturn(builder);
        when(builder.connectTimeout(any())).thenReturn(builder);
        when(builder.buildAsync(any(), any())).thenReturn(CompletableFuture.completedFuture(socket));
        when(socket.sendText(anyString(), eq(true))).thenReturn(CompletableFuture.completedFuture(socket));
        List<String> events = new ArrayList<>();
        var connection = new RestrictedCdpConnection(new ObjectMapper(), http, events::add);
        connection.connect(URI.create("ws://127.0.0.1:9222/devtools/browser/owned"));
        assertThat(connection.send("Runtime.evaluate", Map.of("expression", "synthetic-secret"), "owned")).isCompletedExceptionally();
        var result = connection.send("Page.enable", Map.of(), "owned");
        connection.onText(socket, "{\"id\":1,\"result\":", false);
        connection.onText(socket, "{\"ok\":true}}", true);
        assertThat(result.join().path("ok").asBoolean()).isTrue();
        connection.onText(socket, "{\"method\":\"Network.loadingFailed\",\"params\":{}}", true);
        assertThat(events).hasSize(1);
        var pending = connection.send("Page.enable", Map.of(), "owned");
        connection.onText(socket, "x".repeat(RestrictedCdpConnection.MAX_MESSAGE + 1), false);
        assertThat(pending).isCompletedExceptionally();
        assertThat(connection.termination()).isCompleted();
        connection.close(); verify(socket, times(1)).abort();
    }
}
