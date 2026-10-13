package com.codexquotaguard.backend.controlplane;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/** Shared canonical origin for HTTP routing and the remembered-session AAD. */
public final class ControlPlaneOrigin {
    private ControlPlaneOrigin() { }

    public static URI normalize(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) throw invalid();
        try {
            URI raw = new URI(baseUrl.trim());
            String scheme = raw.getScheme() == null ? "" : raw.getScheme().toLowerCase(Locale.ROOT);
            if (!(scheme.equals("http") || scheme.equals("https")) || raw.getHost() == null
                    || raw.getUserInfo() != null || raw.getQuery() != null || raw.getFragment() != null
                    || (raw.getPath() != null && !raw.getPath().isEmpty() && !raw.getPath().equals("/"))) throw invalid();
            String host = raw.getHost().toLowerCase(Locale.ROOT);
            if (host.equals("0.0.0.0")) host = "127.0.0.1";
            int port = raw.getPort();
            if (port > 65535) throw invalid();
            if ((scheme.equals("http") && port == 80) || (scheme.equals("https") && port == 443)) port = -1;
            return new URI(scheme, null, host, port, "/", null, null);
        } catch (URISyntaxException invalidOrigin) { throw invalid(); }
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Control-plane base URL must be an HTTP or HTTPS origin.");
    }
}
