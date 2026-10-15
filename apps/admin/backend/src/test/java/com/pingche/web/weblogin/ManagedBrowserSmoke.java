package com.pingche.web.weblogin;

import java.net.*;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Explicit opt-in local smoke entry point; never visits an official site or reads an existing profile. */
public final class ManagedBrowserSmoke {
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Supply one explicit browser executable path");
        Path browser = Path.of(args[0]).toAbsolutePath().normalize();
        if (!Files.isRegularFile(browser, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(browser)) throw new IllegalArgumentException("Browser unavailable");
        var profile = PrivateBrowserProfile.create(Path.of(System.getProperty("java.io.tmpdir")));
        Process process = null; RestrictedCdpConnection cdp = null;
        try {
            // Port 9 intentionally has no proxy listener. All external networking fails closed, including background requests.
            var builder = new ProcessBuilder(ChromiumManagedLoginBrowser.command(browser, profile.path(), 9, true));
            var environment = ChromiumManagedLoginBrowser.browserEnvironment(builder.environment(), profile.path(), true);
            builder.environment().clear(); builder.environment().putAll(environment);
            process = builder.redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            URI debugger = null; long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            while (process.isAlive() && System.nanoTime() < deadline && debugger == null) {
                try { debugger = profile.debuggerUri(); } catch (IllegalStateException notReady) { Thread.sleep(50); }
            }
            if (debugger == null) throw new IllegalStateException("Owned browser did not expose its loopback endpoint");
            var reference = new AtomicReference<RestrictedCdpConnection>();
            ObjectMapper json = new ObjectMapper();
            HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER)
                    .proxy(new ProxySelector() {
                        @Override public List<Proxy> select(URI uri) { return List.of(Proxy.NO_PROXY); }
                        @Override public void connectFailed(URI uri, SocketAddress address, java.io.IOException failure) { }
                    }).build();
            cdp = new RestrictedCdpConnection(json, http, event -> {
                JsonNode message = json.readTree(event);
                if ("Fetch.requestPaused".equals(message.path("method").asString(""))) {
                    reference.get().send("Fetch.failRequest", Map.of("requestId", message.path("params").path("requestId").asString(), "errorReason", "BlockedByClient"),
                            message.path("sessionId").asString());
                }
            });
            reference.set(cdp); cdp.connect(debugger);
            JsonNode targets = call(cdp, "Target.getTargets", Map.of(), null);
            String target = null;
            for (JsonNode info : targets.path("targetInfos")) if ("about:blank".equals(info.path("url").asString(""))) target = info.path("targetId").asString();
            if (target == null) throw new IllegalStateException("Owned blank target missing");
            String session = call(cdp, "Target.attachToTarget", Map.of("targetId", target, "flatten", true), null).path("sessionId").asString();
            call(cdp, "Browser.setDownloadBehavior", Map.of("behavior", "deny"), null);
            call(cdp, "Fetch.enable", Map.of("patterns", List.of(Map.of("urlPattern", "http*", "requestStage", "Request"))), session);
            call(cdp, "Emulation.setDeviceMetricsOverride", Map.of("width", 1024, "height", 768, "deviceScaleFactor", 1, "mobile", false), session);
            call(cdp, "Page.enable", Map.of(), session);
            String blank = screenshot(cdp, session);
            String html = "<html><body style='font:28px sans-serif;padding:40px'><h1>Local browser smoke</h1><input autofocus placeholder='Local synthetic input'></body></html>";
            String data = "data:text/html;base64," + Base64.getEncoder().encodeToString(html.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            call(cdp, "Page.navigate", Map.of("url", data), session);
            try (var inspector = new SmokeInspector(http, debugger, target, json)) {
                long readyBy = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                boolean ready;
                do {
                    ready = inspector.evaluate("location.protocol === 'data:' && document.readyState === 'complete' && document.querySelector('input') === document.activeElement");
                    if (!ready) Thread.sleep(50);
                } while (!ready && System.nanoTime() < readyBy);
                if (!ready) throw new IllegalStateException("Synthetic input was not ready");
                call(cdp, "Input.insertText", Map.of("text", "local-smoke-only"), session);
                if (!inspector.evaluate("location.protocol === 'data:' && document.querySelector('input').value === 'local-smoke-only'")) {
                    throw new IllegalStateException("Synthetic input value mismatch");
                }
            }
            String local = screenshot(cdp, session);
            if (blank.equals(local)) throw new IllegalStateException("Synthetic page did not change the frame");
            System.out.println("PASS: owned loopback CDP, 1024x768 in-memory JPEG, synthetic page/input; no official navigation, no image file");
        } finally {
            if (cdp != null) cdp.close();
            if (process != null) ChromiumManagedLoginBrowser.terminate(process);
            profile.close();
            if (Files.exists(profile.path(), LinkOption.NOFOLLOW_LINKS)) throw new IllegalStateException("Owned temporary profile cleanup failed");
        }
    }
    private static JsonNode call(RestrictedCdpConnection cdp, String method, Map<String, ?> params, String session) throws Exception {
        return cdp.send(method, params, session).get(11, TimeUnit.SECONDS);
    }
    private static String screenshot(RestrictedCdpConnection cdp, String session) throws Exception {
        String data = call(cdp, "Page.captureScreenshot", Map.of("format", "jpeg", "quality", 60, "captureBeyondViewport", false), session).path("data").asString();
        if (data.length() < 8 || data.length() >= 2 * 1024 * 1024) throw new IllegalStateException("Frame out of bounds");
        return data;
    }

    /** Test-main-only readonly inspector. Production transport continues to reject Runtime.evaluate. */
    private static final class SmokeInspector implements WebSocket.Listener, AutoCloseable {
        private final ObjectMapper json;
        private final Map<Integer, CompletableFuture<JsonNode>> pending = new java.util.concurrent.ConcurrentHashMap<>();
        private final AtomicInteger ids = new AtomicInteger();
        private final StringBuilder fragments = new StringBuilder();
        private final WebSocket socket;
        private final String session;
        SmokeInspector(HttpClient http, URI ownedDebugger, String ownedTarget, ObjectMapper json) throws Exception {
            this.json = json;
            socket = http.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(5)).buildAsync(ownedDebugger, this).get(5, TimeUnit.SECONDS);
            session = send("Target.attachToTarget", Map.of("targetId", ownedTarget, "flatten", true), null).path("sessionId").asString();
        }
        boolean evaluate(String fixedTestExpression) throws Exception {
            return send("Runtime.evaluate", Map.of("expression", fixedTestExpression, "returnByValue", true), session)
                    .path("result").path("value").asBoolean();
        }
        private JsonNode send(String method, Map<String, ?> params, String session) throws Exception {
            int id = ids.incrementAndGet();
            Map<String, Object> message = new LinkedHashMap<>(Map.of("id", id, "method", method, "params", params));
            if (session != null) message.put("sessionId", session);
            var result = new CompletableFuture<JsonNode>(); pending.put(id, result);
            socket.sendText(json.writeValueAsString(message), true).get(5, TimeUnit.SECONDS);
            return result.get(5, TimeUnit.SECONDS);
        }
        @Override public void onOpen(WebSocket webSocket) { webSocket.request(1); }
        @Override public CompletionStage<?> onText(WebSocket webSocket, CharSequence part, boolean last) {
            if (part.length() > 65536 - fragments.length()) { close(); return CompletableFuture.completedFuture(null); }
            fragments.append(part);
            if (last) {
                JsonNode message = json.readTree(fragments.toString()); fragments.setLength(0);
                var result = pending.remove(message.path("id").asInt());
                if (result != null) {
                    if (message.has("error")) result.completeExceptionally(new IllegalStateException("Synthetic inspection failed"));
                    else result.complete(message.path("result"));
                }
            }
            webSocket.request(1); return CompletableFuture.completedFuture(null);
        }
        @Override public void close() { socket.abort(); pending.clear(); fragments.setLength(0); }
    }
}
