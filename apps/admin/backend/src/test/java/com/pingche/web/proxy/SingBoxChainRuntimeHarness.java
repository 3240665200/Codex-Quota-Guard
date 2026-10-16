package com.pingche.web.proxy;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Loopback-only proof of actual kernel forwarding; no credentials, DNS or Internet traffic. */
public final class SingBoxChainRuntimeHarness {
    private static final String MARKER = "fixture-exit-reached";

    @FunctionalInterface
    interface Configuration { String build(int frontPort, int exitPort, int localPort); }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 1) throw new IllegalArgumentException("Expected absolute kernel path");
        verify(arguments[0], (front, exit, local) -> """
                {"log":{"disabled":true},"inbounds":[{"type":"http","tag":"local",
                "listen":"127.0.0.1","listen_port":%d,"set_system_proxy":false}],
                "outbounds":[{"type":"socks","tag":"front","server":"127.0.0.1",
                "server_port":%d,"version":"5","network":"tcp","connect_timeout":"10s"},
                {"type":"socks","tag":"upstream","server":"127.0.0.1","server_port":%d,
                "version":"5","network":"tcp","detour":"front"}],
                "route":{"rules":[{"domain":["ipwho.is"],"port":[443],"network":["tcp"],
                "action":"route","outbound":"upstream"},{"action":"reject"}]}}
                """.formatted(local, front, exit));
        System.out.println("PASS: front -> exit -> synthetic target; front failure does not bypass to reachable exit");
    }

    static void verify(String kernel, Configuration configuration) throws Exception {
        try (SocksFixture exit = new SocksFixture(null); SocksFixture front = new SocksFixture(exit)) {
            int localPort;
            try (ServerSocket reserve = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
                localPort = reserve.getLocalPort();
            }
            Process process = new ProcessBuilder(kernel, "run", "-c", "stdin")
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            try {
                try (var input = process.getOutputStream()) {
                    input.write(configuration.build(front.port(), exit.port(), localPort).getBytes(StandardCharsets.UTF_8));
                }
                long readyBy = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                boolean ready = false;
                while (process.isAlive() && System.nanoTime() < readyBy) {
                    try (Socket probe = new Socket()) {
                        probe.connect(new InetSocketAddress("127.0.0.1", localPort), 100);
                        ready = true; break;
                    } catch (IOException ignored) { Thread.sleep(20); }
                }
                require(ready, "kernel did not open local listener");
                String success = request(localPort);
                require(success.contains(MARKER), "request did not reach synthetic exit");
                require(front.connections.get() == 1, "front was bypassed or duplicated");
                require(exit.connections.get() == 1, "exit count incorrect");
                require(("127.0.0.1:" + exit.port()).equals(front.destination.get()), "front did not dial exit");
                require("ipwho.is:443".equals(exit.destination.get()), "exit did not receive final target");
                front.close();
                // Exit remains directly reachable: a wrong fallback would succeed and increment its count.
                String failed;
                try { failed = request(localPort); } catch (IOException expected) { failed = ""; }
                require(!failed.contains(MARKER), "front failure incorrectly fell back to exit");
                require(exit.connections.get() == 1, "front failure dispatched directly to exit");
                require(process.isAlive(), "kernel exited unexpectedly during failure test");
            } finally {
                process.destroy();
                if (!process.waitFor(2, TimeUnit.SECONDS)) {
                    process.destroyForcibly(); process.waitFor(2, TimeUnit.SECONDS);
                }
            }
        }
    }

    private static String request(int port) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 1000);
            socket.setSoTimeout(3000);
            socket.getOutputStream().write("CONNECT ipwho.is:443 HTTP/1.1\r\nHost: ipwho.is:443\r\n\r\n"
                    .getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            StringBuilder response = new StringBuilder();
            while (response.length() < 4096) {
                int value = socket.getInputStream().read();
                if (value < 0) break;
                response.append((char) value);
                if (response.indexOf(MARKER) >= 0) break;
            }
            return response.toString();
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class SocksFixture implements AutoCloseable {
        private final ServerSocket listener;
        private final SocksFixture forward;
        private final ExecutorService tasks = Executors.newCachedThreadPool(task -> {
            Thread thread = new Thread(task, "loopback-socks-fixture"); thread.setDaemon(true); return thread;
        });
        private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
        final AtomicInteger connections = new AtomicInteger();
        final AtomicReference<String> destination = new AtomicReference<>();

        SocksFixture(SocksFixture forward) throws IOException {
            this.forward = forward;
            listener = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
            tasks.execute(() -> {
                while (!listener.isClosed()) {
                    try {
                        Socket socket = listener.accept(); sockets.add(socket);
                        tasks.execute(() -> handle(socket));
                    } catch (IOException ignored) { break; }
                }
            });
        }
        int port() { return listener.getLocalPort(); }

        private void handle(Socket socket) {
            try (socket) {
                socket.setSoTimeout(5000);
                DataInputStream input = new DataInputStream(socket.getInputStream());
                require(input.readUnsignedByte() == 5, "not SOCKS5");
                byte[] methods = input.readNBytes(input.readUnsignedByte());
                require(methods.length > 0, "missing authentication methods");
                socket.getOutputStream().write(new byte[] {5, 0}); socket.getOutputStream().flush();
                require(input.readUnsignedByte() == 5 && input.readUnsignedByte() == 1
                        && input.readUnsignedByte() == 0, "not CONNECT");
                String host = switch (input.readUnsignedByte()) {
                    case 1 -> InetAddress.getByAddress(input.readNBytes(4)).getHostAddress();
                    case 3 -> new String(input.readNBytes(input.readUnsignedByte()), StandardCharsets.US_ASCII);
                    case 4 -> InetAddress.getByAddress(input.readNBytes(16)).getHostAddress();
                    default -> throw new IOException("unsupported address");
                };
                int port = input.readUnsignedShort();
                destination.set(host + ":" + port); connections.incrementAndGet();
                socket.getOutputStream().write(new byte[] {5, 0, 0, 1, 127, 0, 0, 1, 0, 0});
                socket.getOutputStream().flush();
                if (forward == null) {
                    require(host.equals("ipwho.is") && port == 443, "unexpected target");
                    socket.getOutputStream().write(MARKER.getBytes(StandardCharsets.US_ASCII));
                    socket.getOutputStream().flush();
                } else {
                    require(host.equals("127.0.0.1") && port == forward.port(), "unexpected upstream");
                    try (Socket upstream = new Socket("127.0.0.1", forward.port())) {
                        sockets.add(upstream); upstream.setSoTimeout(5000);
                        tasks.execute(() -> copy(socket, upstream));
                        copy(upstream, socket); sockets.remove(upstream);
                    }
                }
            } catch (IOException | AssertionError ignored) {
                // The test asserts observed destinations/counts and marker; never emit packet/credential data.
            } finally { sockets.remove(socket); }
        }
        private static void copy(Socket source, Socket target) {
            try {
                source.getInputStream().transferTo(target.getOutputStream());
                target.getOutputStream().flush();
            } catch (IOException ignored) { }
        }
        @Override public void close() {
            try { listener.close(); } catch (IOException ignored) { }
            for (Socket socket : sockets) try { socket.close(); } catch (IOException ignored) { }
            tasks.shutdownNow();
        }
    }
}
