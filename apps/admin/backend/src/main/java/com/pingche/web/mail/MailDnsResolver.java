package com.pingche.web.mail;

import com.pingche.web.auth.DomainException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class MailDnsResolver {
    private static final List<String> ENDPOINTS = List.of(
            "https://cloudflare-dns.com/dns-query", "https://dns.google/resolve");
    private static final int MAX_BODY_BYTES = 16_384;
    private static final int MAX_ADDRESSES = 32;
    private static final long REQUEST_NANOS = TimeUnit.SECONDS.toNanos(2);
    private static final long TOTAL_NANOS = TimeUnit.SECONDS.toNanos(8);
    private final ObjectMapper mapper;
    private final HttpClient client;
    private final SystemResolver systemResolver;

    @Autowired
    public MailDnsResolver(ObjectMapper mapper) {
        this(mapper, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
                .followRedirects(HttpClient.Redirect.NEVER).build(), InetAddress::getAllByName);
    }

    MailDnsResolver(ObjectMapper mapper, HttpClient client, SystemResolver systemResolver) {
        this.mapper = mapper;
        this.client = client;
        this.systemResolver = systemResolver;
    }

    InetAddress[] resolve(String host) {
        String name = dnsName(host);
        try {
            InetAddress[] addresses = systemResolver.resolve(name);
            if (addresses == null) throw failure();
            boolean fakeIp = false;
            for (InetAddress address : addresses) {
                if (isFakeIp(address)) fakeIp = true;
                // Preserve other non-public results so the target policy rejects them without fallback.
                else if (!MailTargetPolicy.isPublic(address)) return addresses;
            }
            if (!fakeIp) return addresses;
        } catch (UnknownHostException ignored) {
            // A trusted HTTPS resolver can still work when the local DNS server cannot resolve the host.
        } catch (RuntimeException ignored) {
            throw failure();
        }
        long deadline = System.nanoTime() + TOTAL_NANOS;
        for (String endpoint : ENDPOINTS) {
            for (int type : new int[] {1, 28}) {
                byte[] body = query(endpoint, name, type, deadline);
                if (body == null) break;
                InetAddress[] addresses = parse(body, name, type);
                if (addresses.length > 0) return addresses;
            }
        }
        throw failure();
    }

    private byte[] query(String endpoint, String host, int type, long deadline) {
        long remaining = Math.min(REQUEST_NANOS, deadline - System.nanoTime());
        if (remaining <= 0 || Thread.currentThread().isInterrupted()) throw failure();
        long requestDeadline = System.nanoTime() + remaining;
        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint + "?name=" + host + "&type=" + (type == 1 ? "A" : "AAAA")))
                .header("Accept", "application/dns-json").timeout(Duration.ofNanos(remaining)).GET().build();
        CompletableFuture<HttpResponse<byte[]>> pending = null;
        try {
            pending = client.sendAsync(request, info -> new LimitedBodySubscriber());
            // The deadline includes the complete body, even when a server stalls after sending headers.
            long wait = requestDeadline - System.nanoTime();
            if (wait <= 0) throw new TimeoutException();
            HttpResponse<byte[]> response = pending.get(wait, TimeUnit.NANOSECONDS);
            if (response.statusCode() != 200) return null;
            byte[] body = response.body();
            if (body == null || body.length > MAX_BODY_BYTES) throw failure();
            return body;
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
            throw failure();
        } catch (ExecutionException error) {
            if (error.getCause() instanceof DomainException) throw failure();
            return null;
        } catch (TimeoutException ignored) {
            return null;
        } catch (RuntimeException ignored) {
            throw failure();
        } finally {
            if (pending != null && !pending.isDone()) pending.cancel(true);
        }
    }

    private InetAddress[] parse(byte[] body, String host, int type) {
        try {
            JsonNode payload = mapper.readTree(body);
            if (payload == null || !payload.isObject() || !numberIs(payload.get("Status"), 0)
                    || payload.get("TC") == null || !payload.get("TC").isBoolean() || payload.get("TC").asBoolean()) throw failure();
            JsonNode questions = payload.get("Question");
            if (questions == null || !questions.isArray() || questions.size() != 1
                    || !numberIs(questions.get(0).get("type"), type)
                    || !host.equals(dnsName(string(questions.get(0), "name")))) throw failure();
            JsonNode answers = payload.get("Answer");
            if (answers == null) return new InetAddress[0];
            if (!answers.isArray() || answers.size() > MAX_ADDRESSES * 2) throw failure();

            Map<String, String> aliases = new HashMap<>();
            List<JsonNode> addressRecords = new ArrayList<>();
            for (JsonNode answer : answers) {
                if (numberIs(answer.get("type"), 5)) {
                    String owner = dnsName(string(answer, "name"));
                    String target = dnsName(string(answer, "data"));
                    String previous = aliases.putIfAbsent(owner, target);
                    if (previous != null && !previous.equals(target)) throw failure();
                } else if (numberIs(answer.get("type"), 1) || numberIs(answer.get("type"), 28)) {
                    addressRecords.add(answer);
                    if (addressRecords.size() > MAX_ADDRESSES) throw failure();
                }
            }
            Set<String> owners = new HashSet<>();
            String owner = host;
            while (owner != null) {
                if (!owners.add(owner) || owners.size() > MAX_ADDRESSES) throw failure();
                owner = aliases.get(owner);
            }
            Set<InetAddress> addresses = new LinkedHashSet<>();
            for (JsonNode answer : addressRecords) {
                if (!owners.contains(dnsName(string(answer, "name"))) || !numberIs(answer.get("type"), type)) continue;
                InetAddress address = literal(string(answer, "data"), type);
                if (!MailTargetPolicy.isPublic(address)) throw failure();
                addresses.add(address);
            }
            return addresses.toArray(InetAddress[]::new);
        } catch (RuntimeException | UnknownHostException ignored) {
            throw failure();
        }
    }

    private static InetAddress literal(String text, int type) throws UnknownHostException {
        if (type == 28) {
            // A colon and this alphabet force numeric IPv6 parsing: no host lookup, zone or bracket syntax.
            if (!text.contains(":") || !text.matches("[0-9a-fA-F:.]+")) throw failure();
            InetAddress address = InetAddress.getByName(text);
            if (address.getAddress().length != 16) throw failure();
            return address;
        }
        String[] octets = text.split("\\.", -1);
        if (octets.length != 4) throw failure();
        byte[] bytes = new byte[4];
        for (int index = 0; index < octets.length; index++) {
            if (!octets[index].matches("0|[1-9][0-9]{0,2}")) throw failure();
            int value = Integer.parseInt(octets[index]);
            if (value > 255) throw failure();
            bytes[index] = (byte) value;
        }
        return InetAddress.getByAddress(bytes);
    }

    private static String dnsName(String value) {
        if (value == null) throw failure();
        String name = (value.endsWith(".") ? value.substring(0, value.length() - 1) : value).toLowerCase(Locale.ROOT);
        if (name.length() > 253 || !name.contains(".") || name.matches("[0-9.]+")) throw failure();
        for (String label : name.split("\\.", -1)) {
            if (!label.matches("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")) throw failure();
        }
        return name;
    }

    private static String string(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isString()) throw failure();
        return value.asString();
    }

    private static boolean numberIs(JsonNode value, int expected) {
        return value != null && value.isIntegralNumber() && value.toString().equals(Integer.toString(expected));
    }

    private static boolean isFakeIp(InetAddress address) {
        byte[] bytes = address.getAddress();
        if (bytes.length == 4)
            return (bytes[0] & 255) == 198 && ((bytes[1] & 255) == 18 || (bytes[1] & 255) == 19);
        // Mihomo's default IPv6 Fake-IP range is exactly fdfe:dcba:9876::/64, not arbitrary ULA space.
        return bytes.length == 16 && (bytes[0] & 255) == 0xfd && (bytes[1] & 255) == 0xfe
                && (bytes[2] & 255) == 0xdc && (bytes[3] & 255) == 0xba
                && (bytes[4] & 255) == 0x98 && (bytes[5] & 255) == 0x76 && bytes[6] == 0 && bytes[7] == 0;
    }

    private static DomainException failure() {
        return new DomainException("MAIL_DNS_FAILED", "Mailbox server DNS resolution failed", HttpStatus.BAD_GATEWAY);
    }

    @FunctionalInterface
    interface SystemResolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
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
                if (buffer.remaining() > MAX_BODY_BYTES - output.size()) {
                    subscription.cancel();
                    completed.completeExceptionally(failure());
                    return;
                }
                byte[] bytes = new byte[buffer.remaining()];
                buffer.get(bytes);
                output.writeBytes(bytes);
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable error) { completed.completeExceptionally(new IOException("DNS response failed")); }
        @Override public void onComplete() { completed.complete(output.toByteArray()); }
    }
}
