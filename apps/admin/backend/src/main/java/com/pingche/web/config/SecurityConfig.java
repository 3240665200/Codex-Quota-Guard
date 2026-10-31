package com.pingche.web.config;

import com.pingche.web.auth.JwtService;
import com.pingche.web.common.SecurityErrorWriter;
import com.pingche.web.meter.MeterDeviceService;
import com.pingche.web.meter.MeterTokenFilter;
import com.pingche.web.sessionbinding.ClientAccessFilter;
import com.pingche.web.sessionbinding.ClientAccessService;
import com.pingche.web.user.UserRepository;
import jakarta.servlet.DispatcherType;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(
            @Value("${app.cors-allowed-origins}") String configuredOrigins
    ) {
        List<String> origins = Stream.concat(Arrays.stream(configuredOrigins.split(",")),
                        Stream.of("http://tauri.localhost", "https://tauri.localhost", "tauri://localhost"))
                .map(String::trim)
                .filter(origin -> !origin.isBlank() && !"*".equals(origin))
                .distinct()
                .toList();

        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(origins);
        configuration.setAllowedMethods(List.of(
                "GET",
                "POST",
                "PUT",
                "PATCH",
                "DELETE",
                "OPTIONS"
        ));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Chunk-SHA256",
                ClientAccessService.DEVICE_HEADER, "X-CQG-Queue-Version"));
        configuration.setAllowCredentials(false);
        configuration.setMaxAge(600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", configuration);
        return source;
    }

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            JwtService jwtService,
            UserRepository users,
            MeterDeviceService meterDevices,
            ClientAccessService clientAccess,
            SecurityErrorWriter errorWriter,
            @Value("${app.auth-rate-limit-per-minute:20}") int authRateLimit,
            @Value("${app.require-https:false}") boolean requireHttps
    ) throws Exception {
        JwtFilter jwtFilter = new JwtFilter(jwtService, users, errorWriter);
        MeterTokenFilter meterTokenFilter = new MeterTokenFilter(meterDevices, errorWriter);
        AuthRateLimitFilter rateLimitFilter =
                new AuthRateLimitFilter(errorWriter, authRateLimit);

        http.csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(
                        SessionCreationPolicy.STATELESS
                ))
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, exception) ->
                                errorWriter.write(
                                        response,
                                        org.springframework.http.HttpStatus.UNAUTHORIZED,
                                        "AUTH_REQUIRED",
                                        "Authentication is required"
                                ))
                        .accessDeniedHandler((request, response, exception) ->
                                errorWriter.write(
                                        response,
                                        org.springframework.http.HttpStatus.FORBIDDEN,
                                        "AUTH_FORBIDDEN",
                                        "Access is forbidden"
                                )))
                .authorizeHttpRequests(authorize -> authorize
                        // The initial REQUEST is authenticated. StreamingResponseBody
                        // resumes as an internal ASYNC dispatch without its JWT context.
                        .dispatcherTypeMatchers(DispatcherType.ASYNC).permitAll()
                        .requestMatchers(
                                HttpMethod.POST,
                                "/api/v1/auth/register",
                                "/api/v1/auth/login",
                                "/api/v1/auth/refresh"
                        ).permitAll()
                        .requestMatchers(HttpMethod.GET,"/api/v1/desktop-updates/**","/api/v1/meter-updates/**").permitAll()
                        .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                        .requestMatchers("/api/v1/meter/**").hasRole("METER")
                        .anyRequest().authenticated())
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(new ClientAccessFilter(clientAccess, errorWriter), JwtFilter.class)
                .addFilterBefore(meterTokenFilter, JwtFilter.class)
                .addFilterBefore(rateLimitFilter, JwtFilter.class);

        if (requireHttps) {
            http.redirectToHttps(Customizer.withDefaults());
        }
        return http.build();
    }
}
