package com.codexquotaguard.backend.officiallogin;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class BrowserCredentialCaptureTest {
    ObjectMapper mapper = new ObjectMapper();
    @Test void onlySuccessfulOfficialAccountReadsProduceCredentials() {
        var capture = new BrowserCredentialCapture(mapper);
        capture.accept(request("https://chatgpt.com/backend-api/accounts/check/v4-2023-04-27", "GET"));
        assertThat(capture.result().isDone()).isFalse();
        capture.accept(response(403));
        assertThat(capture.result().isDone()).isFalse();
        capture.accept(request("https://chatgpt.com/backend-api/accounts/check/v4-2023-04-27", "GET"));
        capture.accept(response(200));
        var value = capture.result().join();
        assertThat(value.authorization()).isEqualTo("Bearer synthetic-token");
        assertThat(value.requestedAccountId()).isEqualTo("synthetic-account");
        assertThat(value.toString()).doesNotContain("synthetic-token");
    }
    @Test void ignoresLookalikeOriginsOtherEndpointsAndNonGetRequests() {
        for (String url : new String[]{"https://chatgpt.com.evil.test/backend-api/accounts/sessions", "http://chatgpt.com/backend-api/accounts/sessions",
                "https://chatgpt.com/backend-api/conversation", "https://chatgpt.com@evil.test/backend-api/accounts/sessions"}) {
            var capture = new BrowserCredentialCapture(mapper);
            capture.accept(request(url,"GET"));capture.accept(response(200));
            assertThat(capture.result().isDone()).isFalse();
        }
        var capture = new BrowserCredentialCapture(mapper);
        capture.accept(request("https://chatgpt.com/backend-api/accounts/sessions","POST"));capture.accept(response(200));
        assertThat(capture.result().isDone()).isFalse();
    }
    @Test void exportsOnlySecureOfficialLoginCookiesNeverChallengeCookies() {
        String value = BrowserCredentialCapture.loginCookies(mapper.readTree("""
            {"cookies":[
              {"name":"__Secure-next-auth.session-token.0","value":"synthetic-part","domain":".chatgpt.com","secure":true},
              {"name":"cf_clearance","value":"challenge-secret","domain":".chatgpt.com","secure":true},
              {"name":"__Secure-next-auth.session-token.1","value":"foreign-secret","domain":"evil.test","secure":true},
              {"name":"__Secure-next-auth.session-token.2","value":"insecure-secret","domain":"chatgpt.com","secure":false}
            ]}
            """));
        assertThat(value).isEqualTo("__Secure-next-auth.session-token.0=synthetic-part");
    }
    @Test void neverReadsValuesOfChallengeCookies() {
        var challenge=org.mockito.Mockito.spy(mapper.readTree("{\"name\":\"cf_clearance\",\"domain\":\".chatgpt.com\",\"secure\":true}"));
        org.mockito.Mockito.doThrow(new AssertionError("Challenge cookie value must not be read")).when(challenge).path("value");
        var response=org.mockito.Mockito.mock(tools.jackson.databind.JsonNode.class);
        org.mockito.Mockito.when(response.path("cookies")).thenReturn(mapper.createArrayNode().add(challenge));
        assertThat(BrowserCredentialCapture.loginCookies(response)).isEmpty();
    }
    private String request(String url,String method) { return "{\"method\":\"Network.requestWillBeSent\",\"params\":{\"requestId\":\"r1\",\"request\":{\"url\":\""+url+"\",\"method\":\""+method+"\",\"headers\":{\"authorization\":\"Bearer synthetic-token\",\"ChatGPT-Account-ID\":\"synthetic-account\"}}}}"; }
    private String response(int status) { return "{\"method\":\"Network.responseReceived\",\"params\":{\"requestId\":\"r1\",\"response\":{\"url\":\"https://chatgpt.com/backend-api/accounts/check/v4-2023-04-27\",\"status\":"+status+",\"mimeType\":\"application/json\"}}}"; }
}
