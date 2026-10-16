package com.pingche.web.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.pingche.web.auth.DomainException;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;

class MailDnsResolverTest {
    private static final String HOST = "imap.example.com";
    private final HttpClient client = mock(HttpClient.class);
    private final List<HttpRequest> requests = new ArrayList<>();

    @Test void ordinaryPublicSystemAnswersNeverUseDoh() throws Exception {
        InetAddress[] expected = {address("1.1.1.1"), address("2606:4700:4700::1111")};
        assertThat(resolver(host -> expected).resolve(HOST)).containsExactly(expected);
        verifyNoInteractions(client);
    }

    @Test void explicitPublicLookupUsesTheBoundedResolverWithoutRepeatingSystemDns() throws Exception {
        reply(200, payload(1, record(HOST, 1, "1.1.1.1")));
        MailDnsResolver resolver = resolver(host -> { throw new AssertionError("Public lookup must not repeat local DNS"); });
        assertThat(resolver.resolvePublicDns(HOST)).containsExactly(address("1.1.1.1"));
        assertThat(requests).hasSize(1);
        assertThat(requests.get(0).uri().getHost()).isEqualTo("cloudflare-dns.com");
    }

    @Test void explicitPublicLookupRejectsAddressLiteralsBeforeAnyRequest() {
        MailDnsResolver resolver = resolver(host -> { throw new AssertionError("Literal must not reach local DNS"); });
        for (String literal : new String[] {"198.18.0.42", "127.0.0.1", "fdfe:dcba:9876::38", "::1"}) {
            assertThatThrownBy(() -> resolver.resolvePublicDns(literal)).isInstanceOf(DomainException.class);
        }
        verifyNoInteractions(client);
    }

    @ParameterizedTest
    @ValueSource(strings = {"10.0.0.1", "127.0.0.1", "169.254.169.254", "100.64.0.1", "0.0.0.0", "fc00::1",
            "fdfe:dcba:9876:1::1", "fdfe:dcba:9875:ffff::1", "fdfe:dcbb:9876::1", "fdfd:dcba:9876::1"})
    void nonPublicSystemAnswersRemainForPolicyToBlockWithoutDoh(String literal) throws Exception {
        InetAddress[] expected = {address(literal), address("198.18.0.1")};
        assertThat(resolver(host -> expected).resolve(HOST)).containsExactly(expected);
        verifyNoInteractions(client);
    }

    @ParameterizedTest
    @ValueSource(strings = {"fdfe:dcba:9876::", "fdfe:dcba:9876::38", "fdfe:dcba:9876:0:ffff:ffff:ffff:ffff"})
    void mihomoDefaultIpv6FakeRangeAlongsideIpv4FakeUsesDoh(String literal) throws Exception {
        reply(200, payload(1, record(HOST, 1, "1.1.1.1")));
        InetAddress[] systemAnswers = {address("198.18.0.68"), address(literal)};
        assertThat(resolver(host -> systemAnswers).resolve(HOST)).containsExactly(address("1.1.1.1"));
        assertThat(requests).hasSize(1);
    }

    @Test void fakeIpIsReplacedWithPublicAddressesFromFixedCloudflareEndpoint() throws Exception {
        reply(200, payload(1, record(HOST, 1, "1.1.1.1")));
        InetAddress[] result = resolver(host -> new InetAddress[] {address("198.19.255.254"), address("8.8.8.8")})
                .resolve(HOST);
        assertThat(result).containsExactly(address("1.1.1.1"));
        assertThat(requests).hasSize(1);
        HttpRequest request = requests.get(0);
        assertThat(request.uri().toString()).isEqualTo("https://cloudflare-dns.com/dns-query?name=imap.example.com&type=A");
        assertThat(request.method()).isEqualTo("GET");
        assertThat(request.bodyPublisher()).isEmpty();
        assertThat(request.headers().firstValue("Accept")).contains("application/dns-json");
        assertThat(request.headers().firstValue("Authorization")).isEmpty();
        assertThat(request.timeout()).hasValueSatisfying(timeout -> assertThat(timeout).isLessThanOrEqualTo(Duration.ofSeconds(4))
                .isGreaterThan(Duration.ofSeconds(3)));
    }

    @Test void unknownSystemHostUsesDohAndAcceptsUnorderedCanonicalNameChain() throws Exception {
        reply(200, payload(1, record("second.example.com.", 1, "8.8.4.4"),
                record("first.example.com.", 5, "SECOND.EXAMPLE.COM."),
                record("IMAP.EXAMPLE.COM.", 5, "first.example.com."),
                record("unrelated.example.com", 1, "9.9.9.9")));
        assertThat(unknownHostResolver().resolve(HOST)).containsExactly(address("8.8.4.4"));
    }

    @Test void emptyAAnswerFallsBackToAaaaAndDeduplicatesAddresses() throws Exception {
        reply(200, payload(1), payload(28, record(HOST, 28, "2606:4700:4700::1111"),
                record(HOST, 28, "2606:4700:4700::1111")));
        assertThat(unknownHostResolver().resolve(HOST)).containsExactly(address("2606:4700:4700::1111"));
        assertThat(requests).extracting(request -> request.uri().getRawQuery())
                .containsExactly("name=imap.example.com&type=A", "name=imap.example.com&type=AAAA");
    }

    @Test void unavailableCloudflareUsesFixedGoogleEndpoint() throws Exception {
        when(client.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenAnswer(invocation -> {
            HttpRequest request = invocation.getArgument(0);
            requests.add(request);
            return request.uri().getHost().equals("cloudflare-dns.com")
                    ? CompletableFuture.failedFuture(new IOException("private upstream diagnostic"))
                    : CompletableFuture.completedFuture(response(200, payload(1, record(HOST + ".", 1, "8.8.8.8"))));
        });
        assertThat(unknownHostResolver().resolve(HOST)).containsExactly(address("8.8.8.8"));
        assertThat(requests).extracting(request -> request.uri().toString()).containsExactly(
                "https://cloudflare-dns.com/dns-query?name=imap.example.com&type=A",
                "https://dns.google/resolve?name=imap.example.com&type=A");
    }

    @ParameterizedTest
    @ValueSource(strings = {"10.0.0.1", "127.0.0.1", "198.18.0.1", "198.19.255.255", "169.254.169.254", "100.64.0.1", "192.0.0.1", "224.0.0.1"})
    void privateOrFakeDohAddressesFailClosedEvenWhenMixedWithPublic(String literal) throws Exception {
        reply(200, payload(1, record(HOST, 1, "1.1.1.1"), record(HOST, 1, literal)));
        assertDnsFailure(unknownHostResolver());
        assertThat(requests).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"localhost", "127.1", "0x08080808", "1.1.1.999", "01.1.1.1", "1.1.1.1 ", "1.1.1.1\n", "8.8.8.8:993"})
    void aRecordsMustBeStrictAddressLiteralsWithoutFurtherDns(String literal) throws Exception {
        reply(200, payload(1, record(HOST, 1, literal)));
        assertDnsFailure(unknownHostResolver());
    }

    @ParameterizedTest
    @ValueSource(strings = {"::1", "fc00::1", "fdfe:dcba:9876::38", "fe80::1", "2002:0808:0808::", "::ffff:8.8.8.8", "2606:4700:4700::1111%1", "[2606:4700:4700::1111]", "example.com"})
    void aaaaRecordsRejectPrivateTransitionScopedAndNonLiteralData(String literal) throws Exception {
        reply(200, payload(1), payload(28, record(HOST, 28, literal)));
        assertDnsFailure(unknownHostResolver());
    }

    @Test void responseMustMatchQuestionStatusAndTruncationFields() throws Exception {
        String valid = payload(1, record(HOST, 1, "1.1.1.1"));
        for (String invalid : List.of("{", "null", "[]", valid.replace("\"Status\":0", "\"Status\":3"),
                valid.replace("\"Status\":0", "\"Status\":\"0\""), valid.replace("\"TC\":false", "\"TC\":true"),
                valid.replace("\"TC\":false,", ""), valid.replace("\"type\":1", "\"type\":28"),
                valid.replace("\"name\":\"imap.example.com\"", "\"name\":\"other.example.com\""),
                valid.replace("\"Question\":[", "\"Question\":null,\"ignored\":["))) {
            reply(200, invalid);
            assertDnsFailure(unknownHostResolver());
        }
    }

    @Test void unrelatedAnswersCannotResolveRequestedHost() throws Exception {
        reply(200, payload(1, record("other.example.com", 1, "1.1.1.1")));
        assertDnsFailure(unknownHostResolver());
    }

    @Test void cyclicOrConflictingCanonicalNamesFailClosed() throws Exception {
        for (String answer : List.of(
                payload(1, record(HOST, 5, "alias.example.com"), record("alias.example.com", 5, HOST), record(HOST, 1, "1.1.1.1")),
                payload(1, record(HOST, 5, "first.example.com"), record(HOST, 5, "second.example.com"), record("first.example.com", 1, "1.1.1.1")))) {
            reply(200, answer);
            assertDnsFailure(unknownHostResolver());
        }
    }

    @Test void moreThan32AddressRecordsAndBodiesLargerThan16KiBAreRejected() throws Exception {
        List<String> records = new ArrayList<>();
        for (int index = 1; index <= 33; index++) records.add(record(HOST, 1, "1.1.1." + index));
        reply(200, payload(1, records.toArray(String[]::new)));
        assertDnsFailure(unknownHostResolver());
        reply(200, " ".repeat(16_385));
        assertDnsFailure(unknownHostResolver());
    }

    @ParameterizedTest
    @ValueSource(ints = {301, 302, 403, 500})
    void redirectsAndHttpErrorsAreNeverAccepted(int status) throws Exception {
        reply(status, payload(1, record(HOST, 1, "1.1.1.1")));
        assertDnsFailure(unknownHostResolver());
        assertThat(requests).hasSize(2);
    }

    @Test void stalledBodyRequestsAreCancelledAndUseBoundedDeadline() throws Exception {
        CompletableFuture<HttpResponse<byte[]>> pending = mock(CompletableFuture.class);
        List<Long> waits = new ArrayList<>();
        when(pending.get(anyLong(), eq(TimeUnit.NANOSECONDS))).thenAnswer(invocation -> {
            waits.add(invocation.getArgument(0));
            throw new TimeoutException("private timeout details");
        });
        when(client.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(pending);
        assertDnsFailure(unknownHostResolver());
        assertThat(waits).hasSize(2).allSatisfy(wait -> assertThat(wait).isPositive().isLessThanOrEqualTo(TimeUnit.SECONDS.toNanos(4)));
        assertThat(waits.stream().mapToLong(Long::longValue).sum()).isLessThanOrEqualTo(TimeUnit.SECONDS.toNanos(8));
        verify(pending, org.mockito.Mockito.times(2)).cancel(true);
    }

    @Test void interruptionIsPreservedAndDoesNotStartSecondResolver() throws Exception {
        CompletableFuture<HttpResponse<byte[]>> pending = mock(CompletableFuture.class);
        when(pending.get(anyLong(), eq(TimeUnit.NANOSECONDS))).thenThrow(new InterruptedException("private details"));
        when(client.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(pending);
        try {
            assertDnsFailure(unknownHostResolver());
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            verify(client).sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
            verify(pending).cancel(true);
        } finally { Thread.interrupted(); }
    }

    @Test void streamingBodyLimitCancelsOverflowAndHandlesNormalCompletionAndFailure() {
        MailDnsResolver.LimitedBodySubscriber bounded = new MailDnsResolver.LimitedBodySubscriber();
        Flow.Subscription subscription = mock(Flow.Subscription.class);
        bounded.onSubscribe(subscription);
        bounded.onNext(List.of(ByteBuffer.wrap(new byte[16_384]), ByteBuffer.wrap(new byte[1])));
        bounded.onNext(List.of(ByteBuffer.wrap(new byte[1])));
        assertThat(bounded.getBody().toCompletableFuture()).isCompletedExceptionally();
        verify(subscription).cancel();

        MailDnsResolver.LimitedBodySubscriber small = new MailDnsResolver.LimitedBodySubscriber();
        small.onSubscribe(mock(Flow.Subscription.class));
        small.onNext(List.of(ByteBuffer.wrap(new byte[] {1, 2})));
        small.onComplete();
        assertThat(small.getBody().toCompletableFuture().join()).containsExactly((byte) 1, (byte) 2);

        MailDnsResolver.LimitedBodySubscriber failed = new MailDnsResolver.LimitedBodySubscriber();
        failed.onError(new IOException("private remote message"));
        assertThat(failed.getBody().toCompletableFuture()).isCompletedExceptionally();
        assertThatThrownBy(() -> failed.getBody().toCompletableFuture().join()).hasRootCauseMessage("DNS response failed");
    }

    @Test void invalidHostNeverCreatesRequestOrInvokesSystemDns() {
        MailDnsResolver resolver = resolver(host -> { throw new AssertionError("Invalid host reached system resolver"); });
        for (String host : new String[] {null, "", "localhost", "host.example.com/path", "host.example.com?token=x", "https://host.example.com", "-host.example.com"}) {
            assertThatThrownBy(() -> resolver.resolve(host)).isInstanceOf(DomainException.class);
        }
        verifyNoInteractions(client);
    }

    private MailDnsResolver resolver(MailDnsResolver.SystemResolver systemResolver) {
        return new MailDnsResolver(new ObjectMapper(), client, systemResolver);
    }

    private MailDnsResolver unknownHostResolver() {
        return resolver(host -> { throw new UnknownHostException("private system path"); });
    }

    private void assertDnsFailure(MailDnsResolver resolver) {
        assertThatThrownBy(() -> resolver.resolve(HOST)).isInstanceOfSatisfying(DomainException.class, failure -> {
            assertThat(failure.getCode()).isEqualTo("MAIL_DNS_FAILED");
            assertThat(failure.getMessage()).isEqualTo("Mailbox server DNS resolution failed");
            assertThat(failure.getCause()).isNull();
        });
    }

    @SuppressWarnings("unchecked")
    private void reply(int status, String... bodies) {
        requests.clear();
        when(client.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenAnswer(invocation -> {
            requests.add(invocation.getArgument(0));
            String body = bodies[Math.min(requests.size() - 1, bodies.length - 1)];
            return CompletableFuture.completedFuture(response(status, body));
        });
    }

    @SuppressWarnings("unchecked")
    private HttpResponse<byte[]> response(int status, String body) {
        HttpResponse<byte[]> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body.getBytes(StandardCharsets.UTF_8));
        return response;
    }

    private static InetAddress address(String literal) throws UnknownHostException { return InetAddress.getByName(literal); }

    private static String payload(int type, String... records) {
        return "{\"Status\":0,\"TC\":false,\"Question\":[{\"name\":\"" + HOST + "\",\"type\":" + type
                + "}],\"Answer\":[" + String.join(",", records) + "]}";
    }

    private static String record(String owner, int type, String data) {
        return "{\"name\":\"" + owner + "\",\"type\":" + type + ",\"data\":\""
                + data.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"}";
    }
}
