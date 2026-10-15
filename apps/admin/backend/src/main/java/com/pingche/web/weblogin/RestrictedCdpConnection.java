package com.pingche.web.weblogin;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Private transport: no arbitrary method, URL, script, or debugging endpoint is exposed to HTTP clients. */
final class RestrictedCdpConnection implements WebSocket.Listener, BrowserCdp {
    static final int MAX_MESSAGE = 2 * 1024 * 1024;
    private static final Set<String> METHODS = Set.of("Target.getTargets", "Target.attachToTarget", "Target.setAutoAttach",
            "Network.enable", "Network.getCookies", "Page.enable", "Page.navigate", "Page.captureScreenshot",
            "Page.setInterceptFileChooserDialog", "Emulation.setDeviceMetricsOverride", "Fetch.enable", "Fetch.continueRequest",
            "Fetch.failRequest", "Input.dispatchMouseEvent", "Input.dispatchKeyEvent", "Input.insertText", "Browser.close",
            "Browser.setDownloadBehavior", "Runtime.runIfWaitingForDebugger");
    private final ObjectMapper json;
    private final HttpClient http;
    private final Consumer<String> events;
    private final Map<Integer, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    private final AtomicInteger ids = new AtomicInteger();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final CompletableFuture<Void> termination = new CompletableFuture<>();
    private final StringBuilder fragments = new StringBuilder();
    private volatile WebSocket socket;
    RestrictedCdpConnection(ObjectMapper json, HttpClient http, Consumer<String> events) {
        this.json = json; this.http = http; this.events = events;
    }
    @Override public CompletableFuture<Void> termination() { return termination; }
    void connect(URI uri) {
        if (!"ws".equals(uri.getScheme()) || !"127.0.0.1".equals(uri.getHost()) || uri.getPort() < 1 || uri.getPort() > 65535
                || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                || !uri.getRawPath().matches("/devtools/browser/[A-Za-z0-9-]{1,100}")) throw unavailable();
        try {
            WebSocket opened = http.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(10)).buildAsync(uri, this).get(10, TimeUnit.SECONDS);
            if (closed.get()) { opened.abort(); throw unavailable(); }
            socket = opened;
        } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw unavailable(); }
        catch (Exception ignored) { throw unavailable(); }
    }
    @Override public CompletableFuture<JsonNode> send(String method, Map<String, ?> params, String sessionId) {
        if (!METHODS.contains(method) || closed.get() || socket == null || pending.size() >= 64) return CompletableFuture.failedFuture(unavailable());
        int id = ids.incrementAndGet();
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("id", id); message.put("method", method); message.put("params", params);
        if (sessionId != null) message.put("sessionId", sessionId);
        CompletableFuture<JsonNode> result = new CompletableFuture<>(); pending.put(id, result);
        result.orTimeout(10, TimeUnit.SECONDS).whenComplete((value, error) -> pending.remove(id, result));
        try { socket.sendText(json.writeValueAsString(message), true).whenComplete((value, error) -> {
            if (error != null) result.completeExceptionally(unavailable());
        }); } catch (RuntimeException ignored) { result.completeExceptionally(unavailable()); }
        return result;
    }
    @Override public void onOpen(WebSocket socket) { socket.request(1); }
    @Override public CompletionStage<?> onText(WebSocket socket, CharSequence part, boolean last) {
        if (closed.get()) return CompletableFuture.completedFuture(null);
        String text = null;
        synchronized (fragments) {
            if (part.length() > MAX_MESSAGE - fragments.length()) { close(); return CompletableFuture.completedFuture(null); }
            fragments.append(part);
            if (last) { text = fragments.toString(); fragments.setLength(0); }
        }
        if (text != null) {
            try {
                JsonNode message = json.readTree(text);
                if (message.has("id")) {
                    CompletableFuture<JsonNode> result = pending.remove(message.path("id").asInt());
                    if (result != null) {
                        if (message.has("error")) result.completeExceptionally(unavailable());
                        else result.complete(message.path("result"));
                    }
                } else events.accept(text);
            } catch (RuntimeException ignored) { close(); }
        }
        socket.request(1); return CompletableFuture.completedFuture(null);
    }
    @Override public CompletionStage<?> onClose(WebSocket socket, int code, String reason) { close(); return CompletableFuture.completedFuture(null); }
    @Override public void onError(WebSocket socket, Throwable error) { close(); }
    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        pending.values().forEach(value -> value.completeExceptionally(unavailable())); pending.clear();
        synchronized (fragments) { fragments.setLength(0); }
        WebSocket opened = socket; if (opened != null) opened.abort();
        termination.complete(null);
    }
    private static IllegalStateException unavailable() { return new IllegalStateException("WEB_LOGIN_BROWSER_UNAVAILABLE"); }
}

interface BrowserCdp extends AutoCloseable {
    CompletableFuture<JsonNode> send(String method, Map<String, ?> params, String sessionId);
    CompletableFuture<Void> termination();
    @Override void close();
}
