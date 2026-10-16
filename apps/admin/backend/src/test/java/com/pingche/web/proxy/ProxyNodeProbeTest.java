package com.pingche.web.proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.pingche.web.auth.DomainException;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class ProxyNodeProbeTest {
    private final UUID id = UUID.randomUUID();
    private final ManagedProxyGateway gateway = mock(ManagedProxyGateway.class);
    private final ProxyLease lease = mock(ProxyLease.class);
    private final HttpClient http = mock(HttpClient.class);

    @Test
    void readsActualEgressMetadataThroughChosenNodeWithNoAccountCredentials() {
        var probe = probe();
        when(http.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenAnswer(call -> {
            HttpRequest request = call.getArgument(0);
            assertThat(request.uri()).isEqualTo(ProxyNodeProbe.TARGET);
            assertThat(request.method()).isEqualTo("GET");
            assertThat(request.headers().map().keySet()).containsExactly("Accept");
            assertThat(request.timeout()).isPresent();
            return CompletableFuture.completedFuture(response(200,
                    "{\"success\":true,\"ip\":\"8.8.8.8\",\"country\":\"United States\",\"region\":\"California\",\"city\":\"Mountain View\"}"));
        });
        var result = probe.test(id);
        assertThat(result.egressIp()).isEqualTo("8.8.8.8");
        assertThat(result.city()).isEqualTo("Mountain View");
        verify(gateway).open(id, ManagedProxyGateway.Purpose.NODE_TEST);
        verify(lease).close();
    }

    @Test
    void rejectsRedirectHtmlProviderFailureInvalidIpAndNeverEchoesResponse() {
        for (String body : List.of("<html>private-token</html>", "{\"success\":false,\"message\":\"private-token\"}",
                "{\"success\":true,\"ip\":\"127.0.0.1\"}", "{\"success\":true,\"ip\":\"attacker.example.com\"}")) {
            var probe = probe();
            when(http.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                    .thenReturn(CompletableFuture.completedFuture(response(200, body)));
            assertThatThrownBy(() -> probe.test(id)).isInstanceOf(DomainException.class).hasMessageNotContaining("private-token");
        }
        when(http.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(CompletableFuture.completedFuture(response(302, "")));
        assertThatThrownBy(() -> probe().test(id)).isInstanceOf(DomainException.class);
    }

    @Test
    void closesFailedLeaseCancelsTimeoutAndBoundsStreamedBody() throws Exception {
        var probe = probe();
        CompletableFuture<HttpResponse<byte[]>> pending = mock(CompletableFuture.class);
        when(pending.get(12, TimeUnit.SECONDS)).thenThrow(new TimeoutException());
        when(http.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(pending);
        assertThatThrownBy(() -> probe.test(id)).isInstanceOf(DomainException.class);
        verify(pending).cancel(true);
        verify(lease).close();
        var subscriber = new ProxyNodeProbe.LimitedBodySubscriber();
        Flow.Subscription subscription = mock(Flow.Subscription.class);
        subscriber.onSubscribe(subscription);
        subscriber.onNext(List.of(ByteBuffer.wrap(new byte[16_385])));
        assertThat(subscriber.getBody().toCompletableFuture()).isCompletedExceptionally();
        verify(subscription).cancel();
    }

    private ProxyNodeProbe probe() {
        ProxySelector selector = mock(ProxySelector.class);
        when(gateway.open(id, ManagedProxyGateway.Purpose.NODE_TEST)).thenReturn(lease);
        when(lease.proxySelector()).thenReturn(selector);
        return new ProxyNodeProbe(gateway, new ObjectMapper(), actual -> {
            assertThat(actual).isSameAs(selector);
            return http;
        });
    }

    @SuppressWarnings("unchecked")
    private HttpResponse<byte[]> response(int status, String body) {
        HttpResponse<byte[]> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body.getBytes(StandardCharsets.UTF_8));
        when(response.headers()).thenReturn(HttpHeaders.of(Map.of("Content-Type", List.of("application/json")), (a, b) -> true));
        return response;
    }
}
