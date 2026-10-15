package com.pingche.web.proxy;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class ProxyNoStoreFilter extends OncePerRequestFilter {
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getRequestURI().startsWith("/api/v1/admin/proxy-nodes")) {
            response.setHeader("Cache-Control", "no-store"); response.setHeader("Pragma", "no-cache");
        }
        chain.doFilter(request, response);
    }
}
