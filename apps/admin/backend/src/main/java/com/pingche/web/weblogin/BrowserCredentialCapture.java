package com.pingche.web.weblogin;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Observes only successful official account reads; never submits or automates a login. */
final class BrowserCredentialCapture {
    private final ObjectMapper json;
    private final Map<String, Candidate> pending = new LinkedHashMap<>();
    private Candidate candidate;
    BrowserCredentialCapture(ObjectMapper json) { this.json = json; }
    synchronized Candidate candidate() { return candidate; }

    synchronized void accept(String message) {
        if (message == null || message.length() > 262144) return;
        try {
            JsonNode event = json.readTree(message);
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
                    candidate = null;
                    String auth = header(request.path("headers"), "authorization");
                    String account = header(request.path("headers"), "chatgpt-account-id");
                    if (auth == null || auth.length() > 16384 || !auth.matches("Bearer [^\\s\\x00-\\x1f\\x7f]+") || !safe(account, 160)) return;
                    if (pending.size() >= 128) pending.clear();
                    pending.put(key, new Candidate(uri, auth, account));
                }
                case "Network.responseReceived" -> {
                    Candidate request = pending.remove(key);
                    JsonNode response = params.path("response");
                    if (request != null && request.uri().equals(officialRead(response.path("url").asString("")))
                            && response.path("status").asDouble() == 200
                            && "application/json".equalsIgnoreCase(response.path("mimeType").asString(""))) {
                        candidate = request; pending.clear();
                    }
                }
                case "Network.loadingFailed" -> pending.remove(key);
                default -> { }
            }
        } catch (RuntimeException ignored) { /* Protocol failures never retain bodies or headers in logs. */ }
    }
    synchronized void clear() { pending.clear(); candidate = null; }

    static String loginCookies(JsonNode response) {
        Map<String, String> accepted = new LinkedHashMap<>();
        int total = 0;
        for (JsonNode cookie : response.path("cookies")) {
            String name = cookie.path("name").asString("");
            String domain = cookie.path("domain").asString("");
            if (!name.matches("__Secure-next-auth\\.session-token(?:\\.[0-9]+)?")
                    || !("chatgpt.com".equals(domain) || ".chatgpt.com".equals(domain)) || !cookie.path("secure").asBoolean()) continue;
            String value = cookie.path("value").asString("");
            if (!safe(value, 32768) || value.indexOf(';') >= 0 || value.indexOf(',') >= 0) continue;
            if (accepted.containsKey(name) || accepted.size() >= 64) return "";
            total += name.length() + value.length() + 3;
            if (total > 32768) return "";
            accepted.put(name, name + "=" + value);
        }
        return String.join("; ", accepted.values());
    }
    private static URI officialRead(String value) {
        try {
            URI uri = URI.create(value);
            String path = uri.getRawPath();
            return OfficialDomainPolicy.allowed(value) && "chatgpt.com".equals(uri.getHost())
                    && ("/backend-api/accounts/sessions".equals(path)
                    || path != null && path.matches("/backend-api/accounts/check(?:/[A-Za-z0-9._-]{1,100})?")) ? uri : null;
        } catch (IllegalArgumentException ignored) { return null; }
    }
    private static String header(JsonNode headers, String name) {
        String value = null;
        for (var entry : headers.properties()) {
            if (!name.equalsIgnoreCase(entry.getKey())) continue;
            if (value != null || !entry.getValue().isString()) return null;
            value = entry.getValue().asString();
        }
        return value;
    }
    private static boolean safe(String value, int max) {
        return value != null && !value.isBlank() && value.length() <= max && value.chars().noneMatch(c -> c <= 32 || c == 127);
    }
    record Candidate(URI uri, String authorization, String accountId) {
        @Override public String toString() { return "ManagedBrowserCandidate[redacted]"; }
    }
}
