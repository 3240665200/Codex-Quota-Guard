package com.codexquotaguard.backend.officiallogin;

import com.codexquotaguard.backend.controlplane.ControlPlaneException;
import com.codexquotaguard.backend.controlplane.ControlPlaneOrigin;
import java.io.ByteArrayOutputStream;
import java.net.*;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
final class HttpOfficialLoginGateway implements OfficialLoginGateway {
    private static final int MAX_RESPONSE = 65_536;
    private final URI base;
    private final ObjectMapper mapper;
    private final HttpClient http;

    @Autowired
    HttpOfficialLoginGateway(@Value("${quota-guard.control-plane.base-url:http://127.0.0.1:8080}") String baseUrl, ObjectMapper mapper) {
        this(baseUrl, mapper, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER).proxy(new ProxySelector() {
                    @Override public List<Proxy> select(URI uri) { return List.of(Proxy.NO_PROXY); }
                    @Override public void connectFailed(URI uri, SocketAddress address, java.io.IOException failure) { }
                }).build());
    }

    HttpOfficialLoginGateway(String baseUrl, ObjectMapper mapper, HttpClient http) {
        try {
            // Match the origin used by system login and remembered sessions.
            // Rejecting remote HTTP here prevents the entire local backend from starting.
            URI uri = ControlPlaneOrigin.normalize(baseUrl);
            if (uri.getPort() == 0 || http.followRedirects() != HttpClient.Redirect.NEVER)
                throw new IllegalArgumentException();
            this.base = uri;
        } catch (IllegalArgumentException invalid) { throw new IllegalArgumentException("An HTTP or HTTPS control-plane origin and a non-redirecting client are required."); }
        this.mapper = mapper; this.http = http;
    }

    @Override public List<Job> jobs(String token) {
        return List.of(send(token, "/api/v1/admin/browser-logins", null, Job[].class));
    }
    @Override public Job get(String token, UUID id) { return send(token, path(id), null, Job.class); }
    @Override public Job claim(String token, UUID id, UUID helperId) { return send(token,path(id)+"/claim",Map.of("helperId",helperId),Job.class); }
    @Override public Job complete(String token, UUID id, Completion body) { return send(token,path(id)+"/complete",body,Job.class); }
    @Override public Job cancel(String token, UUID id) { return send(token,path(id)+"/cancel",Map.of(),Job.class); }

    private static String path(UUID id) {
        if (id == null) throw new OfficialLoginException("OFFICIAL_LOGIN_INVALID_PARAMETERS");
        return "/api/v1/admin/browser-logins/"+id;
    }

    private <T> T send(String token, String path, Object body, Class<T> type) {
        CompletableFuture<HttpResponse<byte[]>> pending = null;
        try {
            Duration timeout = path.endsWith("/complete") ? Duration.ofSeconds(110) : Duration.ofSeconds(15);
            var request = HttpRequest.newBuilder(base.resolve(path)).timeout(timeout)
                    .header("Accept","application/json").header("Authorization","Bearer "+token);
            if (body == null) request.GET();
            else {
                byte[] encoded=mapper.writeValueAsBytes(body);
                if(encoded.length>MAX_RESPONSE) throw new OfficialLoginException("OFFICIAL_LOGIN_REJECTED");
                request.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofByteArray(encoded));
            }
            pending = http.sendAsync(request.build(), ignored -> new LimitedBody());
            HttpResponse<byte[]> response = pending.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (response.statusCode() == 401) throw ControlPlaneException.unauthorized();
            if (response.statusCode() == 403) throw ControlPlaneException.accessDenied();
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                String code = "OFFICIAL_LOGIN_CENTRAL_UNAVAILABLE";
                try { code = OfficialLoginException.safeCode(mapper.readTree(response.body()).path("code").asString("")); }
                catch (RuntimeException ignored) { }
                throw new OfficialLoginException(code);
            }
            if (!response.headers().firstValue("Content-Type").orElse("").toLowerCase(Locale.ROOT).startsWith("application/json"))
                throw new OfficialLoginException("OFFICIAL_LOGIN_CENTRAL_UNAVAILABLE");
            return mapper.readValue(response.body(), type);
        } catch (ControlPlaneException | OfficialLoginException safe) { throw safe; }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw ControlPlaneException.unavailable(); }
        catch (Exception ignored) { throw ControlPlaneException.unavailable(); }
        finally { if (pending != null && !pending.isDone()) pending.cancel(true); }
    }

    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        @Override public CompletionStage<byte[]> getBody() { return result; }
        @Override public void onSubscribe(Flow.Subscription value) { subscription=value;value.request(1); }
        @Override public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer buffer : buffers) {
                if (buffer.remaining() > MAX_RESPONSE-bytes.size()) { subscription.cancel();result.completeExceptionally(ControlPlaneException.unavailable());return; }
                byte[] part = new byte[buffer.remaining()]; buffer.get(part);bytes.writeBytes(part);
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable ignored) { result.completeExceptionally(ControlPlaneException.unavailable()); }
        @Override public void onComplete() { result.complete(bytes.toByteArray()); }
    }
}
