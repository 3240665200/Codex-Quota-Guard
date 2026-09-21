package com.codexquotaguard.backend.codex;

import com.codexquotaguard.backend.account.CodexGateway;
import com.codexquotaguard.backend.quota.AccountState;
import com.codexquotaguard.backend.quota.RawRateLimitBucket;
import com.codexquotaguard.backend.quota.RawRateLimitWindow;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/** Synchronous JSON-RPC client for a locally started {@code codex app-server}. */
public final class CodexAppServerClient implements CodexGateway, AutoCloseable {

    private static final int MAX_IGNORED_MESSAGES = 1_024;

    private final BufferedReader reader;
    private final BufferedWriter writer;
    private final JsonMapper mapper;
    private int nextRequestId = 1;
    private boolean initialized;

    public CodexAppServerClient(BufferedReader reader, BufferedWriter writer) {
        if (reader == null || writer == null) {
            throw new IllegalArgumentException("Codex app-server streams are required.");
        }
        this.reader = reader;
        this.writer = writer;
        this.mapper = JsonMapper.builder().build();
    }

    public void initialize(CodexClientInfo clientInfo) {
        var params = mapper.createObjectNode();
        var info = params.putObject("clientInfo");
        info.put("name", clientInfo.name());
        info.put("title", clientInfo.title());
        info.put("version", clientInfo.version());
        call("initialize", params);

        var notification = mapper.createObjectNode();
        notification.put("method", "initialized");
        notification.putObject("params");
        write(notification);
        initialized = true;
    }

    public AccountState readAccount() {
        requireInitialized();
        var params = mapper.createObjectNode();
        params.put("refreshToken", false);
        JsonNode account = call("account/read", params).path("account");
        if (account.isMissingNode() || account.isNull()) {
            return new AccountState(false, "");
        }
        return new AccountState(true, text(account, "planType"));
    }

    public Map<String, RawRateLimitBucket> readRateLimits() {
        requireInitialized();
        JsonNode bucketsNode = call("account/rateLimits/read", mapper.createObjectNode()).path("rateLimitsByLimitId");
        if (!bucketsNode.isObject()) {
            return Map.of();
        }

        Map<String, RawRateLimitBucket> buckets = new LinkedHashMap<>();
        for (var field : bucketsNode.properties()) {
            JsonNode bucket = field.getValue();
            buckets.put(field.getKey(), new RawRateLimitBucket(
                    text(bucket, "limitId"),
                    text(bucket, "limitName"),
                    window(bucket.path("primary")),
                    window(bucket.path("secondary")),
                    text(bucket, "planType"),
                    bucket.path("spendControlReached").asBoolean(false)
            ));
        }
        return Map.copyOf(buckets);
    }

    public DeviceLogin startDeviceLogin() {
        requireInitialized();
        var params = mapper.createObjectNode();
        params.put("type", "chatgptDeviceCode");
        JsonNode result = call("account/login/start", params);
        return new DeviceLogin(
                text(result, "type"),
                text(result, "loginId"),
                text(result, "verificationUrl"),
                text(result, "userCode")
        );
    }

    public void logout() {
        requireInitialized();
        call("account/logout", mapper.createObjectNode());
    }

    @Override
    public void close() {
        try {
            writer.close();
        } catch (IOException ignored) {
            // Best-effort shutdown of a local child-process stream.
        }
        try {
            reader.close();
        } catch (IOException ignored) {
            // Best-effort shutdown of a local child-process stream.
        }
    }

    private JsonNode call(String method, ObjectNode params) {
        int requestId = nextRequestId++;
        var request = mapper.createObjectNode();
        request.put("method", method);
        request.put("id", requestId);
        request.set("params", params);
        write(request);

        for (int ignoredMessages = 0; ignoredMessages < MAX_IGNORED_MESSAGES; ignoredMessages++) {
            JsonNode response = read();
            if (!response.has("id") || response.path("id").asInt(Integer.MIN_VALUE) != requestId) {
                continue;
            }
            if (response.has("error") && !response.path("error").isNull()) {
                throw new CodexAppServerException("Codex app-server rejected " + method + ".");
            }
            return response.path("result");
        }
        throw new CodexAppServerException("Codex app-server did not return a matching response.");
    }

    private void write(ObjectNode message) {
        try {
            writer.write(mapper.writeValueAsString(message));
            writer.newLine();
            writer.flush();
        } catch (IOException exception) {
            throw new CodexAppServerException("Write to Codex app-server failed.", exception);
        }
    }

    private JsonNode read() {
        try {
            String line = reader.readLine();
            if (line == null) {
                throw new CodexAppServerException("Codex app-server stopped before responding.");
            }
            return mapper.readTree(line);
        } catch (IOException exception) {
            throw new CodexAppServerException("Read from Codex app-server failed.", exception);
        }
    }

    private void requireInitialized() {
        if (!initialized) {
            throw new CodexAppServerException("Codex app-server client is not initialized.");
        }
    }

    private static RawRateLimitWindow window(JsonNode node) {
        if (!node.isObject()) {
            return null;
        }
        return new RawRateLimitWindow(
                numeric(node, "usedPercent"),
                wholeNumber(node, "windowDurationMins"),
                wholeNumber(node, "resetsAt")
        );
    }

    private static Double numeric(JsonNode node, String name) {
        JsonNode value = node.path(name);
        return value.isNumber() ? value.asDouble() : null;
    }

    private static Long wholeNumber(JsonNode node, String name) {
        JsonNode value = node.path(name);
        return value.isIntegralNumber() ? value.asLong() : null;
    }

    private static String text(JsonNode node, String name) {
        JsonNode value = node.path(name);
        return value.isTextual() ? value.asString() : "";
    }
}
