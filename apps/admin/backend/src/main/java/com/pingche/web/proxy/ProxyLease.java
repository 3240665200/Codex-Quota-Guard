package com.pingche.web.proxy;

import java.net.ProxySelector;

public interface ProxyLease extends AutoCloseable {
    ProxySelector proxySelector();
    default boolean isAlive() {
        try { return !proxySelector().select(java.net.URI.create("https://chatgpt.com")).isEmpty(); }
        catch (RuntimeException ignored) { return false; }
    }
    @Override void close();
}
