package com.pingche.web.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.pingche.web.auth.DomainException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import static org.mockito.Mockito.verify;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

class MailOAuthClientTest {
    @Test void refreshHandlesRotatedTokenFromFixedEndpointWithoutNetwork() throws Exception {
        HttpClient client = mock(HttpClient.class);
        HttpResponse<byte[]> response = response(200, "{\"access_token\":\"synthetic-access\",\"refresh_token\":\"synthetic-rotated\"}");
        ArgumentCaptor<HttpRequest> request = ArgumentCaptor.forClass(HttpRequest.class);
        when(client.sendAsync(request.capture(), any(HttpResponse.BodyHandler.class))).thenReturn(CompletableFuture.completedFuture(response));
        MailOAuthClient.Token token = new MailOAuthClient(new ObjectMapper(), client)
                .refresh(MailboxConfig.AuthMode.MICROSOFT_OAUTH2, "client-id", "client-secret", "refresh", "consumers");
        assertThat(token.accessToken()).isEqualTo("synthetic-access"); assertThat(token.refreshToken()).isEqualTo("synthetic-rotated");
        assertThat(request.getValue().uri().toString()).isEqualTo("https://login.microsoftonline.com/consumers/oauth2/v2.0/token");
        assertThat(token.toString()).doesNotContain("synthetic-access", "synthetic-rotated");
    }

    @Test void providerErrorMalformedTokenAndLargePayloadAreSafeFailures() throws Exception {
        for (String payload : new String[] {"{\"error\":\"private-secret\"}", "{\"access_token\":123}", "x".repeat(65_537)}) {
            HttpClient client = mock(HttpClient.class);
            HttpResponse<byte[]> response = response(200, payload);
            when(client.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(CompletableFuture.completedFuture(response));
            assertThatThrownBy(() -> new MailOAuthClient(new ObjectMapper(), client)
                    .refresh(MailboxConfig.AuthMode.GOOGLE_OAUTH2, "client", "secret", "refresh", null))
                    .isInstanceOf(DomainException.class).hasMessage("Mailbox OAuth authorization failed; renew the saved authorization");
        }
        HttpClient denied = mock(HttpClient.class);
        HttpResponse<byte[]> response = response(401, "private-secret");
        when(denied.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(CompletableFuture.completedFuture(response));
        assertThatThrownBy(() -> new MailOAuthClient(new ObjectMapper(), denied)
                .refresh(MailboxConfig.AuthMode.GOOGLE_OAUTH2, "client", null, "refresh", null)).isInstanceOf(DomainException.class);
    }

    @Test void interruptedRequestPreservesThreadInterruptionAndRejectsCustomAuthority() throws Exception {
        HttpClient client = mock(HttpClient.class);
        CompletableFuture<HttpResponse<byte[]>> pending = mock(CompletableFuture.class);
        when(pending.get(10, TimeUnit.SECONDS)).thenThrow(new InterruptedException());
        when(client.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(pending);
        try {
            assertThatThrownBy(() -> new MailOAuthClient(new ObjectMapper(), client)
                    .refresh(MailboxConfig.AuthMode.GOOGLE_OAUTH2, "client", "secret", "refresh", null)).isInstanceOf(DomainException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            verify(pending).cancel(true);
        } finally { Thread.interrupted(); }
    }

    @Test void stalledBodyFutureIsCancelledByWholeResponseDeadlineAndSubscriberIsSizeBounded() throws Exception {
        HttpClient client = mock(HttpClient.class);
        CompletableFuture<HttpResponse<byte[]>> pending = mock(CompletableFuture.class);
        when(pending.get(10, TimeUnit.SECONDS)).thenThrow(new TimeoutException());
        when(client.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(pending);
        assertThatThrownBy(() -> new MailOAuthClient(new ObjectMapper(), client)
                .refresh(MailboxConfig.AuthMode.GOOGLE_OAUTH2, "client", "secret", "refresh", null)).isInstanceOf(DomainException.class);
        verify(pending).cancel(true);
        MailOAuthClient.LimitedBodySubscriber subscriber = new MailOAuthClient.LimitedBodySubscriber();
        Flow.Subscription subscription = mock(Flow.Subscription.class); subscriber.onSubscribe(subscription);
        subscriber.onNext(List.of(ByteBuffer.wrap(new byte[65_537])));
        assertThat(subscriber.getBody().toCompletableFuture()).isCompletedExceptionally(); verify(subscription).cancel();
        MailOAuthClient.LimitedBodySubscriber small = new MailOAuthClient.LimitedBodySubscriber();
        small.onSubscribe(mock(Flow.Subscription.class)); small.onNext(List.of(ByteBuffer.wrap(new byte[] {1, 2}))); small.onComplete();
        assertThat(small.getBody().toCompletableFuture().join()).containsExactly((byte) 1, (byte) 2);
    }

    @SuppressWarnings("unchecked") private HttpResponse<byte[]> response(int status, String body) {
        HttpResponse<byte[]> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body.getBytes(StandardCharsets.UTF_8));
        return response;
    }
}
