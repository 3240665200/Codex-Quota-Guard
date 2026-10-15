package com.pingche.web.proxy;

import java.net.ProxySelector;

public interface ProxyLease extends AutoCloseable {
    ProxySelector proxySelector();
    @Override void close();
}
