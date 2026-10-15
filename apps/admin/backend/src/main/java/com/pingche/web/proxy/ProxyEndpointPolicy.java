package com.pingche.web.proxy;

import com.pingche.web.auth.DomainException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class ProxyEndpointPolicy implements DisposableBean {
    private final Resolver resolver;
    private final Semaphore available = new Semaphore(4);
    private final ExecutorService workers = Executors.newFixedThreadPool(4, runnable -> {
        Thread thread = new Thread(runnable, "managed-proxy-dns"); thread.setDaemon(true); return thread;
    });

    public ProxyEndpointPolicy() { this(InetAddress::getAllByName); }
    ProxyEndpointPolicy(Resolver resolver) { this.resolver = resolver; }

    InetAddress resolve(String value, boolean allowPrivate) {
        String host = ProxyNodeParser.normalizeHost(value);
        if (!available.tryAcquire()) throw blocked();
        Future<InetAddress[]> task = workers.submit(() -> {
            try { return resolver.resolve(host); }
            finally { available.release(); }
        });
        try {
            InetAddress[] addresses = task.get(5, TimeUnit.SECONDS);
            if (addresses == null || addresses.length == 0 || addresses.length > 32) throw blocked();
            for (InetAddress address : addresses) {
                if (address == null || address.isAnyLocalAddress() || address.isMulticastAddress()
                        || (!allowPrivate && !isPublic(address))) throw blocked();
            }
            return addresses[0];
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt(); throw blocked();
        } catch (ExecutionException | TimeoutException ignored) { throw blocked(); }
        finally { if (!task.isDone()) task.cancel(true); }
    }

    static boolean isPublic(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) return false;
        byte[] b = address.getAddress();
        if (b.length == 4) {
            int first = b[0] & 255, second = b[1] & 255;
            return first != 0 && first < 224 && !(first == 100 && second >= 64 && second <= 127)
                    && !(first == 198 && (second == 18 || second == 19))
                    && !(first == 198 && second == 51 && (b[2] & 255) == 100)
                    && !(first == 203 && second == 0 && (b[2] & 255) == 113)
                    && !(first == 192 && second == 0);
        }
        return b.length == 16 && (b[0] & 0xe0) == 0x20 && !(b[0] == 0x20 && b[1] == 0x02)
                && !(b[0] == 0x20 && b[1] == 0x01 && (b[2] & 255) == 0x0d && (b[3] & 255) == 0xb8)
                && !(b[0] == 0x20 && b[1] == 0x01 && b[2] == 0 && b[3] == 0);
    }

    @Override public void destroy() { workers.shutdownNow(); }
    private static DomainException blocked() {
        return new DomainException("PROXY_ENDPOINT_BLOCKED", "代理地址不可用或不符合网络地址策略。", HttpStatus.BAD_REQUEST);
    }
    @FunctionalInterface interface Resolver { InetAddress[] resolve(String host) throws UnknownHostException; }
}
