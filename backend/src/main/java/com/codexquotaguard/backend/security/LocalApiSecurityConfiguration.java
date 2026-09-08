package com.codexquotaguard.backend.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class LocalApiSecurityConfiguration {

    @Bean
    ApiTokenVerifier apiTokenVerifier(@Value("${quota-guard.api-token:}") String token) {
        return new ApiTokenVerifier(token);
    }

    @Bean
    FilterRegistrationBean<ApiAuthenticationFilter> apiAuthenticationFilter(ApiTokenVerifier tokenVerifier) {
        var registration = new FilterRegistrationBean<>(new ApiAuthenticationFilter(tokenVerifier));
        registration.addUrlPatterns("/api/*");
        registration.setOrder(Integer.MIN_VALUE);
        return registration;
    }
}
