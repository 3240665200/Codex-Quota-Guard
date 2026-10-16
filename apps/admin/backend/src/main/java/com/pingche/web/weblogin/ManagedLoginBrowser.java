package com.pingche.web.weblogin;

import java.util.UUID;

/** Server-only browser capability. Credentials must never be serialized into an HTTP response. */
public interface ManagedLoginBrowser {
    int WIDTH = 1024;
    int HEIGHT = 768;
    boolean available();
    Session open(UUID jobId, UUID proxyNodeId);
    default Session open(UUID jobId, UUID frontProxyNodeId, UUID proxyNodeId) {
        if (frontProxyNodeId != null) throw new IllegalArgumentException("Proxy chains are not supported by this browser");
        return open(jobId, proxyNodeId);
    }

    interface Session extends AutoCloseable {
        Frame frame();
        void input(Input input);
        Credentials capture();
        @Override void close();
    }

    record Frame(int width, int height, String jpegBase64, String pageOrigin, boolean readyToSave, String errorCode) {
        @Override public String toString() { return "ManagedBrowserFrame[image redacted]"; }
    }
    record Input(String type, Integer x, Integer y, Integer deltaY, String key, String text, boolean ctrl, boolean shift) {
        @Override public String toString() { return "ManagedBrowserInput[redacted]"; }
    }
    record Credentials(String cookie, String authorization, String accountId) {
        @Override public String toString() { return "ManagedBrowserCredentials[redacted]"; }
    }
}
