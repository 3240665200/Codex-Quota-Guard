package com.pingche.web.mail;

import com.pingche.web.auth.DomainException;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import javax.net.SocketFactory;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class MailTargetPolicy {
    public void validateHost(String host) {
        if (host == null || host.length() > 253 || !host.matches("(?i)[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?")
                || host.contains("..") || !host.contains(".") || host.endsWith(".localhost")
                || host.endsWith(".local") || host.matches("[0-9.]+")) throw blocked();
    }

    SocketFactory pinnedSocketFactory(String host, int port) {
        validateHost(host);
        try {
            InetAddress[] addresses = InetAddress.getAllByName(host);
            if (addresses.length == 0) throw blocked();
            for (InetAddress address : addresses) if (!isPublic(address)) throw blocked();
            return new PinnedSocketFactory(addresses[0], port);
        } catch (IOException ignored) { throw blocked(); }
    }

    static boolean isPublic(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) return false;
        byte[] b = address.getAddress();
        if (b.length == 4) {
            int first = b[0] & 255, second = b[1] & 255;
            return first != 0 && first < 224 && !(first == 100 && second >= 64 && second <= 127)
                    && !(first == 198 && (second == 18 || second == 19))
                    && !(first == 192 && second == 0);
        }
        // Allow globally routable IPv6 only; reject ULA, mapped addresses and transition mechanisms.
        return b.length == 16 && (b[0] & 0xe0) == 0x20 && !(b[0] == 0x20 && b[1] == 0x02)
                && !(b[0] == 0x20 && b[1] == 0x01 && b[2] == 0 && b[3] == 0);
    }

    private DomainException blocked() {
        return new DomainException("MAIL_HOST_BLOCKED", "Mailbox host must be a public IMAPS server",
                HttpStatus.BAD_REQUEST);
    }

    static void closeSockets(SocketFactory factory) {
        if (factory instanceof PinnedSocketFactory pinned) pinned.closeSockets();
    }

    static final class PinnedSocketFactory extends SocketFactory {
        private final InetAddress address;
        private final int port;
        private final ConcurrentLinkedQueue<Socket> sockets = new ConcurrentLinkedQueue<>();
        private volatile boolean closed;
        PinnedSocketFactory(InetAddress address, int port) { this.address = address; this.port = port; }
        @Override public Socket createSocket() throws IOException {
            if (closed) throw unsupported();
            Socket socket = new Socket() {
                @Override public void connect(SocketAddress endpoint, int timeout) throws IOException {
                    if (!(endpoint instanceof InetSocketAddress target) || target.getPort() != port)
                        throw new IOException("Mailbox target is unavailable");
                    // Connect to the validated address even if DNS changes after validation.
                    super.connect(new InetSocketAddress(address, port), timeout);
                }
                @Override public void connect(SocketAddress endpoint) throws IOException { connect(endpoint, 10_000); }
            };
            sockets.add(socket);
            if (closed) { socket.close(); throw unsupported(); }
            return socket;
        }
        void closeSockets() {
            closed = true;
            for (Socket socket : sockets) try { socket.close(); } catch (IOException ignored) { }
            sockets.clear();
        }
        @Override public Socket createSocket(String h, int p) throws IOException { throw unsupported(); }
        @Override public Socket createSocket(String h, int p, InetAddress l, int lp) throws IOException { throw unsupported(); }
        @Override public Socket createSocket(InetAddress h, int p) throws IOException { throw unsupported(); }
        @Override public Socket createSocket(InetAddress h, int p, InetAddress l, int lp) throws IOException { throw unsupported(); }
        private IOException unsupported() { return new IOException("Mailbox socket operation is unavailable"); }
    }
}
