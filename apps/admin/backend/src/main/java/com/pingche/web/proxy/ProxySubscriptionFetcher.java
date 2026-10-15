package com.pingche.web.proxy;

import com.pingche.web.auth.DomainException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class ProxySubscriptionFetcher implements DisposableBean {
    private final ProxyEndpointPolicy endpoints;
    private final SocketConnector connector;
    private final ScheduledExecutorService deadlines = Executors.newScheduledThreadPool(2, task -> {
        Thread thread = new Thread(task, "proxy-subscription-deadline"); thread.setDaemon(true); return thread;
    });

    @Autowired
    public ProxySubscriptionFetcher(ProxyEndpointPolicy endpoints) { this(endpoints, ProxySubscriptionFetcher::connect); }
    ProxySubscriptionFetcher(ProxyEndpointPolicy endpoints, SocketConnector connector) { this.endpoints = endpoints; this.connector = connector; }

    String fetch(String value) {
        AtomicReference<Socket> active = new AtomicReference<>();
        AtomicBoolean expired = new AtomicBoolean();
        var deadline = deadlines.schedule(() -> { expired.set(true); close(active.get()); }, 15, TimeUnit.SECONDS);
        Socket socket = null;
        try {
            if (value == null || value.length() > 8192) throw failed();
            URI uri = URI.create(value);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getRawUserInfo() != null || uri.getRawFragment() != null
                    || (uri.getPort() != -1 && uri.getPort() != 443)) throw failed();
            String host = ProxyNodeParser.normalizeHost(uri.getHost());
            InetAddress pinned = endpoints.resolve(host, false);
            if (expired.get()) throw failed();
            socket = connector.connect(host, pinned, 443, active);
            active.set(socket);
            if (expired.get()) throw failed();
            String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
            if (uri.getRawQuery() != null) path += "?" + uri.getRawQuery();
            if (path.chars().anyMatch(c -> c < 33 || c > 126)) throw failed();
            String authority = host.contains(":") ? "[" + host + "]" : host;
            socket.getOutputStream().write(("GET " + path + " HTTP/1.1\r\nHost: " + authority
                    + "\r\nAccept: text/plain\r\nAccept-Encoding: identity\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            byte[] body = readResponse(socket.getInputStream());
            if (expired.get()) throw failed();
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(body)).toString();
        } catch (IOException | RuntimeException ignored) { throw failed(); }
        finally { deadline.cancel(false); close(socket); close(active.get()); }
    }

    private static Socket connect(String host, InetAddress pinned, int port, AtomicReference<Socket> active) throws IOException {
        Socket raw = new Socket(); active.set(raw);
        raw.connect(new InetSocketAddress(pinned, port), 5000); raw.setSoTimeout(10_000);
        SSLSocket tls = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault()).createSocket(raw, host, port, true);
        active.set(tls);
        SSLParameters parameters = tls.getSSLParameters();
        parameters.setEndpointIdentificationAlgorithm("HTTPS");
        if (!host.contains(":") && !host.matches("[0-9.]+")) parameters.setServerNames(List.of(new SNIHostName(host)));
        parameters.setProtocols(new String[] {"TLSv1.3", "TLSv1.2"});
        tls.setSSLParameters(parameters); tls.startHandshake();
        return tls;
    }

    private static byte[] readResponse(InputStream input) throws IOException {
        int[] headerBytes = {0};
        String status = line(input, headerBytes);
        if (!status.matches("HTTP/1\\.[01] 200(?: .*)?")) throw failed();
        Map<String, String> headers = new LinkedHashMap<>();
        for (String value; !(value = line(input, headerBytes)).isEmpty();) {
            int colon = value.indexOf(':');
            if (colon < 1 || value.charAt(0) == ' ' || value.charAt(0) == '\t') throw failed();
            String name = value.substring(0, colon).toLowerCase(Locale.ROOT);
            String previous = headers.putIfAbsent(name, value.substring(colon + 1).trim());
            if (previous != null && SENSITIVE_HEADERS.contains(name)) throw failed();
        }
        if (!headers.getOrDefault("content-encoding", "identity").equalsIgnoreCase("identity")) throw failed();
        String transfer = headers.get("transfer-encoding");
        String length = headers.get("content-length");
        if (transfer != null && length != null) throw failed();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        if (transfer != null) {
            if (!transfer.equalsIgnoreCase("chunked")) throw failed();
            while (true) {
                String chunk = line(input, headerBytes);
                if (!chunk.matches("[0-9a-fA-F]{1,8}")) throw failed();
                long size = Long.parseLong(chunk, 16);
                if (size > ProxyNodeParser.MAX_SUBSCRIPTION_BYTES - body.size()) throw failed();
                if (size == 0) {
                    while (!line(input, headerBytes).isEmpty()) { }
                    break;
                }
                readExactly(input, body, (int) size);
                if (input.read() != '\r' || input.read() != '\n') throw failed();
            }
        } else if (length != null) {
            if (!length.matches("[0-9]{1,10}")) throw failed();
            long size = Long.parseLong(length);
            if (size > ProxyNodeParser.MAX_SUBSCRIPTION_BYTES) throw failed();
            readExactly(input, body, (int) size);
        } else {
            byte[] buffer = new byte[8192];
            int size;
            while ((size = input.read(buffer)) != -1) {
                if (size > ProxyNodeParser.MAX_SUBSCRIPTION_BYTES - body.size()) throw failed();
                body.write(buffer, 0, size);
            }
        }
        return body.toByteArray();
    }

    private static final java.util.Set<String> SENSITIVE_HEADERS = java.util.Set.of("content-length", "transfer-encoding", "content-encoding");

    private static String line(InputStream input, int[] total) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        while (true) {
            int value = input.read();
            if (value == -1 || ++total[0] > 65_536 || line.size() > 8192) throw failed();
            if (value == '\r') {
                if (input.read() != '\n' || ++total[0] > 65_536) throw failed();
                return line.toString(StandardCharsets.US_ASCII);
            }
            if ((value < 32 && value != '\t') || value > 126) throw failed();
            line.write(value);
        }
    }

    private static void readExactly(InputStream input, ByteArrayOutputStream body, int count) throws IOException {
        byte[] buffer = new byte[8192];
        int remaining = count;
        while (remaining > 0) {
            int read = input.read(buffer, 0, Math.min(remaining, buffer.length));
            if (read == -1) throw failed();
            body.write(buffer, 0, read); remaining -= read;
        }
    }

    private static void close(Socket socket) { if (socket != null) try { socket.close(); } catch (IOException ignored) { } }
    @Override public void destroy() { deadlines.shutdownNow(); }
    private static DomainException failed() {
        return new DomainException("PROXY_SUBSCRIPTION_FAILED", "订阅获取失败；仅支持公网 HTTPS 地址且不跟随跳转。", HttpStatus.BAD_REQUEST);
    }
    @FunctionalInterface interface SocketConnector {
        Socket connect(String host, InetAddress pinned, int port, AtomicReference<Socket> active) throws IOException;
    }
}
