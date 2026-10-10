package com.codexquotaguard.backend.codex;

import com.codexquotaguard.backend.controlplane.OfficialCodexRateLimitWindow;
import com.codexquotaguard.backend.controlplane.OfficialCodexRateLimits;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Reads non-secret quota metadata from the user's own official Codex login. */
@Service
public final class LocalOfficialCodexRateLimitService {

    private static final Duration TIMEOUT = Duration.ofSeconds(20);
    private final ObjectMapper mapper;
    private final Clock clock;
    private final String executable;
    private final Path officialHome;

    public LocalOfficialCodexRateLimitService(ObjectMapper mapper, Clock clock,
            @Value("${quota-guard.codex.executable:codex}") String executable,
            @Value("${quota-guard.codex.official-home:${user.home}/.codex}") String officialHome) {
        this.mapper = mapper;
        this.clock = clock;
        this.executable = resolveExecutable(executable);
        this.officialHome = Path.of(officialHome).toAbsolutePath().normalize();
    }

    public OfficialCodexRateLimits read() {
        Process process = null;
        try {
            ProcessBuilder builder = new ProcessBuilder(executable, "app-server", "--listen", "stdio://",
                    "-c", "cli_auth_credentials_store=\"file\"", "-c", "features.plugins=false");
            builder.redirectError(ProcessBuilder.Redirect.DISCARD);
            builder.environment().remove("QUOTA_GUARD_API_TOKEN");
            builder.environment().remove("QUOTA_GUARD_CONTROL_PLANE_URL");
            builder.environment().put("CODEX_HOME", officialHome.toString());
            process = builder.start();
            try (Rpc rpc = new Rpc(process, mapper)) {
                rpc.call("initialize", Map.of("clientInfo", Map.of(
                        "name", "codex_quota_guard", "version", "0.2.18")));
                rpc.notifyInitialized();
                JsonNode account = rpc.call("account/read", Map.of("refreshToken", false));
                if (!"chatgpt".equals(account.path("account").path("type").asString(""))) {
                    throw new IllegalStateException();
                }
                JsonNode limits = rpc.call("account/rateLimits/read", Map.of());
                return parse(account, limits, clock.instant());
            }
        } catch (Exception exception) {
            // Neither raw protocol output nor local paths may enter UI errors.
            throw new OfficialCodexRateLimitsUnavailableException();
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    static OfficialCodexRateLimits parse(JsonNode account, JsonNode limits, Instant fetchedAt) {
        String plan = account.path("account").path("planType").asString("");
        List<OfficialCodexRateLimitWindow> windows = new ArrayList<>();
        JsonNode byId = limits.path("rateLimitsByLimitId");
        if (byId.isObject() && !byId.properties().isEmpty()) {
            byId.properties().forEach(entry -> addWindows(windows, entry.getKey(), entry.getValue()));
        } else {
            JsonNode legacy = limits.path("rateLimits");
            if (legacy.isObject()) {
                addWindows(windows, legacy.path("limitId").asString("codex"), legacy);
            }
        }
        windows.sort(Comparator.comparing(OfficialCodexRateLimitWindow::limitId)
                .thenComparingLong(OfficialCodexRateLimitWindow::windowDurationMins));
        return new OfficialCodexRateLimits(plan.isBlank() ? null : plan, windows, fetchedAt.toString());
    }

    private static void addWindows(List<OfficialCodexRateLimitWindow> windows, String id, JsonNode bucket) {
        addWindow(windows, id, "primary", bucket.path("primary"));
        addWindow(windows, id, "secondary", bucket.path("secondary"));
    }

    private static void addWindow(List<OfficialCodexRateLimitWindow> windows,
            String id, String slot, JsonNode node) {
        JsonNode duration = node.path("windowDurationMins");
        JsonNode used = node.path("usedPercent");
        if (!duration.isIntegralNumber() || !used.isNumber()) return;
        long minutes = duration.asLong();
        double percent = used.asDouble();
        if (minutes <= 0 || minutes > 525_600 || !Double.isFinite(percent)
                || percent < 0 || percent > 100) return;
        JsonNode reset = node.path("resetsAt");
        String resetsAt = reset.isIntegralNumber()
                ? Instant.ofEpochSecond(reset.asLong()).toString() : null;
        windows.add(new OfficialCodexRateLimitWindow(id, slot, minutes, percent, resetsAt));
    }

    static String resolveExecutable(String configured) {
        if (configured != null && !configured.isBlank()
                && !"codex".equalsIgnoreCase(configured)
                && !"codex.exe".equalsIgnoreCase(configured)) {
            return configured;
        }
        if (System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("windows")) {
            String path = System.getenv("PATH");
            if (path != null) {
                for (String directory : path.split(";")) {
                    if (directory.isBlank()) continue;
                    try {
                        Path candidate = Path.of(directory, "codex.exe");
                        if (Files.isRegularFile(candidate)) return candidate.toAbsolutePath().normalize().toString();
                    } catch (RuntimeException ignored) {
                        // Ignore malformed PATH entries and continue to the next directory.
                    }
                }
            }
        }
        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData != null) {
            String bundled = findBundledExecutable(Path.of(localAppData, "OpenAI", "Codex", "bin"));
            if (bundled != null) return bundled;
        }
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("windows")
                ? "codex.exe" : "codex";
    }

    static String findBundledExecutable(Path binDirectory) {
        Path direct = binDirectory.resolve("codex.exe");
        if (Files.isRegularFile(direct, LinkOption.NOFOLLOW_LINKS)) {
            return direct.toAbsolutePath().normalize().toString();
        }
        Path newest = null;
        FileTime newestTime = null;
        try (var children = Files.list(binDirectory)) {
            for (Path directory : children.toList()) {
                if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) continue;
                Path candidate = directory.resolve("codex.exe");
                if (!Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) continue;
                FileTime modified = Files.getLastModifiedTime(candidate, LinkOption.NOFOLLOW_LINKS);
                if (newestTime == null || modified.compareTo(newestTime) > 0) {
                    newest = candidate;
                    newestTime = modified;
                }
            }
        } catch (IOException | SecurityException ignored) {
            // A missing optional desktop installation falls back to the CLI on PATH.
        }
        return newest == null ? null : newest.toAbsolutePath().normalize().toString();
    }

    private static final class Rpc implements AutoCloseable {
        private static final int MAX_LINE_LENGTH = 1024 * 1024;
        private final Process process;
        private final ObjectMapper mapper;
        private final BufferedWriter stdin;
        private final AtomicLong ids = new AtomicLong();
        private final Map<Long, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();

        Rpc(Process process, ObjectMapper mapper) {
            this.process = process;
            this.mapper = mapper;
            stdin = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
            Thread reader = new Thread(this::readOutput, "local-codex-rate-limit-rpc");
            reader.setDaemon(true);
            reader.start();
        }

        JsonNode call(String method, Map<String, ?> params) throws Exception {
            long id = ids.incrementAndGet();
            CompletableFuture<JsonNode> response = new CompletableFuture<>();
            pending.put(id, response);
            try {
                send(Map.of("id", id, "method", method, "params", params));
                JsonNode message = response.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
                if (message.has("error") || !message.has("result")) throw new IllegalStateException();
                return message.path("result");
            } finally {
                pending.remove(id);
            }
        }

        void notifyInitialized() throws IOException { send(Map.of("method", "initialized")); }

        private synchronized void send(Map<String, ?> message) throws IOException {
            stdin.write(mapper.writeValueAsString(message));
            stdin.newLine();
            stdin.flush();
        }

        private void readOutput() {
            try (BufferedReader stdout = new BufferedReader(new InputStreamReader(
                    process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = readBoundedLine(stdout)) != null) {
                    JsonNode message = mapper.readTree(line);
                    if (message.has("id")) {
                        CompletableFuture<JsonNode> waiting = pending.get(message.path("id").asLong(-1));
                        if (waiting != null) waiting.complete(message);
                    }
                }
            } catch (Exception ignored) {
                // Raw official process output may contain secrets; never log it.
            } finally {
                pending.values().forEach(waiting -> waiting.completeExceptionally(new IllegalStateException()));
            }
        }

        private static String readBoundedLine(BufferedReader reader) throws IOException {
            StringBuilder line = new StringBuilder();
            int character;
            while ((character = reader.read()) != -1) {
                if (character == '\n') return line.toString();
                if (line.length() >= MAX_LINE_LENGTH) throw new IOException();
                if (character != '\r') line.append((char) character);
            }
            return line.isEmpty() ? null : line.toString();
        }

        @Override public void close() {
            process.destroy();
            try {
                if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly();
                stdin.close();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            } catch (IOException ignored) { }
        }
    }
}
