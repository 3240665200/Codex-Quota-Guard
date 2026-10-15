package com.pingche.web.common;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Locale;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class ManagementTransportGuard {
    private static final Set<String> LOOPBACK_ADDRESSES = Set.of(
            "127.0.0.1", "::1", "0:0:0:0:0:0:0:1", "::ffff:127.0.0.1",
            "0:0:0:0:0:ffff:127.0.0.1", "::ffff:7f00:1", "0:0:0:0:0:ffff:7f00:1");
    private static final Set<String> LOOPBACK_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]");
    private final boolean allowLoopbackHttp;

    public ManagementTransportGuard(@Value("${MANAGEMENT_ALLOW_LOOPBACK_HTTP:false}") boolean allowLoopbackHttp) {
        this.allowLoopbackHttp = allowLoopbackHttp;
    }

    public boolean isAllowed(HttpServletRequest request) {
        if (request.isSecure()) return true;
        if (!allowLoopbackHttp || request.getHeader("Forwarded") != null || request.getHeader("X-Forwarded-Proto") != null) return false;
        return contains(LOOPBACK_ADDRESSES, request.getRemoteAddr())
                && contains(LOOPBACK_ADDRESSES, request.getLocalAddr())
                && contains(LOOPBACK_HOSTS, request.getServerName());
    }

    private boolean contains(Set<String> allowed, String value) {
        return value != null && allowed.contains(value.toLowerCase(Locale.ROOT));
    }
}
