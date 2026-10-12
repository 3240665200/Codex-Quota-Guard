package com.pingche.web.mail;

import com.pingche.web.auth.DomainException;
import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class MailOAuthClient {
    private final ObjectMapper mapper;
    private final HttpClient client;
    @Autowired
    public MailOAuthClient(ObjectMapper mapper) {
        this(mapper, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER).build());
    }
    MailOAuthClient(ObjectMapper mapper, HttpClient client) { this.mapper = mapper; this.client = client; }

    Token refresh(MailboxConfig.AuthMode mode, String clientId, String clientSecret, String refreshToken, String tenant) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "refresh_token"); form.put("client_id", clientId); form.put("refresh_token", refreshToken);
        if (clientSecret != null && !clientSecret.isBlank()) form.put("client_secret", clientSecret);
        if (mode == MailboxConfig.AuthMode.MICROSOFT_OAUTH2)
            form.put("scope", "https://outlook.office.com/IMAP.AccessAsUser.All offline_access");
        HttpRequest request = HttpRequest.newBuilder(endpoint(mode, tenant)).timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form.entrySet().stream()
                        .map(e -> encode(e.getKey()) + "=" + encode(e.getValue())).collect(Collectors.joining("&")))).build();
        CompletableFuture<HttpResponse<byte[]>> pending = null;
        try {
            pending = client.sendAsync(request, info -> new LimitedBodySubscriber());
            // This deadline covers headers AND body completion, including a stalled response stream.
            HttpResponse<byte[]> response = pending.get(10, TimeUnit.SECONDS);
            byte[] bytes = response.body();
            if (response.statusCode() != 200 || bytes == null || bytes.length > 65_536) throw failure();
            JsonNode payload = mapper.readTree(bytes);
            String access = value(payload, "access_token");
            String rotated = value(payload, "refresh_token");
            if (access == null || access.isBlank() || access.length() > 8192
                    || (rotated != null && (rotated.isBlank() || rotated.length() > 8192))) throw failure();
            return new Token(access, rotated);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt(); throw failure();
        } catch (ExecutionException | TimeoutException | RuntimeException ignored) { throw failure(); }
        finally { if (pending != null && !pending.isDone()) pending.cancel(true); }
    }

    URI endpoint(MailboxConfig.AuthMode mode, String tenant) {
        if (mode == MailboxConfig.AuthMode.GOOGLE_OAUTH2) return URI.create("https://oauth2.googleapis.com/token");
        if (mode != MailboxConfig.AuthMode.MICROSOFT_OAUTH2) throw failure();
        String resolved = tenant == null || tenant.isBlank() ? "common" : tenant;
        if (!resolved.matches("[A-Za-z0-9][A-Za-z0-9.-]{0,99}")) throw failure();
        return URI.create("https://login.microsoftonline.com/" + resolved + "/oauth2/v2.0/token");
    }
    private String value(JsonNode node, String name) {
        JsonNode value = node.get(name); return value == null || !value.isString() ? null : value.asString();
    }
    private String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    private DomainException failure() {
        return new DomainException("MAIL_OAUTH_FAILED", "Mailbox OAuth authorization failed; renew the saved authorization",
                HttpStatus.BAD_GATEWAY);
    }
    record Token(String accessToken, String refreshToken) {
        @Override public String toString() { return "MailOAuthClient.Token[redacted]"; }
    }

    static final class LimitedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> completed = new CompletableFuture<>();
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        @Override public CompletionStage<byte[]> getBody() { return completed; }
        @Override public void onSubscribe(Flow.Subscription value) { subscription = value; value.request(1); }
        @Override public void onNext(List<ByteBuffer> buffers) {
            if (completed.isDone()) return;
            for (ByteBuffer buffer : buffers) {
                if (buffer.remaining() > 65_536 - output.size()) {
                    subscription.cancel(); completed.completeExceptionally(new IOException("OAuth response exceeds limits")); return;
                }
                byte[] bytes = new byte[buffer.remaining()]; buffer.get(bytes); output.writeBytes(bytes);
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable error) { completed.completeExceptionally(new IOException("OAuth response failed")); }
        @Override public void onComplete() { completed.complete(output.toByteArray()); }
    }
}
