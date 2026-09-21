package com.codexquotaguard.backend.codex;

import com.codexquotaguard.backend.account.CodexGateway;
import com.codexquotaguard.backend.quota.AccountState;
import com.codexquotaguard.backend.quota.RawRateLimitBucket;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/** Owns the local {@code codex app-server} process and its JSON-RPC client. */
public final class CodexAppServerGateway implements CodexGateway, AutoCloseable {

    private static final CodexClientInfo CLIENT_INFO = new CodexClientInfo(
            "codex_quota_guard", "Codex Quota Guard", "0.2.0"
    );

    private final String codexCommand;
    private Process process;
    private CodexAppServerClient client;

    public CodexAppServerGateway(String codexCommand) {
        if (codexCommand == null || codexCommand.isBlank()) {
            throw new IllegalArgumentException("Codex command is required.");
        }
        this.codexCommand = resolveCommand(
                codexCommand.trim(),
                System.getProperty("os.name", ""),
                System.getenv("PATH")
        );
    }

    @Override
    public synchronized AccountState readAccount() {
        return client().readAccount();
    }

    @Override
    public synchronized Map<String, RawRateLimitBucket> readRateLimits() {
        return client().readRateLimits();
    }

    @Override
    public synchronized DeviceLogin startDeviceLogin() {
        return client().startDeviceLogin();
    }

    @Override
    public synchronized void logout() {
        client().logout();
    }

    @Override
    public synchronized void close() {
        if (client != null) {
            client.close();
            client = null;
        }
        if (process != null) {
            stopProcess(process);
            process = null;
        }
    }

    private static void stopProcess(Process process) {
        process.destroy();
        try {
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(2, TimeUnit.SECONDS);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }

    private CodexAppServerClient client() {
        if (client != null && process != null && process.isAlive()) {
            return client;
        }
        close();
        try {
            process = new ProcessBuilder(codexCommand, "app-server")
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            client = new CodexAppServerClient(
                    new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)),
                    new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8))
            );
            client.initialize(CLIENT_INFO);
            return client;
        } catch (IOException | RuntimeException exception) {
            close();
            throw new CodexAppServerException("Unable to connect to the local Codex service.", exception);
        }
    }

    static String resolveCommand(String command, String operatingSystem, String pathValue) {
        boolean isWindows = operatingSystem.toLowerCase(Locale.ROOT).startsWith("windows");
        boolean isBareCommand = !command.contains("/") && !command.contains("\\");
        if (!isWindows || !isBareCommand || command.toLowerCase(Locale.ROOT).endsWith(".exe") || pathValue == null) {
            return command;
        }

        for (String directory : pathValue.split(Pattern.quote(File.pathSeparator))) {
            if (directory.isBlank()) {
                continue;
            }
            Path candidate = Path.of(directory).resolve(command + ".exe");
            if (Files.isRegularFile(candidate)) {
                return candidate.toAbsolutePath().toString();
            }
        }
        return command;
    }
}
