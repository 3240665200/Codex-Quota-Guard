package com.pingche.web.sessionbinding;

import com.pingche.web.common.SecurityErrorWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

/** Default-deny binding gate for client business APIs; meter and administrator roles keep their own boundaries. */
public class ClientAccessFilter extends OncePerRequestFilter {
    private static final String ROOT = "/api/v1/client";
    private static final String RESOURCE_ID = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";
    private final ClientAccessService access;
    private final SecurityErrorWriter errors;

    public ClientAccessFilter(ClientAccessService access, SecurityErrorWriter errors) {
        this.access = access;
        this.errors = errors;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = UrlPathHelper.defaultInstance.getPathWithinApplication(request);
        if (!path.equals(ROOT) && !path.startsWith(ROOT + "/")) return true;
        String route = path.substring(ROOT.length());
        return switch (request.getMethod()) {
            case "GET" -> route.equals("/access") || route.equals("/login-assistance")
                    || route.equals("/login-assistance/context")
                    || route.equals("/login-assistance/authenticator")
                    || route.equals("/login-assistance/listeners/current") || route.equals("/login-assistance/events")
                    || route.matches("/login-assistance/listeners/" + RESOURCE_ID)
                    || route.equals("/session-bindings/current") || route.matches("/session-bindings/attempts/" + RESOURCE_ID);
            case "POST" -> route.equals("/meter/enroll") || route.equals("/usage/events")
                    || route.equals("/login-assistance/authenticator/code")
                    || route.equals("/login-assistance/listeners") || route.equals("/session-bindings/attempts")
                    || route.matches("/session-bindings/attempts/" + RESOURCE_ID + "/(prepare|detect|confirm|cancel)")
                    || route.matches("/session-bindings/bindings/" + RESOURCE_ID + "/release");
            case "DELETE" -> route.matches("/login-assistance/listeners/" + RESOURCE_ID);
            default -> false;
        };
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        // Missing/invalid identity is handled by the authentication chain, preserving its 401 boundary.
        if (authentication != null && authentication.isAuthenticated() && authentication.getPrincipal() instanceof UUID userId
                && !access.access(userId, request.getHeader(ClientAccessService.DEVICE_HEADER)).allowed()) {
            errors.write(response, HttpStatus.FORBIDDEN, "SESSION_BINDING_REQUIRED",
                    "An active session binding is required for this device");
            return;
        }
        chain.doFilter(request, response);
    }
}
