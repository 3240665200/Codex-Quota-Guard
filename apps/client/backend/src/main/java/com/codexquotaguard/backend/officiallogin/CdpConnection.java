package com.codexquotaguard.backend.officiallogin;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Small, bounded CDP transport connected only to the owned profile's loopback endpoint. */
final class CdpConnection implements WebSocket.Listener,AutoCloseable {
    private final ObjectMapper mapper;
    private final HttpClient client;
    private final Consumer<String> events;
    private final Map<Integer,CompletableFuture<JsonNode>> pending=new ConcurrentHashMap<>();
    private final AtomicInteger ids=new AtomicInteger();
    private final AtomicBoolean closed=new AtomicBoolean();
    private final CompletableFuture<Void> termination=new CompletableFuture<>();
    private final StringBuilder fragments=new StringBuilder();
    private volatile WebSocket socket;

    CdpConnection(ObjectMapper mapper,HttpClient client,Consumer<String> events) { this.mapper=mapper;this.client=client;this.events=events; }
    CompletableFuture<Void> termination() { return termination; }

    void connect(URI uri) {
        if (!"ws".equals(uri.getScheme()) || !"127.0.0.1".equals(uri.getHost()) || uri.getPort()<1 || uri.getPort()>65535
                || uri.getRawUserInfo()!=null || uri.getRawQuery()!=null || uri.getRawFragment()!=null
                || !uri.getRawPath().matches("/devtools/browser/[A-Za-z0-9-]{1,100}")) throw failure();
        try {
            WebSocket opened=client.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(10)).buildAsync(uri,this).get(10,TimeUnit.SECONDS);
            if (closed.get()) { opened.abort();throw failure(); }
            socket=opened;
        } catch(InterruptedException interrupted) { Thread.currentThread().interrupt();throw failure(); }
        catch(Exception ignored) { throw failure(); }
    }

    CompletableFuture<JsonNode> send(String method,Map<String,?> params,String sessionId) {
        if (closed.get() || socket==null || pending.size()>=64) return CompletableFuture.failedFuture(failure());
        int id=ids.incrementAndGet();
        Map<String,Object> message=new LinkedHashMap<>();message.put("id",id);message.put("method",method);message.put("params",params);
        if (sessionId!=null) message.put("sessionId",sessionId);
        var result=new CompletableFuture<JsonNode>();pending.put(id,result);
        result.orTimeout(10,TimeUnit.SECONDS).whenComplete((value,error) -> pending.remove(id,result));
        try { socket.sendText(mapper.writeValueAsString(message),true).whenComplete((ignored,error) -> { if(error!=null) result.completeExceptionally(failure()); }); }
        catch(RuntimeException ignored) { result.completeExceptionally(failure()); }
        return result;
    }

    @Override public void onOpen(WebSocket socket) { socket.request(1); }
    @Override public CompletionStage<?> onText(WebSocket socket,CharSequence part,boolean last) {
        if (closed.get()) return CompletableFuture.completedFuture(null);
        String text=null;
        synchronized(fragments) {
            if (part.length()>262144-fragments.length()) { close();return CompletableFuture.completedFuture(null); }
            fragments.append(part);
            if (last) { text=fragments.toString();fragments.setLength(0); }
        }
        if (text!=null) {
            try {
                JsonNode message=mapper.readTree(text);
                if (message.has("id")) {
                    CompletableFuture<JsonNode> result=pending.remove(message.path("id").asInt());
                    if (result!=null) { if(message.has("error")) result.completeExceptionally(failure());else result.complete(message.path("result")); }
                } else if (message.path("method").asString("").startsWith("Network.")) events.accept(text);
            } catch(RuntimeException ignored) { close(); }
        }
        socket.request(1);return CompletableFuture.completedFuture(null);
    }
    @Override public CompletionStage<?> onClose(WebSocket socket,int code,String reason) { close();return CompletableFuture.completedFuture(null); }
    @Override public void onError(WebSocket socket,Throwable error) { close(); }
    @Override public void close() {
        if(!closed.compareAndSet(false,true)) return;
        termination.complete(null);
        pending.values().forEach(result -> result.completeExceptionally(failure()));pending.clear();
        synchronized(fragments) { fragments.setLength(0); }
        WebSocket current=socket;if(current!=null) current.abort();
    }
    private static OfficialLoginException failure() { return new OfficialLoginException("OFFICIAL_LOGIN_BROWSER_UNAVAILABLE"); }
}
