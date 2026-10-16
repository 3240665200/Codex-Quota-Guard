package com.pingche.web.officialsession;

import com.pingche.web.auth.DomainException;
import com.pingche.web.proxy.ManagedProxyGateway;
import com.pingche.web.proxy.ProxyLease;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;

@Component
public class OfficialSessionClient {
    private static final int MAX_BODY = 1_048_576;
    private final ObjectMapper mapper;
    private final ManagedProxyGateway proxies;
    private final Function<ProxySelector, HttpClient> clients;

    @Autowired
    public OfficialSessionClient(ObjectMapper mapper, ManagedProxyGateway proxies) {
        this(mapper, proxies, selector -> HttpClient.newBuilder().proxy(selector)
                .connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build());
    }

    OfficialSessionClient(ObjectMapper mapper, ManagedProxyGateway proxies, Function<ProxySelector, HttpClient> clients) {
        this.mapper = mapper;
        this.proxies = proxies;
        this.clients = clients;
    }

    OfficialSessionSnapshot fetch(OfficialSessionCredentials credentials, UUID proxyNodeId) {
        if (proxyNodeId == null) throw proxyRequired();
        credentials.validate();
        try (ProxyLease lease = proxies.open(proxyNodeId)) {
            HttpClient client = clients.apply(lease.proxySelector());
            JsonNode me = read(client, "/me", credentials);
            String userId = requiredText(me, "id", 320);
            JsonNode accounts = read(client, "/accounts/check/v4-2023-04-27", credentials).get("accounts");
            if (accounts == null || !accounts.isObject() || accounts.size() > 500) throw invalidResponse();
            JsonNode selected = null;
            for (JsonNode item : accounts) {
                JsonNode account = item.get("account");
                if (account != null && account.isObject()
                        && credentials.requestedAccountId().equals(text(account, "account_id", 160))) {
                    // The website can expose the same account under both "default" and its ID.
                    // Accept identical aliases, but never choose silently between conflicting records.
                    if (selected != null && !selected.equals(item)) throw invalidResponse();
                    selected = item;
                }
            }
            if (selected == null) throw failure("OFFICIAL_ACCOUNT_MISMATCH");
            JsonNode account = selected.get("account");
            JsonNode entitlement = selected.get("entitlement");
            OfficialAccountInfo info = new OfficialAccountInfo(userId, text(me, "email", 320), text(me, "name", 320),
                    credentials.requestedAccountId(), text(account, "name", 320), text(account, "plan_type", 100),
                    text(account, "account_user_role", 100), bool(account, "is_deactivated"),
                    entitlement == null || entitlement.isNull() ? null : bool(entitlement, "has_active_subscription"));
            return new OfficialSessionSnapshot(info, sessions(read(client, "/accounts/sessions", credentials)));
        } catch (DomainException failure) { throw failure; }
        catch (RuntimeException ignored) { throw failure("OFFICIAL_UNAVAILABLE"); }
    }

    private JsonNode read(HttpClient client, String path, OfficialSessionCredentials credentials) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("https://chatgpt.com/backend-api" + path))
                .timeout(Duration.ofSeconds(10)).header("Accept", "application/json")
                .header("Authorization", credentials.authorization())
                .header("ChatGPT-Account-ID", credentials.requestedAccountId()).GET();
        if (credentials.cookie() != null && !credentials.cookie().isBlank()) builder.header("Cookie", credentials.cookie());
        CompletableFuture<HttpResponse<byte[]>> pending = null;
        try {
            pending = client.sendAsync(builder.build(), ignored -> new LimitedBodySubscriber());
            HttpResponse<byte[]> response = pending.get(10, TimeUnit.SECONDS);
            if (response.headers().firstValue("cf-mitigated")
                    .map(value -> "challenge".equalsIgnoreCase(value.trim())).orElse(false)) {
                throw failure("OFFICIAL_CHALLENGE_REQUIRED");
            }
            switch (response.statusCode()) {
                case 200 -> { }
                case 401 -> throw failure("OFFICIAL_AUTH_REQUIRED");
                case 403 -> throw failure("OFFICIAL_ACCESS_BLOCKED");
                case 429 -> throw failure("OFFICIAL_RATE_LIMITED");
                default -> throw failure("OFFICIAL_UNAVAILABLE");
            }
            byte[] body = response.body();
            String contentType = response.headers().firstValue("Content-Type").orElse("").toLowerCase(Locale.ROOT);
            if (!contentType.startsWith("application/json") || body == null || body.length > MAX_BODY) throw invalidResponse();
            try {
                JsonNode payload = mapper.reader()
                        .with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).readTree(body);
                if (payload == null || !payload.isObject()) throw invalidResponse();
                return payload;
            } catch (RuntimeException ignored) { throw invalidResponse(); }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
            throw failure("OFFICIAL_UNAVAILABLE");
        } catch (ExecutionException | TimeoutException ignored) { throw failure("OFFICIAL_UNAVAILABLE"); }
        finally { if (pending != null && !pending.isDone()) pending.cancel(true); }
    }

    OfficialSessionWriteResult logoutAll(OfficialSessionCredentials credentials, UUID proxyNodeId) {
        // The official web client sends no requestBody for logout_all.
        return write(credentials,proxyNodeId,"/accounts/logout_all",null);
    }

    OfficialSessionWriteResult revokeSession(OfficialSessionCredentials credentials, UUID proxyNodeId, String sessionId) {
        if(sessionId==null || sessionId.isBlank() || sessionId.length()>512
                || sessionId.chars().anyMatch(c -> c<32 || c==127)) {
            return new OfficialSessionWriteResult("REJECTED","OFFICIAL_SESSION_INVALID");
        }
        return write(credentials,proxyNodeId,"/accounts/sessions/revoke",Map.of("session_id",sessionId));
    }

    private OfficialSessionWriteResult write(OfficialSessionCredentials credentials, UUID proxyNodeId,
            String path, Map<String,String> payload) {
        if (proxyNodeId == null) return new OfficialSessionWriteResult("REJECTED", "OFFICIAL_PROXY_REQUIRED");
        boolean dispatched=false;
        CompletableFuture<HttpResponse<byte[]>> pending=null;
        try {
            credentials.validate();
            try(ProxyLease lease=proxies.open(proxyNodeId)) {
                HttpClient client=clients.apply(lease.proxySelector());
                var request=HttpRequest.newBuilder(URI.create("https://chatgpt.com/backend-api"+path))
                        .timeout(Duration.ofSeconds(10)).header("Accept","application/json")
                        .header("Content-Type","application/json").header("Authorization",credentials.authorization())
                        .header("ChatGPT-Account-ID",credentials.requestedAccountId());
                if(credentials.cookie()!=null && !credentials.cookie().isBlank())request.header("Cookie",credentials.cookie());
                request.POST(payload==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload)));
                dispatched=true;
                // Deliberately no application retry, redirect, or credential refresh for mutations.
                pending=client.sendAsync(request.build(),ignored -> new LimitedBodySubscriber());
                HttpResponse<byte[]> response=pending.get(10,TimeUnit.SECONDS);
                if(response.headers().firstValue("cf-mitigated").map(v -> "challenge".equalsIgnoreCase(v.trim())).orElse(false))
                    return new OfficialSessionWriteResult("REJECTED","OFFICIAL_CHALLENGE_REQUIRED");
                String rejection=switch(response.statusCode()) {
                    case 400,404,405,409,422 -> "OFFICIAL_LOGOUT_REJECTED";
                    case 401 -> "OFFICIAL_AUTH_REQUIRED";
                    case 403 -> "OFFICIAL_ACCESS_BLOCKED";
                    case 429 -> "OFFICIAL_RATE_LIMITED";
                    default -> null;
                };
                if(rejection!=null)return new OfficialSessionWriteResult("REJECTED",rejection);
                if(response.statusCode()!=200 && response.statusCode()!=202 && response.statusCode()!=204)return uncertainWrite();
                byte[] body=response.body();
                if(body==null || body.length>MAX_BODY)return uncertainWrite();
                if(response.statusCode()==204 && body.length==0)return new OfficialSessionWriteResult("ACCEPTED",null);
                String type=response.headers().firstValue("Content-Type").orElse("").toLowerCase(Locale.ROOT);
                if(!type.startsWith("application/json") && !(body.length==0 && type.isEmpty()))return uncertainWrite();
                if(body.length>0) {
                    JsonNode result=mapper.readTree(body);
                    if(result==null || (result.isBoolean() && !result.asBoolean())
                            || (result.isObject() && ((result.has("error") && !result.get("error").isNull())
                            || (result.has("success") && result.get("success").isBoolean() && !result.get("success").asBoolean())
                            || (result.has("ok") && result.get("ok").isBoolean() && !result.get("ok").asBoolean()))))return uncertainWrite();
                }
                return new OfficialSessionWriteResult("ACCEPTED",null);
            }
        } catch(InterruptedException ignored) {
            Thread.currentThread().interrupt();return uncertainWrite();
        } catch(DomainException failure) {
            return dispatched ? uncertainWrite() : new OfficialSessionWriteResult("REJECTED", failure.getCode());
        } catch(ExecutionException|TimeoutException|RuntimeException ignored) {
            return dispatched?uncertainWrite():new OfficialSessionWriteResult("REJECTED","OFFICIAL_LOGOUT_NOT_SENT");
        } finally { if(pending!=null && !pending.isDone())pending.cancel(true); }
    }

    private OfficialSessionWriteResult uncertainWrite() {
        return new OfficialSessionWriteResult("UNKNOWN","OFFICIAL_LOGOUT_OUTCOME_UNKNOWN");
    }

    private OfficialSessions sessions(JsonNode payload) {
        Boolean visible = bool(payload, "show_session_manager");
        JsonNode devices = payload.get("devices");
        if (visible == null || devices == null || !devices.isArray() || devices.size() > 500) throw invalidResponse();
        List<OfficialSessionInfo> rows = new ArrayList<>();
        for (JsonNode device : devices) {
            if (!device.isObject()) throw invalidResponse();
            JsonNode apps = device.get("app_sessions");
            if (apps == null || !apps.isArray() || apps.size() > 100) throw invalidResponse();
            List<String> applications = new ArrayList<>();
            for (JsonNode app : apps) applications.add(requiredText(app, "client_name", 320));
            rows.add(new OfficialSessionInfo(requiredText(device, "render_id", 320), text(device, "session_id", 512),
                    text(device, "platform", 100), text(device, "display_name", 320), text(device, "device_model", 320),
                    text(device, "human_readable_description", 1024), text(device, "os_version", 100),
                    bool(device, "is_current_device"), bool(device, "is_trusted_device"),
                    timestamp(device, "last_signed_in_timestamp_second"), timestamp(device, "session_created_at_seconds"),
                    text(device, "last_signed_in_city", 320), text(device, "last_signed_in_region_code", 100),
                    text(device, "last_signed_in_country", 100), applications));
        }
        return new OfficialSessions(rows, visible);
    }

    private String requiredText(JsonNode node, String field, int max) {
        String value = text(node, field, max);
        if (value == null || value.isBlank()) throw invalidResponse();
        return value;
    }

    private String text(JsonNode node, String field, int max) {
        if (!node.isObject()) throw invalidResponse();
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) return null;
        if (!value.isString() || value.asString().length() > max) throw invalidResponse();
        return value.asString();
    }

    private Boolean bool(JsonNode node, String field) {
        if (!node.isObject()) throw invalidResponse();
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) return null;
        if (!value.isBoolean()) throw invalidResponse();
        return value.asBoolean();
    }

    private Instant timestamp(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) return null;
        if (!value.isNumber()) throw invalidResponse();
        BigDecimal seconds = value.decimalValue();
        if (seconds.signum() < 0 || seconds.compareTo(BigDecimal.valueOf(253_402_300_800L)) >= 0) {
            throw invalidResponse();
        }
        long wholeSeconds = seconds.longValue();
        long nanos = seconds.subtract(BigDecimal.valueOf(wholeSeconds)).movePointRight(9)
                .setScale(0, RoundingMode.DOWN).longValueExact();
        return Instant.ofEpochSecond(wholeSeconds, nanos);
    }

    private DomainException invalidResponse() { return failure("OFFICIAL_RESPONSE_INVALID"); }

    private DomainException proxyRequired() {
        return new DomainException("OFFICIAL_PROXY_REQUIRED", "请先为官方账户选择已启用的代理节点；禁止直连。", HttpStatus.BAD_REQUEST);
    }

    private DomainException failure(String code) {
        String message = switch (code) {
            case "OFFICIAL_AUTH_REQUIRED" -> "Official authorization must be renewed";
            case "OFFICIAL_CHALLENGE_REQUIRED" -> "The official website requires browser verification; this HTTP connection cannot read account sessions";
            case "OFFICIAL_ACCESS_BLOCKED" -> "Official access is blocked; complete any required verification on the official website";
            case "OFFICIAL_RATE_LIMITED" -> "Official service rate limit reached";
            case "OFFICIAL_ACCOUNT_MISMATCH" -> "Selected official account was not found in the authorized account list";
            case "OFFICIAL_RESPONSE_INVALID" -> "Official response could not be verified";
            default -> "Official service is unavailable";
        };
        return new DomainException(code, message, HttpStatus.BAD_GATEWAY);
    }

    static final class LimitedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> completed = new CompletableFuture<>();
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        @Override public CompletionStage<byte[]> getBody() { return completed; }
        @Override public void onSubscribe(Flow.Subscription subscription) { this.subscription = subscription; subscription.request(1); }
        @Override public void onNext(List<ByteBuffer> buffers) {
            if (completed.isDone()) return;
            for (ByteBuffer buffer : buffers) {
                if (buffer.remaining() > MAX_BODY - output.size()) {
                    subscription.cancel();
                    completed.completeExceptionally(new IOException("Official response exceeds limits"));
                    return;
                }
                byte[] bytes = new byte[buffer.remaining()];
                buffer.get(bytes);
                output.writeBytes(bytes);
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable ignored) { completed.completeExceptionally(new IOException("Official response failed")); }
        @Override public void onComplete() { completed.complete(output.toByteArray()); }
    }
}
