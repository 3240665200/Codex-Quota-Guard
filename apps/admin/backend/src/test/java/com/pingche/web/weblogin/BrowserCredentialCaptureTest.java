package com.pingche.web.weblogin;

import static org.assertj.core.api.Assertions.*;

import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class BrowserCredentialCaptureTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void onlyMatchingSuccessfulOfficialJsonAccountReadMakesCandidateReady() {
        BrowserCredentialCapture capture = new BrowserCredentialCapture(json);
        capture.accept(request("https://chatgpt.com/backend-api/accounts/sessions", "GET"));
        capture.accept(response(403, "application/json", "https://chatgpt.com/backend-api/accounts/sessions"));
        assertThat(capture.candidate()).isNull();
        capture.accept(request("https://chatgpt.com/backend-api/accounts/check/v4-2023-04-27", "GET"));
        capture.accept(response(200, "application/json", "https://chatgpt.com/backend-api/accounts/check/v4-2023-04-27"));
        assertThat(capture.candidate().authorization()).isEqualTo("Bearer synthetic-token");
        assertThat(capture.candidate().accountId()).isEqualTo("account-synthetic");
        assertThat(capture.candidate().toString()).doesNotContain("synthetic-token");
        capture.clear();
        assertThat(capture.candidate()).isNull();
    }

    @Test
    void rejectsOtherEndpointsRedirectsMimeTypesMethodsAndOversizedEvents() {
        for (String url : java.util.List.of("https://evil.test/backend-api/accounts/sessions", "http://chatgpt.com/backend-api/accounts/sessions",
                "https://chatgpt.com/backend-api/me", "https://chatgpt.com:444/backend-api/accounts/sessions")) {
            BrowserCredentialCapture capture = new BrowserCredentialCapture(json);
            capture.accept(request(url, "GET")); capture.accept(response(200, "application/json", url));
            assertThat(capture.candidate()).isNull();
        }
        BrowserCredentialCapture capture = new BrowserCredentialCapture(json);
        capture.accept(request("https://chatgpt.com/backend-api/accounts/sessions", "POST"));
        capture.accept(response(200, "application/json", "https://chatgpt.com/backend-api/accounts/sessions"));
        capture.accept("x".repeat(262145));
        assertThat(capture.candidate()).isNull();
    }

    @Test
    void onlySecureOfficialSessionCookiesAndNumericChunksAreCaptured() {
        var cookies = json.readTree("""
                {"cookies":[
                {"name":"__Secure-next-auth.session-token.0","value":"chunk0","domain":".chatgpt.com","secure":true},
                {"name":"__Secure-next-auth.session-token.1","value":"chunk1","domain":"chatgpt.com","secure":true},
                {"name":"cf_clearance","value":"discard","domain":".chatgpt.com","secure":true},
                {"name":"__Secure-next-auth.session-token.bad","value":"discard","domain":"chatgpt.com","secure":true},
                {"name":"__Secure-next-auth.session-token","value":"discard","domain":"evil.test","secure":true},
                {"name":"__Secure-next-auth.session-token.2","value":"discard","domain":"chatgpt.com","secure":false}]}
                """);
        assertThat(BrowserCredentialCapture.loginCookies(cookies)).isEqualTo("__Secure-next-auth.session-token.0=chunk0; __Secure-next-auth.session-token.1=chunk1");
    }

    private String request(String url, String method) {
        return json.writeValueAsString(Map.of("method", "Network.requestWillBeSent", "sessionId", "owned", "params",
                Map.of("requestId", "r1", "request", Map.of("url", url, "method", method, "headers",
                        Map.of("Authorization", "Bearer synthetic-token", "Chatgpt-Account-Id", "account-synthetic")))));
    }
    private String response(int status, String mime, String url) {
        return json.writeValueAsString(Map.of("method", "Network.responseReceived", "sessionId", "owned", "params",
                Map.of("requestId", "r1", "response", Map.of("status", status, "mimeType", mime, "url", url))));
    }
}
