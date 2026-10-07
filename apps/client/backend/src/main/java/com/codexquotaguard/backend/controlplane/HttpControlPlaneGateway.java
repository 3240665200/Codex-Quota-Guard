package com.codexquotaguard.backend.controlplane;

import com.codexquotaguard.backend.usage.CodexUsageEvent;
import java.io.IOException;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;

/** HTTP implementation that has no credential-returning path to the renderer. */
final class HttpControlPlaneGateway implements ControlPlaneGateway {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);
    private static final int MAX_USAGE_EVENTS_PER_REQUEST = 100;
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
        this(baseUrl, mapper, false);
    }

    HttpControlPlaneGateway(String baseUrl, ObjectMapper mapper, boolean allowInsecureLanHttp) {
        this(baseUrl, mapper, HttpClient.newBuilder()
                .connectTimeout(DEFAULT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER)
                .proxy(DIRECT_PROXY_SELECTOR)
                .build(), DEFAULT_TIMEOUT, allowInsecureLanHttp);
    }

    HttpControlPlaneGateway(String baseUrl, ObjectMapper mapper, HttpClient client, Duration requestTimeout) {
        this(baseUrl, mapper, client, requestTimeout, false);
    }

    HttpControlPlaneGateway(String baseUrl, ObjectMapper mapper, HttpClient client, Duration requestTimeout,
            boolean allowInsecureLanHttp) {
        if (mapper == null || client == null || requestTimeout == null || requestTimeout.isNegative()
                || requestTimeout.isZero()) {
            throw new IllegalArgumentException("A complete control-plane HTTP configuration is required.");
        }
        this.baseUri = normalizeBaseUri(baseUrl, allowInsecureLanHttp);
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
                        event.totalTokens(), event.source().name()))
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
        return normalizeBaseUri(baseUrl, false);
    }

    static URI normalizeBaseUri(String baseUrl, boolean allowInsecureLanHttp) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("Control-plane base URL is required.");
        }
        final URI raw;
        try {
            raw = new URI(baseUrl.trim());
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException("Control-plane base URL is invalid.");
        }
        String scheme = raw.getScheme();
        boolean https = "https".equalsIgnoreCase(scheme);
        boolean localHttp = "http".equalsIgnoreCase(scheme) && isDevelopmentLoopbackHost(raw.getHost());
        boolean approvedLanHttp = "http".equalsIgnoreCase(scheme) && allowInsecureLanHttp
                && isPrivateLanIpv4(raw.getHost());
        if ((!https && !localHttp && !approvedLanHttp)
                || raw.getHost() == null || raw.getUserInfo() != null || raw.getQuery() != null
                || raw.getFragment() != null || !isRootPath(raw.getPath())) {
            throw new IllegalArgumentException("Control-plane base URL must be HTTPS, loopback HTTP, or explicitly approved private LAN HTTP.");
        }
        try {
            return new URI(scheme.toLowerCase(Locale.ROOT), null, raw.getHost(), raw.getPort(), "/", null, null);
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException("Control-plane base URL is invalid.");
        }
    }

    private static boolean isRootPath(String path) {
        return path == null || path.isEmpty() || "/".equals(path);
    }

    private static boolean isDevelopmentLoopbackHost(String host) {
        return "localhost".equalsIgnoreCase(host)
                || "127.0.0.1".equals(host)
                || "::1".equals(host)
                || "[::1]".equals(host);
    }

    private static boolean isPrivateLanIpv4(String host) {
        if (host == null) {
            return false;
        }
        String[] octets = host.split("\\.", -1);
        if (octets.length != 4) {
            return false;
        }
        int[] values = new int[4];
        for (int index = 0; index < octets.length; index++) {
            if (octets[index].isEmpty() || octets[index].length() > 3) {
                return false;
            }
            for (int position = 0; position < octets[index].length(); position++) {
                if (!Character.isDigit(octets[index].charAt(position))) {
                    return false;
                }
            }
            try {
                values[index] = Integer.parseInt(octets[index]);
            } catch (NumberFormatException exception) {
                return false;
            }
            if (values[index] > 255) {
                return false;
            }
        }
        return values[0] == 10 || (values[0] == 172 && values[1] >= 16 && values[1] <= 31)
                || (values[0] == 192 && values[1] == 168);
    }

    private record LoginBody(String email, String password) {
    }

    private record RefreshBody(String refreshToken) {
    }

    private record LocalUsageEventsBody(List<UsageEventBody> events) {
    }

    private record MeterDeviceBody(UUID deviceId) {
    }

    private record UsageEventBody(String eventId, String occurredAt, long totalTokens, String source) {
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
            return new ControlPlaneAuthSession(accessToken, refreshToken, user);
        }
    }
}
