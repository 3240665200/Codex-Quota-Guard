package com.pingche.web.weblogin;

import static org.assertj.core.api.Assertions.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class ManagedBrowserSessionTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void sessionShowsOnlyBoundedFramesAndDispatchesAllowedInputWithoutArbitraryScript() {
        FakeCdp cdp = new FakeCdp(); AtomicInteger cleanup = new AtomicInteger();
        var session = new ChromiumManagedLoginBrowser.OwnedSession(cdp, json, cleanup::incrementAndGet, () -> 0L);
        session.initialize();
        session.input(new ManagedLoginBrowser.Input("TEXT", null, null, null, null, "synthetic-password", false, false));
        assertThat(cdp.methods).contains("Fetch.enable", "Browser.setDownloadBehavior", "Input.insertText");
        assertThat(cdp.methods).doesNotContain("Runtime.evaluate", "Input.setFileInputFiles");
        assertThat(session.frame().jpegBase64()).isEqualTo("/9j/2Q==");
        cdp.image = "x".repeat(2 * 1024 * 1024);
        assertThatThrownBy(session::frame).hasMessageNotContaining("xxxx");
        session.close(); session.close();
        assertThat(cleanup.get()).isEqualTo(1);
        assertThat(cdp.closed).isTrue();
    }

    @Test
    void rejectsForeignRequestsBeforeContinueAndDoesNotCaptureForeignSessionEvents() {
        FakeCdp cdp = new FakeCdp();
        var session = new ChromiumManagedLoginBrowser.OwnedSession(cdp, json, () -> {}, () -> 0L);
        session.initialize();
        session.accept(event("Fetch.requestPaused", "owned", Map.of("requestId", "blocked", "request", Map.of("url", "https://accounts.google.com/login"))));
        assertThat(cdp.methods).contains("Fetch.failRequest");
        assertThat(session.frame().errorCode()).isEqualTo("WEB_LOGIN_EXTERNAL_PROVIDER_UNSUPPORTED");
        session.accept(event("Network.requestWillBeSent", "foreign", Map.of("requestId", "r", "request", Map.of(
                "method", "GET", "url", "https://chatgpt.com/backend-api/accounts/sessions", "headers", Map.of("Authorization", "Bearer synthetic")))));
        assertThat(session.capture()).isNull();
        session.close();
    }

    @Test
    void hardDeadlineClosesAnOtherwiseUnusedSession() {
        FakeCdp cdp = new FakeCdp(); var now = new java.util.concurrent.atomic.AtomicLong();
        AtomicInteger cleanup = new AtomicInteger();
        var session = new ChromiumManagedLoginBrowser.OwnedSession(cdp, json, cleanup::incrementAndGet, now::get);
        session.initialize(); now.set(TimeUnit.MINUTES.toNanos(11));
        assertThatThrownBy(session::frame).hasMessage("WEB_LOGIN_BROWSER_UNAVAILABLE");
        assertThat(cleanup.get()).isEqualTo(1);
    }

    @Test
    void changedCookiesInvalidateCapturedAuthorizationUntilANewSuccessfulRead() {
        FakeCdp cdp = new FakeCdp();
        var session = new ChromiumManagedLoginBrowser.OwnedSession(cdp, json, () -> {}, () -> 0L);
        session.initialize();
        successfulRead(session, "Bearer first");
        assertThat(session.capture().authorization()).isEqualTo("Bearer first");
        cdp.cookie = "second-cookie";
        assertThat(session.capture()).isNull();
        assertThat(session.frame().readyToSave()).isFalse();
        successfulRead(session, "Bearer second");
        assertThat(session.capture().authorization()).isEqualTo("Bearer second");
        session.input(new ManagedLoginBrowser.Input("OPEN_SESSIONS", null, null, null, null, null, false, false));
        assertThat(session.capture()).isNull();
        session.close();
    }

    @Test
    void blockedOptionalSubresourcesDoNotAbortTheHumanLoginWhileNavigationStillFailsClosed() {
        FakeCdp cdp = new FakeCdp();
        var session = new ChromiumManagedLoginBrowser.OwnedSession(cdp, json, () -> {}, () -> 0L);
        session.initialize();
        session.accept(event("Fetch.requestPaused", "owned", Map.of("requestId", "tracker", "resourceType", "Script",
                "request", Map.of("url", "https://analytics.example.test/script.js?private=synthetic"))));
        assertThat(cdp.methods).contains("Fetch.failRequest");
        assertThat(session.frame().errorCode()).isNull();
        session.accept(event("Fetch.requestPaused", "owned", Map.of("requestId", "navigation", "resourceType", "Document",
                "request", Map.of("url", "https://evil.test/login?private=synthetic"))));
        assertThat(session.frame().errorCode()).isEqualTo("WEB_LOGIN_DESTINATION_BLOCKED");
        session.close();
    }

    @Test
    void cookieChangeWhileTheAccountRequestIsInFlightCannotBindAnOldBearerToANewCookie() {
        FakeCdp cdp = new FakeCdp();
        var session = new ChromiumManagedLoginBrowser.OwnedSession(cdp, json, () -> {}, () -> 0L);
        session.initialize(); String url = "https://chatgpt.com/backend-api/accounts/sessions";
        session.accept(event("Network.requestWillBeSent", "owned", Map.of("requestId", "inflight", "request", Map.of("method", "GET", "url", url,
                "headers", Map.of("Authorization", "Bearer first", "Chatgpt-Account-Id", "synthetic-account")))));
        cdp.cookie = "changed-before-response";
        session.accept(event("Network.responseReceived", "owned", Map.of("requestId", "inflight", "response", Map.of("status", 200, "mimeType", "application/json", "url", url))));
        assertThat(session.capture()).isNull();
        session.close();
    }

    private void successfulRead(ChromiumManagedLoginBrowser.OwnedSession session, String authorization) {
        String url = "https://chatgpt.com/backend-api/accounts/sessions";
        session.accept(event("Network.requestWillBeSent", "owned", Map.of("requestId", "r", "request", Map.of("method", "GET", "url", url,
                "headers", Map.of("Authorization", authorization, "Chatgpt-Account-Id", "synthetic-account")))));
        session.accept(event("Network.responseReceived", "owned", Map.of("requestId", "r", "response", Map.of("status", 200, "mimeType", "application/json", "url", url))));
    }

    private String event(String method, String session, Map<String, ?> params) {
        return json.writeValueAsString(Map.of("method", method, "sessionId", session, "params", params));
    }
    private final class FakeCdp implements BrowserCdp {
        final List<String> methods = new ArrayList<>();
        final CompletableFuture<Void> termination = new CompletableFuture<>();
        String image = "/9j/2Q=="; String cookie = "first-cookie"; boolean closed;
        @Override public CompletableFuture<JsonNode> send(String method, Map<String, ?> params, String session) {
            methods.add(method);
            Object response = switch (method) {
                case "Target.getTargets" -> Map.of("targetInfos", List.of(Map.of("type", "page", "url", "about:blank", "targetId", "target")));
                case "Target.attachToTarget" -> Map.of("sessionId", "owned");
                case "Page.captureScreenshot" -> Map.of("data", image);
                case "Network.getCookies" -> Map.of("cookies", List.of(Map.of("name", "__Secure-next-auth.session-token", "domain", "chatgpt.com", "secure", true, "value", cookie)));
                default -> Map.of();
            };
            return CompletableFuture.completedFuture(json.valueToTree(response));
        }
        @Override public CompletableFuture<Void> termination() { return termination; }
        @Override public void close() { closed = true; termination.complete(null); }
    }
}
