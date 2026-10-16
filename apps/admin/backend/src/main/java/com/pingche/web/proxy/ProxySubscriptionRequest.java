package com.pingche.web.proxy;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ProxySubscriptionRequest(@NotBlank @Size(max = 120) String label,
        @Size(max = 8192) String url, Long version, Boolean allowInsecureTrojan) {
    public ProxySubscriptionRequest(String label, String url, Long version) {
        this(label, url, version, null);
    }
    @Override public String toString() { return "ProxySubscriptionRequest[redacted]"; }
}
