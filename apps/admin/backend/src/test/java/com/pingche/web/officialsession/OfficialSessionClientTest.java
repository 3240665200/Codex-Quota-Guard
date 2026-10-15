package com.pingche.web.officialsession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.pingche.web.auth.DomainException;
import com.pingche.web.proxy.ManagedProxyGateway;
import com.pingche.web.proxy.ProxyLease;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class OfficialSessionClientTest {
    static final String ME = """
            {"id":"official-user-one","email":"owner@example.com","name":"Owner","unwanted_secret":"private-profile"}
            """;
    static final String ACCOUNTS = """
            {"account_ordering":["not-the-id"],"accounts":{"not-the-id":{
              "account":{"account_id":"official-account-one","name":"Personal","plan_type":"plus",
                "account_user_role":"owner","is_deactivated":false},
              "entitlement":{"has_active_subscription":true,"renews_at":{"unknown":"unverified-type"}},
              "can_access_with_session":true}}}
            """;
    static final String SESSIONS = """
            {"show_session_manager":true,"devices":[{
              "render_id":"row-one","session_id":"session-one","platform":"windows","display_name":"Codex desktop",
              "device_model":"PC","human_readable_description":"Windows PC","os_version":"11",
              "is_current_device":true,"is_trusted_device":false,"can_untrust":false,"hashed_device_id":"private-hash",
              "last_signed_in_timestamp_second":1791964800,"session_created_at_seconds":1791878400,
              "last_signed_in_city":"Synthetic City","last_signed_in_region_code":"SC","last_signed_in_country":"ZZ",
              "app_sessions":[{"client_name":"Codex"},{"client_name":"ChatGPT"}]},
              {"render_id":"row-two","session_id":null,"platform":"linux","is_current_device":false,
               "is_trusted_device":true,"app_sessions":[]} ]}
            """;

    @Test
    void fetchesOnlyThreeFixedReadEndpointsAndMatchesAccountFieldInsteadOfMapKey() {
        HttpClient http = mock(HttpClient.class);
        ManagedProxyGateway proxy = mock(ManagedProxyGateway.class);
        List<HttpRequest> requests = success(http);
        AtomicReference<ProxySelector> selected = new AtomicReference<>();
        OfficialSessionClient client = new OfficialSessionClient(new ObjectMapper(), proxy,
                selector -> { selected.set(selector); return http; });
        OfficialSessionSnapshot result = client.fetch(credentials(), null);

        assertThat(requests).extracting(request -> request.uri().toString()).containsExactly(
                "https://chatgpt.com/backend-api/me",
                "https://chatgpt.com/backend-api/accounts/check/v4-2023-04-27",
                "https://chatgpt.com/backend-api/accounts/sessions");
        for (HttpRequest request : requests) {
            assertThat(request.method()).isEqualTo("GET");
            assertThat(request.headers().firstValue("Authorization")).contains("Bearer synthetic-access");
            assertThat(request.headers().firstValue("Cookie")).contains("__Secure-next-auth.session-token.0=private-cookie");
            assertThat(request.headers().firstValue("ChatGPT-Account-ID")).contains("official-account-one");
        }
        assertThat(selected.get().select(URI.create("https://chatgpt.com"))).containsExactly(Proxy.NO_PROXY);
        verifyNoInteractions(proxy);
        assertThat(result.account().accountId()).isEqualTo("official-account-one");
        assertThat(result.account().userId()).isEqualTo("official-user-one");
        assertThat(result.account().activeSubscription()).isTrue();
        assertThat(result.sessions().showSessionManager()).isTrue();
        assertThat(result.sessions().devices()).hasSize(2);
        assertThat(result.sessions().devices().get(0).applications()).containsExactly("Codex", "ChatGPT");
        assertThat(result.sessions().devices().get(0).lastSignedInAt()).isEqualTo(Instant.ofEpochSecond(1791964800));
        assertThat(result.sessions().devices().get(1).sessionId()).isNull();
        String safe = new ObjectMapper().writeValueAsString(result);
        assertThat(safe).doesNotContain("private-profile", "private-hash", "unwanted_secret", "renews_at", "authorization");
        assertThat(credentials().toString()).doesNotContain("synthetic-access", "private-cookie");
    }

    @Test
    void acceptsIdenticalDefaultAccountAliasWithoutTreatingItAsConflictingIdentity() {
        ObjectMapper mapper = new ObjectMapper();
        String item = mapper.readTree(ACCOUNTS).get("accounts").get("not-the-id").toString();
        for (String entries : List.of("\"default\":" + item + ",\"official-account-one\":" + item,
                "\"official-account-one\":" + item + ",\"default\":" + item)) {
            HttpClient http = mock(HttpClient.class);
            List<HttpRequest> requests = success(http, "{\"accounts\":{" + entries + "}}", SESSIONS);

            OfficialSessionSnapshot result = client(http).fetch(credentials(), null);

            assertThat(result.account().accountId()).isEqualTo("official-account-one");
            assertThat(result.sessions().devices()).hasSize(2);
            assertThat(requests).hasSize(3);
        }
    }

    @Test
    void stillRejectsConflictingDataForTheSameAccountId() {
        String item = new ObjectMapper().readTree(ACCOUNTS).get("accounts").get("not-the-id").toString();
        String conflicting = item.replace("\"plus\"", "\"pro\"");
        HttpClient http = mock(HttpClient.class);
        List<HttpRequest> requests = success(http,
                "{\"accounts\":{\"default\":" + item + ",\"official-account-one\":" + conflicting + "}}", SESSIONS);

        assertFailure(http, "OFFICIAL_RESPONSE_INVALID");
        assertThat(requests).hasSize(2);
    }

    @Test
    void acceptsFractionalOfficialSessionSecondsWithoutLosingMicroseconds() {
        HttpClient http = mock(HttpClient.class);
        success(http, ACCOUNTS, SESSIONS
                .replace("\"last_signed_in_timestamp_second\":1791964800", "\"last_signed_in_timestamp_second\":1791964800.0")
                .replace("\"session_created_at_seconds\":1791878400", "\"session_created_at_seconds\":1791878400.123456"));

        OfficialSessionInfo device = client(http).fetch(credentials(), null).sessions().devices().get(0);

        assertThat(device.lastSignedInAt()).isEqualTo(Instant.ofEpochSecond(1791964800));
        assertThat(device.sessionCreatedAt()).isEqualTo(Instant.ofEpochSecond(1791878400, 123456000));
    }

    @Test
    void rejectsInvalidTimestampTypesAndValues() {
        for (String invalid : List.of("\"1791878400.1\"", "-0.1", "253402300800.0", "1e999", "true", "[]")) {
            HttpClient http = mock(HttpClient.class);
            success(http, ACCOUNTS, SESSIONS.replace("\"session_created_at_seconds\":1791878400",
                    "\"session_created_at_seconds\":" + invalid));
            assertFailure(http, "OFFICIAL_RESPONSE_INVALID");
        }
    }

    @Test
    void chosenProxyLeaseIsUsedAndAlwaysClosedWithoutFallingBackToDirect() {
        HttpClient http = mock(HttpClient.class);
        success(http);
        ManagedProxyGateway gateway = mock(ManagedProxyGateway.class);
        ProxyLease lease = mock(ProxyLease.class);
        ProxySelector selector = mock(ProxySelector.class);
        UUID nodeId = UUID.randomUUID();
        when(gateway.open(nodeId)).thenReturn(lease);
        when(lease.proxySelector()).thenReturn(selector);
        OfficialSessionClient client = new OfficialSessionClient(new ObjectMapper(), gateway,
                actual -> { assertThat(actual).isSameAs(selector); return http; });
        client.fetch(credentials(), nodeId);
        verify(lease).close();
        when(gateway.open(nodeId)).thenThrow(new DomainException("PROXY_UNAVAILABLE", "Proxy is unavailable", org.springframework.http.HttpStatus.BAD_GATEWAY));
        assertThatThrownBy(() -> client.fetch(credentials(), nodeId)).isInstanceOf(DomainException.class);
    }

    @Test
    void recognizesChallengeResponseWithoutRetryingOrLeakingHtml() {
        for (int status : new int[] {200, 403}) {
            HttpClient http = mock(HttpClient.class);
            HttpResponse<byte[]> upstream = response(status, "<html>private-token challenge</html>");
            when(upstream.headers()).thenReturn(HttpHeaders.of(Map.of(
                    "Content-Type", List.of("text/html"), "cf-mitigated", List.of("challenge")), (key, value) -> true));
            when(http.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                    .thenReturn(CompletableFuture.completedFuture(upstream));
            assertFailure(http, "OFFICIAL_CHALLENGE_REQUIRED");
            verify(http).sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
        }
    }

    @Test
    void upstreamFailuresAreFixedAndNeverExposeHeadersBodyOrChallengeHtml() {
        for (int status : new int[] {301, 401, 403, 429, 500}) {
            String expected = switch (status) {
                case 401 -> "OFFICIAL_AUTH_REQUIRED";
                case 403 -> "OFFICIAL_ACCESS_BLOCKED";
                case 429 -> "OFFICIAL_RATE_LIMITED";
                default -> "OFFICIAL_UNAVAILABLE";
            };
            HttpClient http = mock(HttpClient.class);
            HttpResponse<byte[]> upstream = response(status, "private-token <html>challenge</html>");
            when(http.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                    .thenReturn(CompletableFuture.completedFuture(upstream));
            assertFailure(http, expected);
        }
        for (String malformed : new String[] {"<html>private-token challenge</html>", "{}", "{\"id\":123}", "x".repeat(1_048_577)}) {
            HttpClient http = mock(HttpClient.class);
            HttpResponse<byte[]> upstream = response(200, malformed);
            when(http.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                    .thenReturn(CompletableFuture.completedFuture(upstream));
            assertFailure(http, "OFFICIAL_RESPONSE_INVALID");
        }
    }

    @Test
    void accountMismatchMalformedSessionListAndHeaderInjectionFailClosed() {
        HttpClient mismatch = mock(HttpClient.class);
        success(mismatch, ACCOUNTS.replace("official-account-one", "other-account"), SESSIONS);
        assertFailure(mismatch, "OFFICIAL_ACCOUNT_MISMATCH");
        for (String malformed : new String[] {"{}", "{\"show_session_manager\":true,\"devices\":{}}",
                "{\"show_session_manager\":true,\"devices\":[{\"render_id\":\"x\",\"app_sessions\":\"wrong\"}]}"}) {
            HttpClient http = mock(HttpClient.class);
            success(http, ACCOUNTS, malformed);
            assertFailure(http, "OFFICIAL_RESPONSE_INVALID");
        }
        HttpClient http = mock(HttpClient.class);
        OfficialSessionClient client = client(http);
        assertThatThrownBy(() -> client.fetch(new OfficialSessionCredentials("Bearer x\r\nHost: evil", null, "account"), null))
                .isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> client.fetch(new OfficialSessionCredentials("Bearer synthetic-access", "cf_clearance=private-cookie", "account"), null))
                .isInstanceOf(DomainException.class);
        verifyNoInteractions(http);
    }

    @Test
    void officialLogoutUsesFixedPostEndpointsAndNeverRetries() throws Exception {
        HttpClient http = mock(HttpClient.class);
        List<HttpRequest> requests = new ArrayList<>();
        when(http.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenAnswer(call -> {
            requests.add(call.getArgument(0));
            return CompletableFuture.completedFuture(response(200, "{\"success\":true}"));
        });
        OfficialSessionClient client = client(http);
        assertThat(client.logoutAll(credentials(), null).status()).isEqualTo("ACCEPTED");
        assertThat(client.revokeSession(credentials(), null, "synthetic-session").status()).isEqualTo("ACCEPTED");
        assertThat(requests).extracting(request -> request.uri().toString()).containsExactly(
                "https://chatgpt.com/backend-api/accounts/logout_all",
                "https://chatgpt.com/backend-api/accounts/sessions/revoke");
        assertThat(requests).allSatisfy(request -> {
            assertThat(request.method()).isEqualTo("POST");
            assertThat(request.headers().firstValue("Authorization")).contains("Bearer synthetic-access");
            assertThat(request.headers().firstValue("ChatGPT-Account-ID")).contains("official-account-one");
        });
        assertThat(requests.get(0).bodyPublisher().orElseThrow().contentLength()).isZero();
        var bytes = new java.io.ByteArrayOutputStream();
        var done = new CompletableFuture<Void>();
        requests.get(1).bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<ByteBuffer>() {
            public void onSubscribe(Flow.Subscription subscription) { subscription.request(Long.MAX_VALUE); }
            public void onNext(ByteBuffer buffer) { byte[] chunk = new byte[buffer.remaining()];buffer.get(chunk);bytes.writeBytes(chunk); }
            public void onError(Throwable error) { done.completeExceptionally(error); }
            public void onComplete() { done.complete(null); }
        });
        done.get(1,TimeUnit.SECONDS);
        assertThat(new ObjectMapper().readTree(bytes.toByteArray()).get("session_id").asString()).isEqualTo("synthetic-session");
    }

    @Test
    void logoutDistinguishesRejectionFromUnknownEffectsAndDoesNotExposeBodies() {
        for (int status : new int[]{200,204,401,403,429,500,302}) {
            HttpClient http=mock(HttpClient.class);
            HttpResponse<byte[]> upstream=response(status,status==204?"":"{\"success\":true,\"private\":\"secret-marker\"}");
            when(http.sendAsync(any(HttpRequest.class),any(HttpResponse.BodyHandler.class)))
                    .thenReturn(CompletableFuture.completedFuture(upstream));
            OfficialSessionWriteResult result=client(http).logoutAll(credentials(),null);
            assertThat(result.status()).isEqualTo(status==200||status==204?"ACCEPTED":status==401||status==403||status==429?"REJECTED":"UNKNOWN");
            assertThat(result.toString()).doesNotContain("secret-marker","synthetic-access","private-cookie");
            verify(http).sendAsync(any(HttpRequest.class),any(HttpResponse.BodyHandler.class));
        }
        for(String body : List.of("<html>private-token</html>","{\"success\":false}","{\"error\":\"private-token\"}")) {
            HttpClient http=mock(HttpClient.class);
            HttpResponse<byte[]> upstream=response(200,body);
            when(http.sendAsync(any(HttpRequest.class),any(HttpResponse.BodyHandler.class))).thenReturn(CompletableFuture.completedFuture(upstream));
            assertThat(client(http).logoutAll(credentials(),null).status()).isEqualTo("UNKNOWN");
            verify(http).sendAsync(any(HttpRequest.class),any(HttpResponse.BodyHandler.class));
        }
    }

    @Test
    void ambiguousLogoutTimeoutIsNotRetriedAndInvalidSessionNeverSends() throws Exception {
        HttpClient http=mock(HttpClient.class);
        CompletableFuture<HttpResponse<byte[]>> pending=mock(CompletableFuture.class);
        when(pending.get(10,TimeUnit.SECONDS)).thenThrow(new TimeoutException());
        when(http.sendAsync(any(HttpRequest.class),any(HttpResponse.BodyHandler.class))).thenReturn(pending);
        assertThat(client(http).logoutAll(credentials(),null).status()).isEqualTo("UNKNOWN");
        verify(pending).cancel(true);
        verify(http).sendAsync(any(HttpRequest.class),any(HttpResponse.BodyHandler.class));
        HttpClient noRequest=mock(HttpClient.class);
        for(String invalid : new String[]{null,"", " ","x\r\ninvalid","x".repeat(513)})
            assertThat(client(noRequest).revokeSession(credentials(),null,invalid).status()).isEqualTo("REJECTED");
        verifyNoInteractions(noRequest);
    }

    @Test
    void deadlineCancelsStalledBodyAndSubscriberCancelsOversizedBody() throws Exception {
        HttpClient http = mock(HttpClient.class);
        CompletableFuture<HttpResponse<byte[]>> pending = mock(CompletableFuture.class);
        when(pending.get(10, TimeUnit.SECONDS)).thenThrow(new TimeoutException());
        when(http.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(pending);
        assertFailure(http, "OFFICIAL_UNAVAILABLE");
        verify(pending).cancel(true);
        OfficialSessionClient.LimitedBodySubscriber subscriber = new OfficialSessionClient.LimitedBodySubscriber();
        Flow.Subscription subscription = mock(Flow.Subscription.class);
        subscriber.onSubscribe(subscription);
        subscriber.onNext(List.of(ByteBuffer.wrap(new byte[1_048_577])));
        assertThat(subscriber.getBody().toCompletableFuture()).isCompletedExceptionally();
        verify(subscription).cancel();
    }

    private OfficialSessionClient client(HttpClient http) {
        return new OfficialSessionClient(new ObjectMapper(), mock(ManagedProxyGateway.class), ignored -> http);
    }

    private void assertFailure(HttpClient http, String code) {
        assertThatThrownBy(() -> client(http).fetch(credentials(), null)).isInstanceOfSatisfying(DomainException.class, error -> {
            assertThat(error.getCode()).isEqualTo(code);
            assertThat(error.getMessage()).doesNotContain("private-token", "synthetic-access", "private-cookie");
            assertThat(error.getCause()).isNull();
        });
    }

    private OfficialSessionCredentials credentials() {
        return new OfficialSessionCredentials("Bearer synthetic-access", "__Secure-next-auth.session-token.0=private-cookie", "official-account-one");
    }

    private List<HttpRequest> success(HttpClient http) { return success(http, ACCOUNTS, SESSIONS); }
    private List<HttpRequest> success(HttpClient http, String accounts, String sessions) {
        List<HttpRequest> requests = new ArrayList<>();
        when(http.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenAnswer(call -> {
            HttpRequest request = call.getArgument(0);
            requests.add(request);
            String body = switch (request.uri().getPath()) {
                case "/backend-api/me" -> ME;
                case "/backend-api/accounts/check/v4-2023-04-27" -> accounts;
                case "/backend-api/accounts/sessions" -> sessions;
                default -> throw new AssertionError("Unexpected outbound endpoint");
            };
            return CompletableFuture.completedFuture(response(200, body));
        });
        return requests;
    }

    @SuppressWarnings("unchecked")
    private HttpResponse<byte[]> response(int status, String body) {
        HttpResponse<byte[]> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body.getBytes(StandardCharsets.UTF_8));
        when(response.headers()).thenReturn(HttpHeaders.of(Map.of("Content-Type", List.of("application/json")), (key, value) -> true));
        return response;
    }
}
