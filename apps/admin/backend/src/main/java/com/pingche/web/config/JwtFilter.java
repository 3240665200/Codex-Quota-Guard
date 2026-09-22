package com.pingche.web.config;

import com.pingche.web.auth.JwtService;
import com.pingche.web.common.SecurityErrorWriter;
import com.pingche.web.user.User;
import com.pingche.web.user.UserRepository;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

public class JwtFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final UserRepository users;
    private final SecurityErrorWriter errorWriter;

    public JwtFilter(
            JwtService jwtService,
            UserRepository users,
            SecurityErrorWriter errorWriter
    ) {
        this.jwtService = jwtService;
        this.users = users;
        this.errorWriter = errorWriter;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String authorization = request.getHeader("Authorization");
        if (authorization == null) {
            filterChain.doFilter(request, response);
            return;
        }
        if (!authorization.startsWith("Bearer ") || authorization.length() == 7) {
            unauthorized(response);
            return;
        }

        try {
            JwtService.JwtIdentity identity = jwtService.parseIdentity(
                    authorization.substring(7)
            );
            UUID userId = identity.userId();
            User user = users.findById(userId).orElse(null);
            if (user == null || user.getSessionVersion() != identity.sessionVersion()) {
                unauthorized(response);
                return;
            }
            if (user.getStatus() == User.Status.DISABLED) {
                SecurityContextHolder.clearContext();
                errorWriter.write(
                        response,
                        HttpStatus.FORBIDDEN,
                        "AUTH_ACCOUNT_DISABLED",
                        "Account is disabled"
                );
                return;
            }

            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(
                            userId,
                            null,
                            List.of(new SimpleGrantedAuthority(
                                    "ROLE_" + user.getRole().name()
                            ))
                    );
            SecurityContextHolder.getContext().setAuthentication(authentication);
            filterChain.doFilter(request, response);
        } catch (JwtException | IllegalArgumentException exception) {
            unauthorized(response);
        }
    }

    private void unauthorized(HttpServletResponse response) throws IOException {
        SecurityContextHolder.clearContext();
        errorWriter.write(
                response,
                HttpStatus.UNAUTHORIZED,
                "AUTH_TOKEN_INVALID",
                "Access token is invalid or expired"
        );
    }
}
