package com.pingche.web.codex;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** Only validates URLs supplied by the official child process; never fetches arbitrary URLs. */
final class CodexLoginUrl {
    private static final Set<String> HOSTS = Set.of("auth.openai.com", "chatgpt.com");

    private CodexLoginUrl() { }

    static boolean isBrowserLogin(String value) {
        if (value == null || value.length() > 8192) return false;
        try {
            URI url = URI.create(value);
            if (!"https".equals(url.getScheme()) || !HOSTS.contains(url.getHost())
                    || (url.getPort() != -1 && url.getPort() != 443)
                    || url.getRawUserInfo() != null || url.getRawFragment() != null) return false;
            Map<String, String> query = new HashMap<>();
            if (url.getRawQuery() == null) return false;
            for (String field : url.getRawQuery().split("&")) {
                String[] pair = field.split("=", 2);
                String key = URLDecoder.decode(pair[0], StandardCharsets.UTF_8);
                String content = pair.length == 2 ? URLDecoder.decode(pair[1], StandardCharsets.UTF_8) : "";
                if (query.putIfAbsent(key, content) != null) return false;
            }
            URI callback = URI.create(query.getOrDefault("redirect_uri", ""));
            return "http".equals(callback.getScheme())
                    && Set.of("localhost", "127.0.0.1").contains(callback.getHost())
                    && callback.getPort() == 1455 && "/auth/callback".equals(callback.getPath())
                    && callback.getRawUserInfo() == null && callback.getRawQuery() == null
                    && callback.getRawFragment() == null
                    && "code".equals(query.get("response_type"))
                    && "S256".equals(query.get("code_challenge_method"))
                    && query.getOrDefault("code_challenge", "").matches("[A-Za-z0-9_-]{43}")
                    && !query.getOrDefault("state", "").isBlank();
        } catch (IllegalArgumentException | NullPointerException ignored) {
            return false;
        }
    }
}
