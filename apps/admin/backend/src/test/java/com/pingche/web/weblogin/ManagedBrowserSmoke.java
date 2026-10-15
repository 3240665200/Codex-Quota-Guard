package com.pingche.web.weblogin;

import java.net.*;
import java.net.http.HttpClient;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
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
            call(cdp, "Input.insertText", Map.of("text", "local-smoke-only"), session);
            String local = screenshot(cdp, session);
            if (blank.equals(local)) throw new IllegalStateException("Synthetic page did not change the frame");
            System.out.println("PASS: owned loopback CDP, 1024x768 in-memory JPEG, synthetic page/input; no official navigation, no image file");
        } finally {
            if (cdp != null) cdp.close();
            if (process != null) {
                var children = process.descendants().toList(); children.forEach(ProcessHandle::destroy);
                process.destroy(); if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly().waitFor(2, TimeUnit.SECONDS);
                children.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
            }
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
}
