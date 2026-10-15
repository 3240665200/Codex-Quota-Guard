package com.codexquotaguard.backend.officiallogin;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Observes only successful official account reads; never drives or bypasses login. */
final class BrowserCredentialCapture {
    private static final int MAX_EVENT_CHARS = 262_144;
    private final ObjectMapper mapper;
    private final Map<String, Candidate> pending = new LinkedHashMap<>();
    private final CompletableFuture<Credentials> result = new CompletableFuture<>();

    BrowserCredentialCapture(ObjectMapper mapper) { this.mapper = mapper; }

    CompletableFuture<Credentials> result() { return result; }

    synchronized void accept(String message) {
        if (result.isDone() || message == null || message.length() > MAX_EVENT_CHARS) return;
        try {
            JsonNode event = mapper.readTree(message);
            JsonNode params = event.path("params");
            String id = params.path("requestId").asString("");
            if (id.isEmpty() || id.length() > 200) return;
            String key = event.path("sessionId").asString("") + ":" + id;
            switch (event.path("method").asString("")) {
                case "Network.requestWillBeSent" -> {
                    pending.remove(key);
                    JsonNode request = params.path("request");
                    URI uri = officialRead(request.path("url").asString(""));
                    if (uri == null || !"GET".equals(request.path("method").asString(""))) return;
                    String authorization = header(request.path("headers"), "authorization");
                    String account = header(request.path("headers"), "chatgpt-account-id");
                    if (!validAuthorization(authorization) || !safeValue(account, 160)) return;
                    if (pending.size() >= 128) pending.clear();
                    pending.put(key, new Candidate(uri, authorization, account));
                }
                case "Network.responseReceived" -> {
                    Candidate candidate = pending.remove(key);
                    JsonNode response = params.path("response");
                    if (candidate != null && candidate.uri().equals(officialRead(response.path("url").asString("")))
                            && response.path("status").asDouble() == 200
                            && "application/json".equalsIgnoreCase(response.path("mimeType").asString(""))) {
                        pending.clear();
                        result.complete(new Credentials(candidate.authorization(), candidate.accountId()));
                    }
                }
                case "Network.loadingFailed" -> pending.remove(key);
                default -> { }
            }
        } catch (RuntimeException ignored) {
            // Malformed protocol data is ignored without retaining exception bodies or headers.
        }
    }

    synchronized void clear() { pending.clear(); result.cancel(false); }

    static String loginCookies(JsonNode response) {
        Map<String, String> accepted = new LinkedHashMap<>();
        int total = 0;
        for (JsonNode cookie : response.path("cookies")) {
            String name = cookie.path("name").asString("");
            String domain = cookie.path("domain").asString("");
            String value = cookie.path("value").asString("");
            if (!name.matches("__Secure-next-auth\\.session-token(?:\\.[0-9]+)?")
                    || !("chatgpt.com".equals(domain) || ".chatgpt.com".equals(domain))
                    || !cookie.path("secure").asBoolean() || !safeValue(value, 32768)
                    || value.indexOf(';') >= 0 || value.indexOf(',') >= 0) continue;
            if (accepted.containsKey(name) || accepted.size() >= 64) return "";
            total += name.length() + value.length() + 3;
            if (total > 32768) return "";
            accepted.put(name, name + "=" + value);
        }
        return String.join("; ", accepted.values());
    }

    private static URI officialRead(String text) {
        try {
            URI uri = URI.create(text);
            String path = uri.getRawPath();
            return "https".equals(uri.getScheme()) && "chatgpt.com".equals(uri.getHost())
                    && uri.getRawUserInfo() == null && uri.getRawFragment() == null
                    && (uri.getPort() == -1 || uri.getPort() == 443)
                    && ("/backend-api/accounts/sessions".equals(path)
                        || path != null && path.matches("/backend-api/accounts/check(?:/[A-Za-z0-9._-]{1,100})?"))
                    ? uri : null;
        } catch (IllegalArgumentException ignored) { return null; }
    }

    private static String header(JsonNode headers, String name) {
        String found = null;
        for (var entry : headers.properties()) {
            if (!name.equalsIgnoreCase(entry.getKey())) continue;
            if (found != null || !entry.getValue().isString()) return null;
            found = entry.getValue().asString();
        }
        return found;
    }

    private static boolean validAuthorization(String value) {
        return value != null && value.length() <= 16384 && value.matches("Bearer [^\\s\\x00-\\x1f\\x7f]+");
    }

    private static boolean safeValue(String value, int max) {
        return value != null && !value.isBlank() && value.length() <= max && value.chars().noneMatch(ch -> ch <= 32 || ch == 127);
    }

    record Credentials(String authorization, String requestedAccountId) {
        @Override public String toString() { return "OfficialBrowserCredentials[redacted]"; }
    }
    private record Candidate(URI uri, String authorization, String accountId) {
        @Override public String toString() { return "OfficialBrowserCandidate[redacted]"; }
    }
}
