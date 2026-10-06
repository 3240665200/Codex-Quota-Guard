package com.pingche.web.codex;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Bounded stdio transport for the official login methods; no arbitrary RPC is exposed over HTTP. */
final class CodexLoginRpc implements AutoCloseable {

    private static final int MAX_LINE_LENGTH = 1024 * 1024;
    private final Process process;
    private final ObjectMapper mapper;
    private final BufferedWriter input;
    private final Map<Long, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();
    private volatile JsonNode loginCompleted;
    private volatile boolean closed;

    CodexLoginRpc(Process process, ObjectMapper mapper) {
        this.process = process;
        this.mapper = mapper;
        input = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        Thread reader = new Thread(this::readOutput, "codex-authorization-output");
        reader.setDaemon(true);
        reader.start();
    }

    JsonNode call(String method, Map<String, ?> params, Duration timeout) {
        long id = sequence.incrementAndGet();
        CompletableFuture<JsonNode> response = new CompletableFuture<>();
        pending.put(id, response);
        try {
            if (closed || !process.isAlive()) throw failure();
            send(Map.of("id", id, "method", method, "params", params));
            JsonNode message = response.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (message.has("error") || !message.has("result")) throw failure();
            return message.get("result");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw failure();
        } catch (Exception exception) {
            // Upstream messages can contain credentials: never propagate their text or cause.
            throw failure();
        } finally {
            pending.remove(id);
        }
    }

    void initialized() { send(Map.of("method", "initialized")); }

    boolean completed(String loginId) {
        JsonNode result = loginCompleted;
        if (result == null) {
            if (closed || !process.isAlive()) throw failure();
            return false;
        }
        if (!loginId.equals(result.path("loginId").asString())
                || !result.path("success").asBoolean(false)) throw failure();
        return true;
    }

    private synchronized void send(Map<String, ?> value) {
        try {
            input.write(mapper.writeValueAsString(value));
            input.newLine();
            input.flush();
        } catch (Exception exception) {
            throw failure();
        }
    }

    private void readOutput() {
        try (BufferedReader output = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            while (!closed) {
                String line = readBoundedLine(output);
                if (line == null) break;
                JsonNode message = mapper.readTree(line);
                if (message.has("id")) {
                    CompletableFuture<JsonNode> response = pending.get(message.path("id").asLong(-1));
                    if (response != null) response.complete(message);
                } else if ("account/login/completed".equals(message.path("method").asString())) {
                    loginCompleted = message.path("params");
                }
            }
        } catch (Exception ignored) {
            // Do not log raw protocol output or parse exceptions.
        } finally {
            closed = true;
            pending.values().forEach(future -> future.completeExceptionally(failure()));
            process.destroy();
        }
    }

    static String readBoundedLine(BufferedReader reader) throws IOException {
        StringBuilder line = new StringBuilder();
        int character;
        while ((character = reader.read()) != -1) {
            if (character == '\n') return line.toString();
            if (line.length() >= MAX_LINE_LENGTH) throw new IOException("Protocol line too large");
            if (character != '\r') line.append((char) character);
        }
        return line.isEmpty() ? null : line.toString();
    }

    private static IllegalStateException failure() {
        return new IllegalStateException("Codex authorization is unavailable or was declined");
    }

    @Override
    public void close() {
        closed = true;
        pending.values().forEach(future -> future.completeExceptionally(failure()));
        process.destroy();
        try {
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                if (!process.waitFor(2, TimeUnit.SECONDS)) throw failure();
            }
            input.close();
        } catch (InterruptedException exception) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw failure();
        } catch (IOException ignored) {
            // The child may already have closed its pipe.
        }
    }
}
