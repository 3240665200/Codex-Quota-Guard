package com.codexquotaguard.backend.officiallogin;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.net.URI;
import java.net.http.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class CdpConnectionTest {
    @Test void correlatesFragmentedRepliesAndFailsPendingCommandsOnClose() {
        var mapper=new ObjectMapper();var http=mock(HttpClient.class);var builder=mock(WebSocket.Builder.class);var socket=mock(WebSocket.class);
        AtomicReference<WebSocket.Listener> listener=new AtomicReference<>();AtomicReference<String> sent=new AtomicReference<>();
        when(http.newWebSocketBuilder()).thenReturn(builder);
        when(builder.connectTimeout(any())).thenReturn(builder);
        when(builder.buildAsync(any(),any())).thenAnswer(inv -> { listener.set(inv.getArgument(1));return CompletableFuture.completedFuture(socket); });
        when(socket.sendText(anyString(),eq(true))).thenAnswer(inv -> { sent.set(inv.getArgument(0));return CompletableFuture.completedFuture(socket); });
        List<String> events=new ArrayList<>();
        var cdp=new CdpConnection(mapper,http,events::add);cdp.connect(URI.create("ws://127.0.0.1:12345/devtools/browser/synthetic"));
        var result=cdp.send("Network.enable",Map.of(),"page-session");
        int id=mapper.readTree(sent.get()).path("id").asInt();
        assertThat(mapper.readTree(sent.get()).path("sessionId").asString()).isEqualTo("page-session");
        listener.get().onText(socket,"{\"id\":"+id+",",false);listener.get().onText(socket,"\"result\":{\"ok\":true}}",true);
        assertThat(result.join().path("ok").asBoolean()).isTrue();
        var waiting=cdp.send("Target.getTargets",Map.of(),null);cdp.close();
        assertThat(waiting.isCompletedExceptionally()).isTrue();
        assertThat(cdp.termination().isDone()).isTrue();
        verify(socket).abort();
    }

    @Test void neverConnectsToANonLoopbackOrUnexpectedDebuggerEndpoint() {
        var client=mock(HttpClient.class);var cdp=new CdpConnection(new ObjectMapper(),client,ignored -> {});
        for(String uri:List.of("ws://example.test/devtools/browser/x","wss://127.0.0.1/devtools/browser/x","ws://127.0.0.1:1234/other","ws://user@127.0.0.1:1234/devtools/browser/x"))
            assertThatThrownBy(() -> cdp.connect(URI.create(uri))).isInstanceOf(OfficialLoginException.class);
        verifyNoInteractions(client);
    }
}
