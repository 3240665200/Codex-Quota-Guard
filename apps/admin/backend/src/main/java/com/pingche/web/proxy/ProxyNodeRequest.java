package com.pingche.web.proxy;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Pattern;

public record ProxyNodeRequest(@NotBlank @Size(max = 120) String label,
        @NotBlank @Pattern(regexp = "HTTP|HTTPS|SOCKS5") String protocol,
        @NotBlank @Size(max = 253) String host, @NotNull @Min(1) @Max(65535) Integer port,
        @Size(max = 255) String username, @Size(max = 4096) String password,
        @NotNull Boolean enabled, Long version) {
    @Override public String toString() { return "ProxyNodeRequest[redacted]"; }
}
