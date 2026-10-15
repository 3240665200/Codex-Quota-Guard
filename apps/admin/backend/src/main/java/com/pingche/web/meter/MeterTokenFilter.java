package com.pingche.web.meter;

import com.pingche.web.auth.DomainException;
import com.pingche.web.common.SecurityErrorWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/** Authenticates only the dedicated meter endpoints, never a general user API. */
public final class MeterTokenFilter extends OncePerRequestFilter {
    private final MeterDeviceService devices;
    private final SecurityErrorWriter errors;

    public MeterTokenFilter(MeterDeviceService devices, SecurityErrorWriter errors) {
        this.devices = devices;
        this.errors = errors;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/v1/meter/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws IOException, ServletException {
        if (request.getHeader("Authorization") != null) {
            unauthorized(response);
            return;
        }
        try {
            boolean renewal = "POST".equals(request.getMethod())
                    && "/api/v1/meter/credential/renew".equals(request.getRequestURI());
            String token = request.getHeader("X-Quota-Guard-Meter");
            MeterIdentity identity = renewal ? devices.authenticateForRenewal(token) : devices.authenticate(token);
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(identity, null,
                            List.of(new SimpleGrantedAuthority("ROLE_METER"))));
            chain.doFilter(request, response);
        } catch (DomainException exception) {
            unauthorized(response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private void unauthorized(HttpServletResponse response) throws IOException {
        errors.write(response, HttpStatus.UNAUTHORIZED, "METER_TOKEN_INVALID",
                "Meter authentication is required");
    }
}
