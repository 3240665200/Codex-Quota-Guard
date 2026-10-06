package com.pingche.web.codex;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class OfficialCodexLoginGatewayTest {

    @TempDir Path temporary;
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void usesIsolatedOwnerOnlyStorageAndOfficialLoginMethodsThenDeletesPlaintext() throws Exception {
        AtomicReference<StubProcess> child = new AtomicReference<>();
        AtomicReference<Path> home = new AtomicReference<>();
        var gateway = new OfficialCodexLoginGateway(mapper, "codex", temporary.toString(), builder -> {
            assertThat(builder.command()).containsExactly("codex", "app-server", "--listen", "stdio://",
                    "-c", "cli_auth_credentials_store=\"file\"", "-c", "features.plugins=false");
            assertThat(builder.environment()).doesNotContainKeys("JWT_SECRET", "DATABASE_PASSWORD", "OPENAI_API_KEY");
            Path directory = Path.of(builder.environment().get("CODEX_HOME"));
            home.set(directory);
            assertThat(builder.directory().toPath()).isEqualTo(directory);
            assertThat(directory).startsWith(temporary);
            assertPrivate(directory);
            StubProcess process = new StubProcess(directory, "https://auth.openai.com/codex/device", true);
            child.set(process);
            return process;
        });
        CodexLoginGateway.Login login = gateway.open();
        assertThat(login.userCode()).isEqualTo("ABCD-1234");
        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> assertThat(login.poll()).isPresent());
        var account = login.poll().orElseThrow();
        assertThat(account.email()).isEqualTo("test@example.com");
        assertThat(account.credential().path("tokens").path("access_token").asString()).isEqualTo("fake-token");
        assertThat(account.toString()).doesNotContain("fake-token");
        login.close();
        login.close();
        assertThat(home.get()).doesNotExist();
        assertThat(child.get().isAlive()).isFalse();
        assertThat(child.get().methods).containsOnly("initialize", "initialized", "account/login/start", "account/read");
    }

    @Test
    void pendingLoginCanBeClosedWithoutAnyCredentialBeingPersisted() throws Exception {
        var gateway = new OfficialCodexLoginGateway(mapper, "codex", temporary.toString(), builder ->
                new StubProcess(builder.directory().toPath(), "https://auth.openai.com/codex/device", false));
        try (CodexLoginGateway.Login login = gateway.open()) {
            assertThat(login.poll()).isEmpty();
        }
        try (var directories = Files.list(temporary.resolve("codex-quota-guard-authorizations"))) {
            assertThat(directories.count()).isZero();
        }
    }

    @Test
    void refusesUntrustedVerificationLinksAndCleansUpFailedStarts() throws Exception {
        var gateway = new OfficialCodexLoginGateway(mapper, "codex", temporary.toString(), builder ->
                new StubProcess(builder.directory().toPath(), "https://attacker.invalid/device", true));
        assertThatThrownBy(gateway::open).isInstanceOf(IllegalStateException.class)
                .hasMessage("Unable to start official Codex authorization");
        try (var directories = Files.list(temporary.resolve("codex-quota-guard-authorizations"))) {
            assertThat(directories.count()).isZero();
        }
    }

    @Test
    void cleanupCannotRemoveTheRootOrAnUnrelatedDirectory() throws Exception {
        Path unrelated = Files.createDirectory(temporary.resolve("unrelated"));
        assertThatThrownBy(() -> CodexLoginDirectory.remove(temporary, temporary))
                .isInstanceOf(IOException.class);
        assertThatThrownBy(() -> CodexLoginDirectory.remove(temporary, unrelated))
                .isInstanceOf(IOException.class);
        assertThat(unrelated).exists();
    }

    @Test
    void protocolReaderBoundsInputAndDoesNotEchoMalformedData() throws Exception {
        assertThat(CodexLoginRpc.readBoundedLine(new BufferedReader(new StringReader("line\r\n"))))
                .isEqualTo("line");
        assertThatThrownBy(() -> CodexLoginRpc.readBoundedLine(new BufferedReader(
                new StringReader("x".repeat(1024 * 1024 + 1)))))
                .isInstanceOf(IOException.class).hasMessage("Protocol line too large");
    }

    @Test
    void browserLoginUsesOfficialPkceUrlAndTheSameEncryptedImportContract() throws Exception {
        String url = CodexLoginUrlTest.VALID_URL;
        AtomicReference<StubProcess> process = new AtomicReference<>();
        var gateway = new OfficialCodexLoginGateway(mapper, "codex", temporary.toString(), builder -> {
            var child = new StubProcess(builder.directory().toPath(), url, true);
            process.set(child);
            return child;
        }, () -> { });
        try (var login = gateway.open(CodexLoginMode.BROWSER)) {
            assertThat(login.authUrl()).isEqualTo(url);
            assertThat(login.userCode()).isNull();
            assertThat(login.verificationUrl()).isNull();
            assertThat(process.get().loginParams.path("type").asString()).isEqualTo("chatgpt");
            assertThat(process.get().loginParams.path("useHostedLoginSuccessPage").asBoolean()).isTrue();
            assertThat(process.get().loginParams.path("appBrand").asString()).isEqualTo("codex");
            await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> assertThat(login.poll()).isPresent());
            assertThat(login.poll().orElseThrow().email()).isEqualTo("test@example.com");
        }
        assertThat(process.get().isAlive()).isFalse();
        assertThat(process.get().directory).doesNotExist();
    }

    @Test
    void refusesBrowserRedirectsWithoutPkceAndCleansUpTheProcess() throws Exception {
        var gateway = new OfficialCodexLoginGateway(mapper, "codex", temporary.toString(), builder ->
                new StubProcess(builder.directory().toPath(), "https://auth.openai.com/oauth/authorize", false),
                () -> { });
        assertThatThrownBy(() -> gateway.open(CodexLoginMode.BROWSER))
                .isInstanceOf(IllegalStateException.class).hasMessage("Unable to start official Codex authorization");
        try (var directories = Files.list(temporary.resolve("codex-quota-guard-authorizations"))) {
            assertThat(directories.count()).isZero();
        }
    }

    private void assertPrivate(Path directory) throws IOException {
        if (Files.getFileAttributeView(directory, PosixFileAttributeView.class) != null) {
            assertThat(Files.getPosixFilePermissions(directory))
                    .isEqualTo(PosixFilePermissions.fromString("rwx------"));
        } else {
            var acl = Files.getFileAttributeView(directory, AclFileAttributeView.class).getAcl();
            assertThat(acl).hasSize(1);
            assertThat(acl.get(0).type()).isEqualTo(AclEntryType.ALLOW);
            assertThat(acl.get(0).principal()).isEqualTo(Files.getOwner(directory));
        }
    }

    private final class StubProcess extends Process {
        private final PipedInputStream output = new PipedInputStream(64 * 1024);
        private final PipedOutputStream emit = new PipedOutputStream(output);
        private final List<String> methods = new ArrayList<>();
        private final Path directory;
        private final String url;
        private final boolean complete;
        private JsonNode loginParams;
        private volatile boolean alive = true;
        private final ByteArrayOutputStream input = new ByteArrayOutputStream() {
            @Override
            public synchronized void flush() throws IOException {
                if (size() == 0) return;
                String message = toString(StandardCharsets.UTF_8);
                reset();
                respond(mapper.readTree(message));
            }
        };

        StubProcess(Path directory, String url, boolean complete) throws IOException {
            this.directory = directory;
            this.url = url;
            this.complete = complete;
        }

        private void respond(JsonNode request) throws IOException {
            String method = request.path("method").asString();
            methods.add(method);
            if (method.equals("initialized")) return;
            if (method.equals("account/login/start")) loginParams = request.path("params");
            Map<String, ?> result = switch (method) {
                case "initialize" -> Map.of();
                case "account/login/start" -> "chatgpt".equals(loginParams.path("type").asString())
                        ? Map.of("type", "chatgpt", "loginId", "login-id", "authUrl", url)
                        : Map.of("type", "chatgptDeviceCode", "loginId", "login-id",
                                "verificationUrl", url, "userCode", "ABCD-1234");
                case "account/read" -> Map.of("account", Map.of("type", "chatgpt", "email", "test@example.com"));
                default -> throw new AssertionError("Unexpected RPC method");
            };
            emit(Map.of("id", request.path("id").asLong(), "result", result));
            if (complete && method.equals("account/login/start")) {
                Files.writeString(directory.resolve("auth.json"), """
                        {"tokens":{"access_token":"fake-token","refresh_token":"fake-refresh"}}
                        """);
                emit(Map.of("method", "account/login/completed",
                        "params", Map.of("loginId", "login-id", "success", true)));
            }
        }

        private void emit(Map<String, ?> value) throws IOException {
            emit.write((mapper.writeValueAsString(value) + "\n").getBytes(StandardCharsets.UTF_8));
            emit.flush();
        }

        @Override public OutputStream getOutputStream() { return input; }
        @Override public InputStream getInputStream() { return output; }
        @Override public InputStream getErrorStream() { return InputStream.nullInputStream(); }
        @Override public int waitFor() { return 0; }
        @Override public boolean waitFor(long timeout, TimeUnit unit) { return !alive; }
        @Override public int exitValue() { if (alive) throw new IllegalThreadStateException(); return 0; }
        @Override public boolean isAlive() { return alive; }
        @Override public void destroy() {
            alive = false;
            try { emit.close(); } catch (IOException ignored) { }
        }
    }
}
