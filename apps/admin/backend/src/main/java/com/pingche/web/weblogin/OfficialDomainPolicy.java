package com.pingche.web.weblogin;

import java.net.URI;
import java.util.List;

/** Exact hosts only; external SSO providers require separate support and are deliberately excluded. */
public final class OfficialDomainPolicy {
    public static final List<String> HOSTS = List.of("chatgpt.com", "auth.openai.com", "auth0.openai.com",
            "cdn.auth0.com", "cdn.openai.com", "oaistatic.com", "cdn.oaistatic.com", "challenges.cloudflare.com");
    private OfficialDomainPolicy() { }

    public static boolean allowed(String value) {
        try {
            URI uri = URI.create(value);
            return "https".equals(uri.getScheme()) && HOSTS.contains(uri.getHost())
                    && (uri.getPort() == -1 || uri.getPort() == 443)
                    && uri.getRawUserInfo() == null && uri.getRawFragment() == null;
        } catch (IllegalArgumentException | NullPointerException ignored) { return false; }
    }

    static String origin(String value) {
        return allowed(value) ? "https://" + URI.create(value).getHost() : "";
    }
}
