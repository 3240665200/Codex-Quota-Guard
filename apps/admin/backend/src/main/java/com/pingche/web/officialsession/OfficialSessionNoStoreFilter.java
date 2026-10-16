package com.pingche.web.officialsession;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class OfficialSessionNoStoreFilter extends OncePerRequestFilter {
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getRequestURI().matches("/api/v1/admin/official-accounts/[^/]+/(connection(?:/(?:refresh|preset|proxy))?|sessions|browser-login|web-login|session-logout(?:/.*)?)")
                || request.getRequestURI().equals("/api/v1/admin/official-authorization-preset")
                || request.getRequestURI().startsWith("/api/v1/admin/browser-logins")
                || request.getRequestURI().startsWith("/api/v1/admin/web-logins")) {
            response.setHeader("Cache-Control", "no-store");
            response.setHeader("Pragma", "no-cache");
        }
        chain.doFilter(request, response);
    }
}
