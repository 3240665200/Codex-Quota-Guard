package com.codexquotaguard.backend.officiallogin;

import java.util.concurrent.CompletableFuture;

interface OfficialLoginBrowser {
    enum Browser { EDGE, CHROME }
    Session open(Browser browser);
    interface Session extends AutoCloseable {
        CompletableFuture<Authorization> authorization();
        @Override void close();
    }
    record Authorization(String authorization, String cookie, String requestedAccountId) {
        @Override public String toString() { return "OfficialBrowserAuthorization[redacted]"; }
    }
}
