package com.codexquotaguard.backend.controlplane;

import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/** Mail-only byte accumulator. Never retains an oversized body or transport failure details. */
final class BoundedMailBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
    private final byte[] bytes;
    private final CompletableFuture<byte[]> body = new CompletableFuture<>();
    private Flow.Subscription subscription;
    private int size;

    BoundedMailBodySubscriber(int maximumBytes) {
        if (maximumBytes <= 0) throw new IllegalArgumentException("A positive mail response limit is required.");
        bytes = new byte[maximumBytes];
    }

    @Override
    public CompletionStage<byte[]> getBody() {
        return body;
    }

    @Override
    public void onSubscribe(Flow.Subscription incoming) {
        if (subscription != null || body.isDone()) {
            incoming.cancel();
            return;
        }
        subscription = incoming;
        incoming.request(1);
    }

    @Override
    public void onNext(List<ByteBuffer> buffers) {
        if (body.isDone()) return;
        for (ByteBuffer buffer : buffers) {
            int length = buffer.remaining();
            if (length > bytes.length - size) {
                fail();
                return;
            }
            buffer.get(bytes, size, length);
            size += length;
        }
        subscription.request(1);
    }

    @Override
    public void onError(Throwable ignored) {
        fail();
    }

    @Override
    public void onComplete() {
        if (body.isDone()) return;
        byte[] result = Arrays.copyOf(bytes, size);
        Arrays.fill(bytes, (byte) 0);
        size = 0;
        body.complete(result);
    }

    private void fail() {
        if (body.isDone()) return;
        Arrays.fill(bytes, (byte) 0);
        size = 0;
        body.completeExceptionally(ControlPlaneException.unavailable());
        if (subscription != null) subscription.cancel();
    }
}
