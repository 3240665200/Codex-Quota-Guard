package com.pingche.web.proxy;

import jakarta.validation.constraints.Size;

public record ProxyImportRequest(@Size(max = 1_048_576) String content, @Size(max = 8192) String subscriptionUrl) {
    @Override public String toString() { return "ProxyImportRequest[redacted]"; }
}
