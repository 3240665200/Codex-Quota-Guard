package com.codexquotaguard.backend.security;

import com.codexquotaguard.backend.system.SessionBindingRequiredException;
import com.codexquotaguard.backend.system.SystemSessionService;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Applies after the local bearer filter, including direct loopback callers. */
@Configuration(proxyBeanMethods = false)
public class ClientBindingGateConfiguration implements WebMvcConfigurer {
    private final SystemSessionService sessions;
    public ClientBindingGateConfiguration(SystemSessionService sessions) { this.sessions = sessions; }
    @Override public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new org.springframework.web.servlet.HandlerInterceptor() {
            @Override public boolean preHandle(jakarta.servlet.http.HttpServletRequest request,
                    jakarta.servlet.http.HttpServletResponse response, Object handler) {
                if (!(handler instanceof org.springframework.web.method.HandlerMethod) || bootstrapAllowed(request.getMethod(), request.getRequestURI().substring(request.getContextPath().length()))) return true;
                response.setHeader("Cache-Control", "no-store");
                if (!sessions.clientAccess().allowed()) throw new SessionBindingRequiredException();
                return true;
            }
        }).addPathPatterns("/api/v1/**");
    }

    static boolean bootstrapAllowed(String method, String path) {
        String uuid = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";
        if ("GET".equals(method)) {
            return java.util.Set.of("/api/v1/status", "/api/v1/session/current", "/api/v1/session/access",
                    "/api/v1/login-assistance", "/api/v1/session-bindings", "/api/v1/official-login-helper/jobs",
                    "/api/v1/official-login-helper/status").contains(path)
                    || path.matches("/api/v1/login-assistance/listeners/" + uuid)
                    || path.matches("/api/v1/session-bindings/attempts/" + uuid);
        }
        if ("DELETE".equals(method)) return path.matches("/api/v1/login-assistance/listeners/" + uuid);
        return "POST".equals(method) && (java.util.Set.of("/api/v1/session/login", "/api/v1/session/logout",
                "/api/v1/usage/pair", "/api/v1/system/shutdown", "/api/v1/login-assistance/listeners",
                "/api/v1/session-bindings/attempts", "/api/v1/official-login-helper/cancel").contains(path)
                || path.matches("/api/v1/session-bindings/attempts/" + uuid + "/(prepare|detect|confirm|cancel)")
                || path.matches("/api/v1/official-login-helper/jobs/" + uuid + "/start"));
    }
}
