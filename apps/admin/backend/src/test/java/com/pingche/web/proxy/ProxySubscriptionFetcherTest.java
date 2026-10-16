package com.pingche.web.proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pingche.web.auth.DomainException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class ProxySubscriptionFetcherTest {
    @Test
    void limitsAllSubscriptionDownloadsToTwoAndReleasesPermitsAfterCompletion() throws Exception {
        var connected = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(3);
        ProxyEndpointPolicy policy = new ProxyEndpointPolicy(host -> new InetAddress[] {InetAddress.getByName("8.8.8.8")});
        ProxySubscriptionFetcher fetcher = new ProxySubscriptionFetcher(policy, (host, pinned, port, active) -> {
            connected.countDown();
            try { if (!release.await(10, TimeUnit.SECONDS)) throw new java.io.IOException(); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new java.io.IOException(); }
            return new FakeSocket("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok");
        });
        try {
            var first = executor.submit(() -> fetcher.fetch("https://one.example.com/list"));
            var second = executor.submit(() -> fetcher.fetch("https://two.example.com/list"));
            assertThat(connected.await(5, TimeUnit.SECONDS)).isTrue();
            var third = executor.submit(() -> fetcher.fetch("https://three.example.com/list"));
            assertThatThrownBy(() -> third.get(2, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                    .cause().isInstanceOf(DomainException.class)
                    .satisfies(error -> assertThat(((DomainException) error).getCode()).isEqualTo("PROXY_SUBSCRIPTION_BUSY"));
            release.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo("ok");
            assertThat(second.get(5, TimeUnit.SECONDS)).isEqualTo("ok");
            assertThat(fetcher.fetch("https://four.example.com/list")).isEqualTo("ok");
        } finally {
            release.countDown(); executor.shutdownNow(); executor.awaitTermination(5, TimeUnit.SECONDS);
            fetcher.destroy(); policy.destroy();
        }
    }

    @Test
    void fetchesFromValidatedPinnedAddressAndClosesConnection() throws Exception {
        InetAddress address = InetAddress.getByAddress(new byte[] {8, 8, 8, 8});
        ProxyEndpointPolicy policy = new ProxyEndpointPolicy(host -> new InetAddress[] {address});
        String body = "https://proxy.example.com:443";
        FakeSocket socket = new FakeSocket("HTTP/1.1 200 OK\r\nContent-Length: " + body.length() + "\r\n\r\n" + body);
        AtomicReference<InetAddress> observed = new AtomicReference<>();
        ProxySubscriptionFetcher fetcher = new ProxySubscriptionFetcher(policy, (host, pinned, port, active) -> {
            observed.set(pinned); active.set(socket); return socket;
        });
        try {
            assertThat(fetcher.fetch("https://subscription.example.com/list?token=synthetic-secret")).isEqualTo(body);
            assertThat(observed.get()).isEqualTo(address);
            assertThat(socket.closed).isTrue();
        } finally { fetcher.destroy(); policy.destroy(); }
    }

    @Test
    void refusesNonHttpsPrivateTargetsRedirectsOversizedOrTruncatedBodies() throws Exception {
        ProxyEndpointPolicy publicPolicy = new ProxyEndpointPolicy(host -> new InetAddress[] {InetAddress.getByName("8.8.8.8")});
        for (String response : new String[] {
                "HTTP/1.1 302 Found\r\nLocation: https://127.0.0.1/secret\r\nContent-Length: 0\r\n\r\n",
                "HTTP/1.1 200 OK\r\nContent-Length: 1048577\r\n\r\n",
                "HTTP/1.1 200 OK\r\nContent-Length: 20\r\n\r\nshort"
        }) {
            ProxySubscriptionFetcher fetcher = new ProxySubscriptionFetcher(publicPolicy, (host, pinned, port, active) -> new FakeSocket(response));
            try {
                assertThatThrownBy(() -> fetcher.fetch("https://subscription.example.com/list?token=synthetic-secret"))
                        .isInstanceOf(DomainException.class).hasMessageNotContaining("synthetic-secret");
                assertThatThrownBy(() -> fetcher.fetch("http://subscription.example.com/list")).isInstanceOf(DomainException.class);
            } finally { fetcher.destroy(); }
        }
        ProxyEndpointPolicy privatePolicy = new ProxyEndpointPolicy(host -> new InetAddress[] {InetAddress.getLoopbackAddress()});
        ProxySubscriptionFetcher blocked = new ProxySubscriptionFetcher(privatePolicy, (host, pinned, port, active) -> { throw new AssertionError("Must not connect"); });
        try { assertThatThrownBy(() -> blocked.fetch("https://subscription.example.com/list")).isInstanceOf(DomainException.class); }
        finally { blocked.destroy(); privatePolicy.destroy(); publicPolicy.destroy(); }
    }

    private static final class FakeSocket extends Socket {
        private final byte[] response;
        private boolean closed;
        FakeSocket(String response) { this.response = response.getBytes(StandardCharsets.US_ASCII); }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(response); }
        @Override public OutputStream getOutputStream() { return new ByteArrayOutputStream(); }
        @Override public synchronized void close() { closed = true; }
    }
}
