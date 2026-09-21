package com.pingche.web.config;

import com.pingche.web.common.SecurityErrorWriter;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.Set;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

public class AuthRateLimitFilter extends OncePerRequestFilter {

    private static final long WINDOW_MILLIS = Duration.ofMinutes(1).toMillis();
    private static final int MAX_ENTRIES = 10_000;
    private static final Set<String> LIMITED_PATHS = Set.of(
            "/api/v1/auth/register",
            "/api/v1/auth/login",
            "/api/v1/auth/refresh"
    );

    private final Cache<String, Window> windows = Caffeine.newBuilder()
            .maximumSize(MAX_ENTRIES)
            .expireAfterWrite(Duration.ofMinutes(1))
            .build();
    private final SecurityErrorWriter errorWriter;
    private final int maximumRequests;

    public AuthRateLimitFilter(SecurityErrorWriter errorWriter, int maximumRequests) {
        if (maximumRequests < 1) {
            throw new IllegalArgumentException("auth rate limit must be positive");
        }
        this.errorWriter = errorWriter;
        this.maximumRequests = maximumRequests;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !HttpMethod.POST.matches(request.getMethod())
                || !LIMITED_PATHS.contains(request.getRequestURI());
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        long now = System.currentTimeMillis();
        String key = request.getRemoteAddr() + "|" + request.getRequestURI();
        Window window = windows.asMap().compute(key, (ignored, current) -> {
            if (current == null || now - current.startedAt() >= WINDOW_MILLIS) {
                return new Window(now, 1);
            }
            return new Window(current.startedAt(), current.count() + 1);
        });

        if (window.count() > maximumRequests) {
            response.setHeader("Retry-After", "60");
            errorWriter.write(
                    response,
                    HttpStatus.TOO_MANY_REQUESTS,
                    "AUTH_RATE_LIMITED",
                    "Too many authentication attempts"
            );
            return;
        }
        filterChain.doFilter(request, response);
    }

    private record Window(long startedAt, int count) {
    }
}
