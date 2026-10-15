package com.pingche.web.proxy;

import com.pingche.web.auth.DomainException;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntFunction;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

@Component
public class SingBoxProcessRunner implements DisposableBean {
    private final String executable;
    private final int startupMillis;
    private final int leaseMillis;
    private final Semaphore capacity;
    private final ProcessStarter starter;
    private final PortProbe probe;
    private final Set<Lease> active = ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService deadlines = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "managed-proxy-deadlines"); thread.setDaemon(true); return thread;
    });
    private volatile boolean stopped;

    @Autowired
    public SingBoxProcessRunner(@Value("${MANAGED_PROXY_SING_BOX_PATH:}") String executable,
            @Value("${MANAGED_PROXY_MAX_PROCESSES:4}") int maxProcesses,
            @Value("${MANAGED_PROXY_STARTUP_TIMEOUT_MS:5000}") int startupMillis,
            @Value("${MANAGED_PROXY_LEASE_TIMEOUT_MS:60000}") int leaseMillis) {
        this(executable, maxProcesses, startupMillis, leaseMillis, SingBoxProcessRunner::launch,
                SingBoxProcessRunner::ready);
    }

    SingBoxProcessRunner(String executable, int maxProcesses, int startupMillis, int leaseMillis,
            ProcessStarter starter, PortProbe probe) {
        this.executable = executable; this.startupMillis = Math.max(10, Math.min(startupMillis, 30_000));
        this.leaseMillis = Math.max(20, Math.min(leaseMillis, 600_000));
        capacity = new Semaphore(Math.max(1, Math.min(maxProcesses, 32)));
        this.starter = starter; this.probe = probe;
    }

    ProxyLease open(IntFunction<JsonNode> configuration) {
        if (stopped || executable == null || executable.isBlank()) throw kernelUnavailable();
        if (!capacity.tryAcquire()) throw new DomainException("PROXY_CAPACITY_EXCEEDED", "代理连接繁忙，请稍后重试。", HttpStatus.SERVICE_UNAVAILABLE);
        Process process = null;
        Lease lease = null;
        boolean returned = false;
        ScheduledFuture<?> startupDeadline = null;
        try {
            int port;
            try (ServerSocket reservation = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
                port = reservation.getLocalPort();
            }
            byte[] config = configuration.apply(port).toString().getBytes(StandardCharsets.UTF_8);
            if (config.length > 32_768) throw connectionFailed();
            process = starter.start(executable);
            Process starting = process;
            startupDeadline = deadlines.schedule(() -> terminate(starting), startupMillis, TimeUnit.MILLISECONDS);
            long expires = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(startupMillis);
            try (var input = process.getOutputStream()) { input.write(config); }
            boolean listening = false;
            while (System.nanoTime() < expires && process.isAlive() && !stopped) {
                if (probe.ready(port)) { listening = true; break; }
                Thread.sleep(Math.min(20, startupMillis));
            }
            if (!listening || !process.isAlive() || stopped || System.nanoTime() >= expires) throw connectionFailed();
            startupDeadline.cancel(false);
            lease = new Lease(process, port);
            active.add(lease);
            lease.deadline = deadlines.schedule(lease::close, leaseMillis, TimeUnit.MILLISECONDS);
            if (stopped) { lease.close(); throw connectionFailed(); }
            returned = true;
            return lease;
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt(); throw connectionFailed();
        } catch (IOException ignored) { throw kernelUnavailable(); }
        catch (RuntimeException error) {
            if (error instanceof DomainException domain) throw domain;
            throw connectionFailed();
        } finally {
            if (startupDeadline != null) startupDeadline.cancel(false);
            if (!returned) {
                if (lease != null) lease.close();
                else {
                    if (process != null) terminate(process);
                    capacity.release();
                }
            }
        }
    }

    private static Process launch(String executable) throws IOException {
        Path path = Path.of(executable);
        if (!path.isAbsolute() || !Files.isRegularFile(path) || !Files.isExecutable(path)) throw new IOException("Proxy executable unavailable");
        ProcessBuilder builder = new ProcessBuilder(path.toRealPath().toString(), "run", "-c", "stdin");
        Map<String, String> inherited = Map.copyOf(builder.environment());
        builder.environment().clear();
        for (String name : List.of("SystemRoot", "SYSTEMROOT", "WINDIR", "TEMP", "TMP", "LANG")) {
            if (inherited.containsKey(name)) builder.environment().put(name, inherited.get(name));
        }
        builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        builder.redirectError(ProcessBuilder.Redirect.DISCARD);
        return builder.start();
    }

    private static boolean ready(int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 100); return true;
        } catch (IOException ignored) { return false; }
    }

    private static void terminate(Process process) {
        process.destroy();
        try {
            if (!process.waitFor(250, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly(); process.waitFor(1, TimeUnit.SECONDS);
            }
        } catch (InterruptedException ignored) { process.destroyForcibly(); Thread.currentThread().interrupt(); }
    }

    @Override public void destroy() {
        stopped = true;
        for (Lease lease : active) lease.close();
        deadlines.shutdownNow();
    }

    private final class Lease implements ProxyLease {
        private final Process process;
        private final AtomicBoolean closed = new AtomicBoolean();
        private final ProxySelector selector;
        private volatile ScheduledFuture<?> deadline;
        Lease(Process process, int port) {
            this.process = process;
            Proxy local = new Proxy(Proxy.Type.HTTP, new InetSocketAddress("127.0.0.1", port));
            selector = new ProxySelector() {
                @Override public List<Proxy> select(URI uri) {
                    if (closed.get() || !process.isAlive()) throw new IllegalStateException("Proxy lease is unavailable");
                    if (uri == null || !"https".equalsIgnoreCase(uri.getScheme()) || !"chatgpt.com".equalsIgnoreCase(uri.getHost())
                            || (uri.getPort() != -1 && uri.getPort() != 443) || uri.getUserInfo() != null)
                        throw new IllegalArgumentException("Proxy target is not permitted");
                    return List.of(local);
                }
                @Override public void connectFailed(URI uri, SocketAddress address, IOException error) { }
            };
        }
        @Override public ProxySelector proxySelector() { return selector; }
        @Override public void close() {
            if (!closed.compareAndSet(false, true)) return;
            if (deadline != null) deadline.cancel(false);
            terminate(process); active.remove(this); capacity.release();
        }
    }

    private static DomainException kernelUnavailable() {
        return new DomainException("PROXY_KERNEL_UNAVAILABLE", "代理内核未配置或无法启动，请检查服务器代理配置。", HttpStatus.SERVICE_UNAVAILABLE);
    }
    private static DomainException connectionFailed() {
        return new DomainException("PROXY_CONNECTION_FAILED", "代理连接失败，未回退直连。", HttpStatus.BAD_GATEWAY);
    }
    @FunctionalInterface interface ProcessStarter { Process start(String executable) throws IOException; }
    @FunctionalInterface interface PortProbe { boolean ready(int port); }
}
