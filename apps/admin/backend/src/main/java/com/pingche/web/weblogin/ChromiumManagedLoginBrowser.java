package com.pingche.web.weblogin;

import com.pingche.web.auth.DomainException;
import com.pingche.web.proxy.ManagedProxyGateway;
import com.pingche.web.proxy.ProxyLease;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.net.*;
import java.net.http.HttpClient;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import java.util.function.BooleanSupplier;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Every task owns its browser process, profile and selected proxy lease. No normal browser profile is read. */
@Component
public final class ChromiumManagedLoginBrowser implements ManagedLoginBrowser {
    private static final String LOGIN_URL = "https://chatgpt.com/settings/security?view=sessions";
    private final ObjectMapper json;
    private final ManagedProxyGateway proxies;
    private final boolean enabled;
    private final String executable;
    private final boolean headless;
    private final Set<OwnedSession> sessions = ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService expiry = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "managed-login-expiry"); thread.setDaemon(true); return thread;
    });

    public ChromiumManagedLoginBrowser(ObjectMapper json, ManagedProxyGateway proxies,
            @Value("${WEB_LOGIN_BROWSER_ENABLED:false}") boolean enabled,
            @Value("${WEB_LOGIN_BROWSER_EXECUTABLE:}") String executable,
            @Value("${WEB_LOGIN_BROWSER_HEADLESS:true}") boolean headless) {
        this.json = json; this.proxies = proxies; this.enabled = enabled; this.executable = executable; this.headless = headless;
    }
    @Override public boolean available() {
        if (!enabled) return false;
        try { installedBrowser(); return true; } catch (DomainException ignored) { return false; }
    }
    @Override public Session open(UUID jobId, UUID proxyNodeId) {
        if (!enabled) throw unavailable("WEB_LOGIN_BROWSER_DISABLED");
        if (jobId == null || proxyNodeId == null) throw unavailable("PROXY_NODE_UNAVAILABLE");
        Path browser = installedBrowser();
        ProxyLease proxy = proxies.open(proxyNodeId, ManagedProxyGateway.Purpose.BROWSER_LOGIN);
        if (proxy == null) throw unavailable("PROXY_NODE_UNAVAILABLE");
        PrivateBrowserProfile profile = null; Process process = null; OwnedSession session = null;
        try {
            int port = proxyPort(proxy);
            profile = PrivateBrowserProfile.create(Path.of(System.getProperty("java.io.tmpdir")));
            ProcessBuilder builder = new ProcessBuilder(command(browser, profile.path(), port, headless));
            Map<String, String> childEnv = browserEnvironment(builder.environment(), profile.path(), headless);
            builder.environment().clear(); builder.environment().putAll(childEnv);
            process = builder.redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            PrivateBrowserProfile ownedProfile = profile; Process ownedProcess = process;
            AtomicReference<OwnedSession> active = new AtomicReference<>();
            HttpClient loopback = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER)
                    .proxy(new ProxySelector() {
                        @Override public List<Proxy> select(URI uri) { return List.of(Proxy.NO_PROXY); }
                        @Override public void connectFailed(URI uri, SocketAddress address, IOException failure) { }
                    }).build();
            RestrictedCdpConnection cdp = new RestrictedCdpConnection(json, loopback, event -> {
                OwnedSession current = active.get(); if (current != null) current.enqueue(event);
            });
            session = new OwnedSession(cdp, json, () -> {
                terminate(ownedProcess); ownedProfile.close(); proxy.close();
                OwnedSession current = active.get(); if (current != null) sessions.remove(current);
            }, System::nanoTime);
            active.set(session); sessions.add(session);
            session.proxyAlive = proxy::isAlive;
            OwnedSession owned = session;
            session.timeout = expiry.schedule(owned::close, 10, TimeUnit.MINUTES);
            session.health = expiry.scheduleWithFixedDelay(owned::checkQuietly, 1, 1, TimeUnit.SECONDS);
            ownedProcess.onExit().thenRun(owned::close);
            cdp.connect(awaitDebugger(ownedProfile, ownedProcess));
            session.initialize();
            return session;
        } catch (IOException | RuntimeException failure) {
            if (session != null) session.close();
            else { if (process != null) terminate(process); if (profile != null) profile.close(); proxy.close(); }
            throw unavailable("WEB_LOGIN_BROWSER_UNAVAILABLE");
        }
    }
    @PreDestroy public void shutdown() { sessions.forEach(OwnedSession::close); expiry.shutdownNow(); }

    static Map<String, String> browserEnvironment(Map<String, String> inherited, Path profile, boolean headless) {
        Map<String, String> values = new HashMap<>();
        for (String key : List.of("SystemRoot", "SYSTEMROOT", "WINDIR", "LANG", "LC_ALL")) {
            if (inherited.containsKey(key)) values.put(key, inherited.get(key));
        }
        String owned = profile.toAbsolutePath().normalize().toString();
        for (String key : List.of("HOME", "USERPROFILE", "TEMP", "TMP", "TMPDIR", "XDG_CONFIG_HOME", "XDG_CACHE_HOME")) values.put(key, owned);
        if (!headless) for (String key : List.of("DISPLAY", "WAYLAND_DISPLAY", "XAUTHORITY", "XDG_RUNTIME_DIR")) {
            if (inherited.containsKey(key)) values.put(key, inherited.get(key));
        }
        return Map.copyOf(values);
    }

    static List<String> command(Path browser, Path profile, int proxyPort, boolean headless) {
        if (proxyPort < 1 || proxyPort > 65535) throw unavailable("PROXY_NODE_UNAVAILABLE");
        List<String> args = new ArrayList<>(List.of(browser.toString(), "--user-data-dir=" + profile.toAbsolutePath().normalize(),
                "--remote-debugging-port=0", "--remote-debugging-address=127.0.0.1", "--no-first-run", "--no-default-browser-check",
                "--proxy-server=http://127.0.0.1:" + proxyPort, "--proxy-bypass-list=<-loopback>",
                "--disable-quic", "--force-webrtc-ip-handling-policy=disable_non_proxied_udp", "--dns-prefetch-disable",
                "--disable-background-networking", "--disable-component-update", "--disable-sync", "--disable-extensions",
                "--disable-default-apps", "--disable-breakpad", "--window-size=1024,768"));
        if (headless) args.add("--headless=new");
        args.add("about:blank"); return List.copyOf(args);
    }
    private Path installedBrowser() {
        if (executable != null && !executable.isBlank()) return verifiedExecutable(Path.of(executable));
        if (System.getProperty("os.name", "").startsWith("Windows")) {
            for (String base : List.of("ProgramFiles", "ProgramFiles(x86)", "LOCALAPPDATA")) {
                String root = System.getenv(base); if (root == null || root.isBlank()) continue;
                for (String relative : List.of("Microsoft/Edge/Application/msedge.exe", "Google/Chrome/Application/chrome.exe")) {
                    try { return verifiedExecutable(Path.of(root).resolve(relative)); } catch (DomainException ignored) { }
                }
            }
        }
        throw unavailable("WEB_LOGIN_BROWSER_NOT_FOUND");
    }
    private static Path verifiedExecutable(Path path) {
        try {
            Path normalized = path.toAbsolutePath().normalize();
            if (!path.isAbsolute() || !Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(normalized)
                    || !Files.isExecutable(normalized) || !normalized.equals(normalized.toRealPath())) throw new IOException();
            return normalized;
        } catch (IOException | RuntimeException ignored) { throw unavailable("WEB_LOGIN_BROWSER_NOT_FOUND"); }
    }
    private static int proxyPort(ProxyLease lease) {
        List<Proxy> choices = lease.proxySelector().select(URI.create("https://chatgpt.com"));
        if (choices == null || choices.size() != 1 || choices.get(0).type() != Proxy.Type.HTTP
                || !(choices.get(0).address() instanceof InetSocketAddress address) || address.isUnresolved()
                || !"127.0.0.1".equals(address.getAddress().getHostAddress()) || address.getPort() < 1) throw unavailable("PROXY_NODE_UNAVAILABLE");
        return address.getPort();
    }
    private static URI awaitDebugger(PrivateBrowserProfile profile, Process process) {
        long start = System.nanoTime();
        while (process.isAlive() && System.nanoTime() - start < TimeUnit.SECONDS.toNanos(20)) {
            try { return profile.debuggerUri(); }
            catch (IllegalStateException notReady) {
                try { Thread.sleep(50); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); break; }
            }
        }
        throw unavailable("WEB_LOGIN_BROWSER_UNAVAILABLE");
    }
    private static void terminate(Process process) {
        boolean wasInterrupted = Thread.interrupted();
        try {
            List<ProcessHandle> descendants = process.descendants().toList();
            descendants.forEach(ProcessHandle::destroy);
            if (process.isAlive()) process.destroy();
            try { if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly().waitFor(2, TimeUnit.SECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); process.destroyForcibly(); }
            descendants.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
        } finally { if (wasInterrupted) Thread.currentThread().interrupt(); }
    }
    private static DomainException unavailable(String code) {
        return new DomainException(code, "服务器登录浏览器不可用，请检查浏览器与所选代理配置。", HttpStatus.SERVICE_UNAVAILABLE);
    }

    static final class OwnedSession implements Session {
        private final BrowserCdp cdp;
        private final ObjectMapper json;
        private final Runnable cleanup;
        private final LongSupplier nanos;
        private final long started;
        private final BrowserCredentialCapture capture;
        private final AtomicBoolean closed = new AtomicBoolean();
        private final Set<String> attached = ConcurrentHashMap.newKeySet();
        private final ThreadPoolExecutor events = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(64), r -> { Thread t = new Thread(r, "managed-login-events"); t.setDaemon(true); return t; });
        private volatile String mainSession;
        private volatile String pageOrigin = "";
        private volatile String errorCode;
        private volatile ScheduledFuture<?> timeout;
        private volatile ScheduledFuture<?> health;
        private volatile BooleanSupplier proxyAlive = () -> true;
        private volatile String cookieFingerprint;

        OwnedSession(BrowserCdp cdp, ObjectMapper json, Runnable cleanup, LongSupplier nanos) {
            this.cdp = cdp; this.json = json; this.cleanup = cleanup; this.nanos = nanos; this.started = nanos.getAsLong();
            this.capture = new BrowserCredentialCapture(json);
            cdp.termination().thenRun(this::close);
        }
        void initialize() {
            call("Browser.setDownloadBehavior", Map.of("behavior", "deny"), null);
            JsonNode targets = call("Target.getTargets", Map.of(), null);
            String target = null;
            for (JsonNode info : targets.path("targetInfos")) {
                if ("page".equals(info.path("type").asString("")) && "about:blank".equals(info.path("url").asString(""))) {
                    target = info.path("targetId").asString(""); break;
                }
            }
            if (target == null || target.isBlank()) throw failure();
            mainSession = call("Target.attachToTarget", Map.of("targetId", target, "flatten", true), null).path("sessionId").asString("");
            if (mainSession.isBlank()) throw failure();
            attached.add(mainSession); configure(mainSession, true);
            call("Target.setAutoAttach", Map.of("autoAttach", true, "waitForDebuggerOnStart", true, "flatten", true), null);
            call("Page.navigate", Map.of("url", LOGIN_URL), mainSession);
        }
        private void configure(String session, boolean page) {
            call("Network.enable", Map.of("maxTotalBufferSize", 262144, "maxResourceBufferSize", 65536), session);
            call("Fetch.enable", Map.of("patterns", List.of(Map.of("urlPattern", "*", "requestStage", "Request"))), session);
            call("Target.setAutoAttach", Map.of("autoAttach", true, "waitForDebuggerOnStart", true, "flatten", true), session);
            if (page) {
                call("Page.enable", Map.of(), session);
                call("Page.setInterceptFileChooserDialog", Map.of("enabled", true), session);
                call("Emulation.setDeviceMetricsOverride", Map.of("width", WIDTH, "height", HEIGHT, "deviceScaleFactor", 1, "mobile", false), session);
            }
        }
        void enqueue(String event) {
            if (closed.get()) return;
            if (event.length() > 262144) { close(); return; }
            try { events.execute(() -> { try { accept(event); } catch (RuntimeException ignored) { close(); } }); }
            catch (RejectedExecutionException ignored) { close(); }
        }
        synchronized void accept(String event) {
            if (closed.get()) return;
            JsonNode message = json.readTree(event); JsonNode params = message.path("params");
            String method = message.path("method").asString(""); String session = message.path("sessionId").asString("");
            if ("Target.attachedToTarget".equals(method)) {
                String child = params.path("sessionId").asString("");
                if (child.isBlank() || child.length() > 200 || attached.size() >= 24) { close(); return; }
                String type = params.path("targetInfo").path("type").asString("");
                if (!Set.of("page", "iframe", "worker", "service_worker", "shared_worker").contains(type)) { close(); return; }
                if (attached.add(child)) configure(child, "page".equals(type) || "iframe".equals(type));
                call("Runtime.runIfWaitingForDebugger", Map.of(), child); return;
            }
            if (!attached.contains(session)) return;
            switch (method) {
                case "Fetch.requestPaused" -> {
                    String requestId = params.path("requestId").asString("");
                    String url = params.path("request").path("url").asString("");
                    if (requestId.isBlank() || requestId.length() > 200) { close(); return; }
                    if (OfficialDomainPolicy.allowed(url)) call("Fetch.continueRequest", Map.of("requestId", requestId), session);
                    else { errorCode = blockedCode(url); call("Fetch.failRequest", Map.of("requestId", requestId, "errorReason", "BlockedByClient"), session); }
                }
                case "Network.requestWillBeSent" -> {
                    String url = params.path("request").path("url").asString("");
                    if (!OfficialDomainPolicy.allowed(url)) {
                        errorCode = blockedCode(url);
                        if (!(url.startsWith("https:") || url.startsWith("http:") || url.startsWith("data:") || url.startsWith("blob:"))) close();
                        return;
                    }
                    if (url.startsWith("https://auth.openai.com/") || url.startsWith("https://auth0.openai.com/")
                            || url.startsWith("https://chatgpt.com/api/auth/signout") || url.contains("/logout")) invalidate();
                    capture.accept(event);
                    if (capture.candidate() == null) cookieFingerprint = null;
                }
                case "Network.responseReceived", "Network.loadingFailed" -> {
                    BrowserCredentialCapture.Candidate before = capture.candidate();
                    capture.accept(event);
                    if (capture.candidate() != null && capture.candidate() != before) {
                        String cookie = currentCookie();
                        cookieFingerprint = cookie.isBlank() ? null : fingerprint(cookie);
                        errorCode = null;
                    }
                }
                case "Page.frameNavigated" -> {
                    JsonNode frame = params.path("frame");
                    if (!frame.has("parentId") && mainSession.equals(session)) {
                        String url = frame.path("url").asString("");
                        if (OfficialDomainPolicy.allowed(url)) {
                            pageOrigin = OfficialDomainPolicy.origin(url);
                            if (!"https://chatgpt.com".equals(pageOrigin)) invalidate();
                        }
                        else if (!"about:blank".equals(url) && !url.startsWith("chrome-error://")) { errorCode = blockedCode(url); close(); }
                    }
                }
                default -> { }
            }
        }
        @Override public synchronized Frame frame() {
            check();
            JsonNode screenshot = call("Page.captureScreenshot", Map.of("format", "jpeg", "quality", 65, "fromSurface", true, "captureBeyondViewport", false), mainSession);
            String data = screenshot.path("data").asString("");
            if (data.length() > RestrictedCdpConnection.MAX_MESSAGE - 4096 || data.length() < 8) { close(); throw failure(); }
            try {
                byte[] bytes = Base64.getDecoder().decode(data);
                if (bytes.length < 4 || (bytes[0] & 255) != 255 || (bytes[1] & 255) != 216) throw new IllegalArgumentException();
            } catch (IllegalArgumentException ignored) { close(); throw failure(); }
            return new Frame(WIDTH, HEIGHT, data, pageOrigin, capture() != null, errorCode);
        }
        @Override public synchronized void input(Input input) {
            BrowserInputPolicy.validate(input); check();
            switch (input.type()) {
                case "OPEN_SESSIONS" -> { invalidate(); errorCode = null; call("Page.navigate", Map.of("url", LOGIN_URL), mainSession); }
                case "TEXT" -> call("Input.insertText", Map.of("text", input.text()), mainSession);
                case "CLICK" -> {
                    call("Input.dispatchMouseEvent", Map.of("type", "mousePressed", "x", input.x(), "y", input.y(), "button", "left", "clickCount", 1), mainSession);
                    call("Input.dispatchMouseEvent", Map.of("type", "mouseReleased", "x", input.x(), "y", input.y(), "button", "left", "clickCount", 1), mainSession);
                }
                case "WHEEL" -> call("Input.dispatchMouseEvent", Map.of("type", "mouseWheel", "x", input.x(), "y", input.y(), "deltaX", 0, "deltaY", input.deltaY()), mainSession);
                case "KEY" -> {
                    String key = "Space".equals(input.key()) ? " " : input.key();
                    Map<String, Object> params = new HashMap<>(); params.put("type", "keyDown"); params.put("key", key);
                    params.put("modifiers", input.ctrl() ? 2 : input.shift() ? 8 : 0);
                    params.put("windowsVirtualKeyCode", keyCode(input.key()));
                    call("Input.dispatchKeyEvent", params, mainSession); params.put("type", "keyUp");
                    call("Input.dispatchKeyEvent", params, mainSession);
                }
                default -> throw new IllegalArgumentException("WEB_LOGIN_INPUT_INVALID");
            }
        }
        @Override public synchronized Credentials capture() {
            check(); BrowserCredentialCapture.Candidate candidate = capture.candidate();
            String expected = cookieFingerprint;
            if (candidate == null || expected == null) return null;
            String cookie = currentCookie();
            if (cookie.isBlank() || !expected.equals(fingerprint(cookie))) { invalidate(); errorCode = "WEB_LOGIN_SESSION_CHANGED"; return null; }
            return new Credentials(cookie, candidate.authorization(), candidate.accountId());
        }
        private String currentCookie() {
            return BrowserCredentialCapture.loginCookies(call("Network.getCookies", Map.of("urls", List.of("https://chatgpt.com")), mainSession));
        }
        private void invalidate() { capture.clear(); cookieFingerprint = null; }
        private static String fingerprint(String cookie) {
            try { return Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(cookie.getBytes(StandardCharsets.UTF_8))); }
            catch (NoSuchAlgorithmException unavailable) { throw failure(); }
        }
        private JsonNode call(String method, Map<String, ?> params, String session) {
            check();
            try { return cdp.send(method, params, session).get(11, TimeUnit.SECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); close(); throw failure(); }
            catch (Exception ignored) { close(); throw failure(); }
        }
        private void check() {
            if (closed.get() || !proxyAlive.getAsBoolean() || nanos.getAsLong() - started >= TimeUnit.MINUTES.toNanos(10)) { close(); throw failure(); }
        }
        private void checkQuietly() { try { check(); } catch (RuntimeException ignored) { close(); } }
        @Override public void close() {
            if (!closed.compareAndSet(false, true)) return;
            if (timeout != null) timeout.cancel(false);
            if (health != null) health.cancel(false);
            events.shutdownNow(); attached.clear(); invalidate(); pageOrigin = "";
            try { cdp.close(); } finally { cleanup.run(); }
        }
        private static String blockedCode(String url) {
            return url.startsWith("https://accounts.google.com/") || url.startsWith("https://login.microsoftonline.com/") || url.startsWith("https://appleid.apple.com/")
                    ? "WEB_LOGIN_EXTERNAL_PROVIDER_UNSUPPORTED" : "WEB_LOGIN_DESTINATION_BLOCKED";
        }
        private static int keyCode(String key) {
            return switch (key) {
                case "Tab" -> 9; case "Enter" -> 13; case "Backspace" -> 8; case "Delete" -> 46; case "Escape" -> 27;
                case "ArrowLeft" -> 37; case "ArrowUp" -> 38; case "ArrowRight" -> 39; case "ArrowDown" -> 40;
                case "Home" -> 36; case "End" -> 35; case "PageUp" -> 33; case "PageDown" -> 34; case "Space" -> 32; case "a" -> 65;
                default -> throw new IllegalArgumentException("WEB_LOGIN_INPUT_INVALID");
            };
        }
        private static IllegalStateException failure() { return new IllegalStateException("WEB_LOGIN_BROWSER_UNAVAILABLE"); }
    }
}
