package com.pingche.web.proxy;

import com.pingche.web.auth.DomainException;
import java.io.ByteArrayOutputStream;
import java.net.InetAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
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
import tools.jackson.databind.ObjectMapper;

/** A credential-free request through the same managed transport used for official account traffic. */
@Component
public class ProxyNodeProbe {
    static final URI TARGET = URI.create("https://ipwho.is/?fields=success,ip,country,region,city");
    private static final int MAX_BODY = 16_384;
    private final ManagedProxyGateway gateway;
    private final ObjectMapper json;
    private final Function<ProxySelector, HttpClient> clients;

    @Autowired
    public ProxyNodeProbe(ManagedProxyGateway gateway, ObjectMapper json) {
        this(gateway, json, selector -> HttpClient.newBuilder().proxy(selector)
                .connectTimeout(Duration.ofSeconds(8)).followRedirects(HttpClient.Redirect.NEVER).build());
    }

    ProxyNodeProbe(ManagedProxyGateway gateway, ObjectMapper json, Function<ProxySelector, HttpClient> clients) {
        this.gateway = gateway;
        this.json = json;
        this.clients = clients;
    }

    Result test(UUID nodeId) {
        return test(null, nodeId);
    }

    Result test(UUID frontNodeId, UUID nodeId) {
        CompletableFuture<HttpResponse<byte[]>> pending = null;
        try (ProxyLease lease = frontNodeId == null ? gateway.open(nodeId, ManagedProxyGateway.Purpose.NODE_TEST)
                : gateway.open(frontNodeId, nodeId, ManagedProxyGateway.Purpose.NODE_TEST)) {
            HttpRequest request = HttpRequest.newBuilder(TARGET).GET().header("Accept", "application/json")
                    .timeout(Duration.ofSeconds(12)).build();
            pending = clients.apply(lease.proxySelector()).sendAsync(request, ignored -> new LimitedBodySubscriber());
            HttpResponse<byte[]> response = pending.get(12, TimeUnit.SECONDS);
            if (response.statusCode() != 200 || response.body() == null || response.body().length > MAX_BODY
                    || !response.headers().firstValue("Content-Type").orElse("").toLowerCase(Locale.ROOT).startsWith("application/json"))
                throw invalidResponse();
            JsonNode body;
            try { body = json.readTree(response.body()); }
            catch (RuntimeException ignored) { throw invalidResponse(); }
            if (body == null || !body.isObject() || !body.has("success") || !body.get("success").isBoolean()
                    || !body.get("success").asBoolean()) throw invalidResponse();
            String ip = field(body, "ip", 45);
            if (ip == null || !ip.matches("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}|[0-9a-fA-F:]+") || !ip.contains(".") && !ip.contains(":"))
                throw invalidResponse();
            // Literal-only parsing: the metadata service can never cause a DNS lookup or a new request.
            try { if (!ProxyEndpointPolicy.isPublic(InetAddress.getByName(ip))) throw invalidResponse(); }
            catch (UnknownHostException ignored) { throw invalidResponse(); }
            return new Result(ip, field(body, "country", 120), field(body, "region", 120), field(body, "city", 120));
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt(); throw connectionFailed();
        } catch (TimeoutException ignored) { throw timeout(); }
        catch (ExecutionException failure) {
            if (failure.getCause() instanceof HttpTimeoutException) throw timeout();
            throw connectionFailed();
        } catch (DomainException failure) { throw failure; }
        catch (RuntimeException ignored) { throw connectionFailed(); }
        finally { if (pending != null && !pending.isDone()) pending.cancel(true); }
    }

    private String field(JsonNode body, String key, int maximum) {
        JsonNode field = body.get(key);
        if (field == null || field.isNull()) return null;
        if (!field.isString()) throw invalidResponse();
        String value = field.asString().trim();
        if (value.length() > maximum || value.chars().anyMatch(c -> c < 32 || c == 127)) throw invalidResponse();
        return value.isEmpty() ? null : value;
    }

    private static DomainException invalidResponse() {
        return new DomainException("PROXY_TEST_RESPONSE_INVALID", "出口检测服务返回无效结果，请稍后重试。", HttpStatus.BAD_GATEWAY);
    }
    private static DomainException timeout() {
        return new DomainException("PROXY_CONNECT_TIMEOUT", "代理测试超时，请检查节点连接；未回退直连。", HttpStatus.GATEWAY_TIMEOUT);
    }
    private static DomainException connectionFailed() {
        return new DomainException("PROXY_CONNECTION_FAILED", "代理连接测试失败，请检查节点及认证信息；未回退直连。", HttpStatus.BAD_GATEWAY);
    }

    record Result(String egressIp, String country, String region, String city) { }

    static final class LimitedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        @Override public CompletionStage<byte[]> getBody() { return result; }
        @Override public void onSubscribe(Flow.Subscription value) { subscription = value; value.request(1); }
        @Override public void onNext(List<ByteBuffer> chunks) {
            for (ByteBuffer chunk : chunks) {
                if (chunk.remaining() > MAX_BODY - output.size()) {
                    subscription.cancel(); result.completeExceptionally(invalidResponse()); return;
                }
                byte[] bytes = new byte[chunk.remaining()]; chunk.get(bytes); output.writeBytes(bytes);
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable error) { result.completeExceptionally(connectionFailed()); }
        @Override public void onComplete() { result.complete(output.toByteArray()); }
    }
}
