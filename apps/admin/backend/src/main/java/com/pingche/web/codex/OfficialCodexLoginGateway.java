package com.pingche.web.codex;

import com.pingche.web.auth.DomainException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class OfficialCodexLoginGateway implements CodexLoginGateway {

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);
    private static final Set<String> ENVIRONMENT_ALLOWLIST = Set.of(
            "PATH", "SYSTEMROOT", "WINDIR", "PATHEXT", "COMSPEC", "USERPROFILE", "HOME",
            "LOCALAPPDATA", "APPDATA", "TEMP", "TMP", "LANG", "LC_ALL",
            "HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY", "NO_PROXY", "SSL_CERT_FILE", "SSL_CERT_DIR");
    private final ObjectMapper mapper;
    private final String command;
    private final Path root;
    private final ProcessStarter processStarter;
    private final Runnable browserPortCheck;

    @Autowired
    public OfficialCodexLoginGateway(ObjectMapper mapper,
            @Value("${app.codex-login.command:codex}") String command,
            @Value("${app.codex-login.work-dir:${java.io.tmpdir}}") String root) {
        this(mapper, command, root, ProcessBuilder::start, OfficialCodexLoginGateway::checkBrowserPort);
    }

    OfficialCodexLoginGateway(ObjectMapper mapper, String command, String root, ProcessStarter processStarter) {
        this(mapper, command, root, processStarter, OfficialCodexLoginGateway::checkBrowserPort);
    }

    OfficialCodexLoginGateway(ObjectMapper mapper, String command, String root, ProcessStarter processStarter,
            Runnable browserPortCheck) {
        this.mapper = mapper;
        this.command = command;
        this.root = Path.of(root).toAbsolutePath().normalize().resolve("codex-quota-guard-authorizations");
        this.processStarter = processStarter;
        this.browserPortCheck = browserPortCheck;
    }

    @FunctionalInterface
    interface ProcessStarter { Process start(ProcessBuilder builder) throws IOException; }

    @Override
    public Login open(CodexLoginMode mode) {
        // Do not let the official CLI take over/cancel an unrelated login listener.
        if (mode == CodexLoginMode.BROWSER) browserPortCheck.run();
        Path directory = null;
        CodexLoginRpc rpc = null;
        try {
            directory = CodexLoginDirectory.create(root);
            ProcessBuilder builder = new ProcessBuilder(command, "app-server", "--listen", "stdio://",
                    "-c", "cli_auth_credentials_store=\"file\"", "-c", "features.plugins=false");
            builder.directory(directory.toFile()).redirectError(ProcessBuilder.Redirect.DISCARD);
            builder.environment().keySet().removeIf(key -> !ENVIRONMENT_ALLOWLIST.contains(key.toUpperCase(Locale.ROOT)));
            builder.environment().put("CODEX_HOME", directory.toString());
            rpc = new CodexLoginRpc(processStarter.start(builder), mapper);
            rpc.call("initialize", Map.of("clientInfo", Map.of(
                    "name", "codex_quota_guard", "version", "0.1.0")), REQUEST_TIMEOUT);
            rpc.initialized();
            Map<String, ?> params = mode == CodexLoginMode.BROWSER
                    ? Map.of("type", "chatgpt", "useHostedLoginSuccessPage", true, "appBrand", "codex")
                    : Map.of("type", "chatgptDeviceCode");
            JsonNode started = rpc.call("account/login/start", params, REQUEST_TIMEOUT);
            String loginId = started.path("loginId").asString("");
            String url = started.path("verificationUrl").asString("");
            String code = started.path("userCode").asString("");
            String authUrl = started.path("authUrl").asString("");
            boolean valid = mode == CodexLoginMode.BROWSER
                    ? "chatgpt".equals(started.path("type").asString()) && CodexLoginUrl.isBrowserLogin(authUrl)
                    : "chatgptDeviceCode".equals(started.path("type").asString())
                            && "https://auth.openai.com/codex/device".equals(url)
                            && code.matches("[A-Za-z0-9-]{4,32}");
            if (loginId.isBlank() || !valid) {
                throw new IllegalStateException("Unsupported Codex authorization response");
            }
            return new OfficialLogin(rpc, directory, loginId,
                    mode == CodexLoginMode.DEVICE_CODE ? url : null,
                    mode == CodexLoginMode.DEVICE_CODE ? code : null,
                    mode == CodexLoginMode.BROWSER ? authUrl : null);
        } catch (Exception exception) {
            try {
                if (rpc != null) rpc.close();
            } finally {
                cleanup(directory);
            }
            throw new IllegalStateException("Unable to start official Codex authorization");
        }
    }

    private static void checkBrowserPort() {
        try (ServerSocket socket = new ServerSocket()) {
            socket.setReuseAddress(false);
            socket.bind(new InetSocketAddress("127.0.0.1", 1455));
        } catch (IOException exception) {
            throw new DomainException("CODEX_BROWSER_LOGIN_BUSY",
                    "本机 1455 登录回调端口被占用，请完成已有登录或改用设备码登录。", HttpStatus.CONFLICT);
        }
    }

    private void cleanup(Path directory) {
        if (directory == null) return;
        try {
            CodexLoginDirectory.remove(root, directory);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to clean the private Codex authorization directory");
        }
    }

    private final class OfficialLogin implements Login {
        private final CodexLoginRpc rpc;
        private final Path directory;
        private final String loginId;
        private final String url;
        private final String code;
        private final String authUrl;

        private OfficialLogin(CodexLoginRpc rpc, Path directory, String loginId, String url, String code,
                String authUrl) {
            this.rpc = rpc;
            this.directory = directory;
            this.loginId = loginId;
            this.url = url;
            this.code = code;
            this.authUrl = authUrl;
        }

        @Override
        public String verificationUrl() { return url; }

        @Override
        public String userCode() { return code; }

        @Override
        public String authUrl() { return authUrl; }

        @Override
        public Optional<AuthorizedAccount> poll() {
            if (!rpc.completed(loginId)) return Optional.empty();
            JsonNode account = rpc.call("account/read", Map.of("refreshToken", false), REQUEST_TIMEOUT).path("account");
            String email = account.path("email").asString("");
            if (!"chatgpt".equals(account.path("type").asString())
                    || email.length() > 320 || !email.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")) {
                throw new IllegalStateException("Unable to read the authorized ChatGPT account");
            }
            Path authFile = directory.resolve("auth.json");
            byte[] bytes = null;
            try {
                if (!Files.isRegularFile(authFile, LinkOption.NOFOLLOW_LINKS)
                        || Files.size(authFile) > 1024 * 1024) throw new IOException();
                try (var input = Files.newInputStream(authFile)) {
                    bytes = input.readNBytes(1024 * 1024 + 1);
                }
                if (bytes.length > 1024 * 1024) throw new IOException();
                JsonNode credential = mapper.readTree(bytes);
                JsonNode tokens = credential.path("tokens");
                if (!credential.isObject() || tokens.path("access_token").asString("").isBlank()
                        || tokens.path("refresh_token").asString("").isBlank()) throw new IOException();
                return Optional.of(new AuthorizedAccount(email, credential));
            } catch (Exception exception) {
                throw new IllegalStateException("Unable to securely import the authorized credentials");
            } finally {
                if (bytes != null) Arrays.fill(bytes, (byte) 0);
            }
        }

        @Override
        public void close() {
            try { rpc.close(); } finally { cleanup(directory); }
        }
    }
}
