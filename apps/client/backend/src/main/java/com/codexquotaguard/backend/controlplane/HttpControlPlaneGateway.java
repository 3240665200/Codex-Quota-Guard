package com.codexquotaguard.backend.controlplane;

import com.codexquotaguard.backend.usage.CodexUsageEvent;
import java.io.IOException;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;

/** HTTP implementation. Mailbox credentials and official session tokens never reach the renderer. */
final class HttpControlPlaneGateway implements ControlPlaneGateway {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);
    private static final int MAX_USAGE_EVENTS_PER_REQUEST = 100;
    private static final int MAX_MAIL_RESPONSE_BYTES = 8192;
    private static final ProxySelector DIRECT_PROXY_SELECTOR = new ProxySelector() {
        @Override
        public List<Proxy> select(URI uri) {
            return List.of(Proxy.NO_PROXY);
        }

        @Override
        public void connectFailed(URI uri, SocketAddress address, IOException failure) {
            // The caller receives a fixed unavailable error. Do not retain an
            // upstream URI, response, or credential-bearing request detail.
        }
    };

    private final URI baseUri;
    private final HttpClient client;
    private final ObjectMapper mapper;
    private final Duration requestTimeout;

    HttpControlPlaneGateway(String baseUrl, ObjectMapper mapper) {
        this(baseUrl, mapper, HttpClient.newBuilder()
                .connectTimeout(DEFAULT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER)
                .proxy(DIRECT_PROXY_SELECTOR)
                .build(), DEFAULT_TIMEOUT);
    }

    HttpControlPlaneGateway(String baseUrl, ObjectMapper mapper, HttpClient client, Duration requestTimeout) {
        if (mapper == null || client == null || requestTimeout == null || requestTimeout.isNegative()
                || requestTimeout.isZero()) {
            throw new IllegalArgumentException("A complete control-plane HTTP configuration is required.");
        }
        this.baseUri = normalizeBaseUri(baseUrl);
        this.client = client;
        this.mapper = mapper.rebuild().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
        this.requestTimeout = requestTimeout;
    }

    @Override
    public ControlPlaneAuthSession login(String email, String password) {
        AuthResponse response = sendJson(
                postJson("/api/v1/auth/login", new LoginBody(email, password), null),
                AuthResponse.class
        );
        return toSession(response);
    }

    @Override
    public ControlPlaneAuthSession refresh(String refreshToken) {
        AuthResponse response = sendJson(
                postJson("/api/v1/auth/refresh", new RefreshBody(refreshToken), null),
                AuthResponse.class
        );
        return toSession(response);
    }

    @Override
    public SystemUser current(String accessToken) {
        return sendJson(get("/api/v1/users/me", accessToken), SystemUser.class);
    }

    @Override
    public PersonalQuotaOverview quotaOverview(String accessToken) {
        return sendJson(get("/api/v1/client/quota/overview", accessToken), PersonalQuotaOverview.class);
    }

    @Override
    public LoginAssistanceInfo loginAssistanceInfo(String accessToken) {
        return sendMailJson(get("/api/v1/client/login-assistance", accessToken), LoginAssistanceInfo.class);
    }

    @Override
    public LoginAssistanceListener startLoginAssistance(String accessToken) {
        HttpRequest request = HttpRequest.newBuilder(endpoint("/api/v1/client/login-assistance/listeners"))
                .timeout(requestTimeout).header("Accept", "application/json")
                .header("Authorization", "Bearer " + accessToken)
                .POST(HttpRequest.BodyPublishers.noBody()).build();
        return sendMailJson(request, LoginAssistanceListener.class);
    }

    @Override
    public LoginAssistanceListener loginAssistanceStatus(String accessToken, UUID listenerId) {
        return sendMailJson(get(mailListenerPath(listenerId), accessToken), LoginAssistanceListener.class);
    }

    @Override
    public LoginAssistanceListener cancelLoginAssistance(String accessToken, UUID listenerId) {
        HttpRequest request = HttpRequest.newBuilder(endpoint(mailListenerPath(listenerId)))
                .timeout(requestTimeout).header("Accept", "application/json")
                .header("Authorization", "Bearer " + accessToken).DELETE().build();
        return sendMailJson(request, LoginAssistanceListener.class);
    }

    private static String mailListenerPath(UUID listenerId) {
        if (listenerId == null) throw ControlPlaneException.invalidRequest();
        return "/api/v1/client/login-assistance/listeners/" + listenerId;
    }

    private <T> T sendMailJson(HttpRequest request, Class<T> type) {
        HttpResponse<byte[]> response = sendMail(request);
        switch (response.statusCode()) {
            case 400, 403, 404 -> throw ControlPlaneException.mailRejected();
            case 409 -> throw ControlPlaneException.mailBusy();
            case 429 -> throw ControlPlaneException.mailRateLimited();
            default -> ensureSuccess(response.statusCode());
        }
        try {
            return mapper.readValue(response.body(), type);
        } catch (RuntimeException exception) {
            // Never retain a response body or Jackson exception containing an OTP.
            throw ControlPlaneException.unavailable();
        }
    }

    private HttpResponse<byte[]> sendMail(HttpRequest request) {
        CompletableFuture<HttpResponse<byte[]>> exchange = null;
        try {
            // The stage completes only after the bounded subscriber consumes the
            // full body; this deadline therefore also covers slow/chunked bodies.
            exchange = client.sendAsync(request,
                    ignored -> new BoundedMailBodySubscriber(MAX_MAIL_RESPONSE_BYTES));
            return exchange.get(requestTimeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (InterruptedException interrupted) {
            if (exchange != null) exchange.cancel(true);
            Thread.currentThread().interrupt();
            throw ControlPlaneException.unavailable();
        } catch (ExecutionException | TimeoutException | RuntimeException failure) {
            if (exchange != null) exchange.cancel(true);
            throw ControlPlaneException.unavailable();
        }
    }

    @Override
    public UserUsageEventPage usageEvents(String accessToken, int page, int size) {
        if (page < 0 || page > 100000 || size < 1 || size > 100) {
            throw ControlPlaneException.invalidRequest();
        }
        return sendJson(get("/api/v1/client/usage/events?page=" + page + "&size=" + size
                + "&includeMetadata=true&includeTokenBreakdown=true", accessToken),
                UserUsageEventPage.class);
    }

    @Override
    public PersonalUsageAnalytics usageAnalytics(String accessToken, int days) {
        if (days != 1 && days != 7 && days != 30) {
            throw ControlPlaneException.invalidRequest();
        }
        return sendJson(get("/api/v1/client/usage/analytics?days=" + days, accessToken),
                PersonalUsageAnalytics.class);
    }

    @Override
    public MeterEnrollment enrollMeter(String accessToken, UUID deviceId) {
        if (deviceId == null) throw ControlPlaneException.invalidRequest();
        return sendJson(postJson("/api/v1/client/meter/enroll", new MeterDeviceBody(deviceId), accessToken),
                MeterEnrollment.class);
    }

    @Override
    public PersonalQuotaOverview reportUsage(String accessToken, List<CodexUsageEvent> events) {
        if (events == null || events.isEmpty() || events.size() > MAX_USAGE_EVENTS_PER_REQUEST) {
            throw ControlPlaneException.invalidRequest();
        }
        List<UsageEventBody> eventBodies = events.stream()
                .map(event -> new UsageEventBody(event.eventId(), event.occurredAt().toString(),
                        event.totalTokens(), event.source().name(), event.model(), event.reasoningEffort(),
                        event.inputTokens(), event.cachedInputTokens(), event.cacheWriteInputTokens(),
                        event.outputTokens(), event.reasoningOutputTokens()))
                .toList();
        HttpResponse<String> response = send(postJson("/api/v1/client/usage/events",
                new LocalUsageEventsBody(eventBodies), accessToken));
        if (response.statusCode() == 409) {
            throw ControlPlaneException.accessDenied();
        }
        ensureSuccess(response.statusCode());
        try {
            return mapper.readValue(response.body(), PersonalQuotaOverview.class);
        } catch (RuntimeException exception) {
            throw ControlPlaneException.unavailable();
        }
    }

    @Override
    public void reportOfficialQuota(String accessToken, OfficialCodexRateLimits snapshot) {
        HttpRequest request = HttpRequest.newBuilder(endpoint("/api/v1/client/official-quota"))
                .timeout(requestTimeout)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + accessToken)
                .PUT(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(snapshot)))
                .build();
        sendEmpty(request);
    }

    @Override
    public void logout(String accessToken, String refreshToken) {
        sendEmpty(postJson("/api/v1/auth/logout", new RefreshBody(refreshToken), accessToken));
    }

    private HttpRequest get(String path, String accessToken) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint(path))
                .timeout(requestTimeout)
                .header("Accept", "application/json")
                .GET();
        if (accessToken != null) {
            builder.header("Authorization", "Bearer " + accessToken);
        }
        return builder.build();
    }

    private static ControlPlaneAuthSession toSession(AuthResponse response) {
        try {
            return response.toSession();
        } catch (RuntimeException exception) {
            throw ControlPlaneException.unavailable();
        }
    }

    private HttpRequest postJson(String path, Object body, String accessToken) {
        final String json;
        try {
            json = mapper.writeValueAsString(body);
        } catch (RuntimeException exception) {
            throw ControlPlaneException.invalidRequest();
        }

        HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint(path))
                .timeout(requestTimeout)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json));
        if (accessToken != null) {
            builder.header("Authorization", "Bearer " + accessToken);
        }
        return builder.build();
    }

    private <T> T sendJson(HttpRequest request, Class<T> responseType) {
        HttpResponse<String> response = send(request);
        ensureSuccess(response.statusCode());
        try {
            return mapper.readValue(response.body(), responseType);
        } catch (RuntimeException exception) {
            throw ControlPlaneException.unavailable();
        }
    }

    private void sendEmpty(HttpRequest request) {
        HttpResponse<String> response = send(request);
        ensureSuccess(response.statusCode());
    }

    private HttpResponse<String> send(HttpRequest request) {
        try {
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException exception) {
            throw ControlPlaneException.unavailable();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw ControlPlaneException.unavailable();
        }
    }

    private static void ensureSuccess(int statusCode) {
        if (statusCode >= 200 && statusCode < 300) {
            return;
        }
        if (statusCode == 401 || statusCode == 403) {
            throw ControlPlaneException.unauthorized();
        }
        if (statusCode >= 400 && statusCode < 500) {
            throw ControlPlaneException.invalidRequest();
        }
        throw ControlPlaneException.unavailable();
    }

    private URI endpoint(String path) {
        return baseUri.resolve(path.substring(1));
    }

    static URI normalizeBaseUri(String baseUrl) {
        return ControlPlaneOrigin.normalize(baseUrl);
    }

    private record LoginBody(String email, String password) {
    }

    private record RefreshBody(String refreshToken) {
    }

    private record LocalUsageEventsBody(List<UsageEventBody> events) {
    }

    private record MeterDeviceBody(UUID deviceId) {
    }

    private record UsageEventBody(String eventId, String occurredAt, long totalTokens, String source,
            String model, String reasoningEffort, Long inputTokens, Long cachedInputTokens,
            Long cacheWriteInputTokens, Long outputTokens, Long reasoningOutputTokens) {
    }

    private record AuthResponse(
            String accessToken,
            String tokenType,
            long expiresIn,
            String refreshToken,
            long refreshExpiresIn,
            SystemUser user
    ) {
        ControlPlaneAuthSession toSession() {
            return new ControlPlaneAuthSession(accessToken, refreshToken, user, refreshExpiresIn);
        }
    }
}
