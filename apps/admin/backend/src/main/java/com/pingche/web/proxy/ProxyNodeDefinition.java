package com.pingche.web.proxy;

record ProxyNodeDefinition(String label, String protocol, String host, int port, String outboundJson,
        boolean hasCredentials) {
    @Override public String toString() { return "ProxyNodeDefinition[redacted]"; }
}
