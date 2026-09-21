package com.codexquotaguard.backend.codex;

import com.codexquotaguard.backend.quota.AccountState;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class CodexAppServerClientTest {

    @Test
    void initializesThenReadsTheAccountAndEveryRateLimitBucket() throws Exception {
        var serverInput = new PipedInputStream();
        var clientOutput = new PipedOutputStream(serverInput);
        var clientInput = new PipedInputStream();
        var serverOutput = new PipedOutputStream(clientInput);
        var serverError = new AtomicReference<Throwable>();

        var server = new Thread(() -> {
            try (
                    var reader = new BufferedReader(new InputStreamReader(serverInput, StandardCharsets.UTF_8));
                    var writer = new BufferedWriter(new OutputStreamWriter(serverOutput, StandardCharsets.UTF_8))
            ) {
                assertThat(reader.readLine()).contains("\"method\":\"initialize\"", "\"id\":1");
                writeLine(writer, "{\"id\":1,\"result\":{\"userAgent\":\"test\"}}");
                assertThat(reader.readLine()).contains("\"method\":\"initialized\"");

                assertThat(reader.readLine()).contains("\"method\":\"account/read\"", "\"id\":2");
                writeLine(writer, "{\"id\":2,\"result\":{\"account\":{\"email\":\"alice@example.com\",\"planType\":\"plus\"}}}");

                assertThat(reader.readLine()).contains("\"method\":\"account/rateLimits/read\"", "\"id\":3");
                writeLine(writer, "{\"id\":3,\"result\":{\"rateLimitsByLimitId\":{\"codex\":{\"limitId\":\"codex\",\"primary\":{\"usedPercent\":20.5,\"windowDurationMins\":300,\"resetsAt\":1788798296}},\"codex_other\":{\"limitId\":\"codex_other\",\"primary\":{\"usedPercent\":4,\"windowDurationMins\":60,\"resetsAt\":1788798296}}}}}");
            } catch (Throwable throwable) {
                serverError.set(throwable);
            }
        });
        server.start();

        try (var client = new CodexAppServerClient(
                new BufferedReader(new InputStreamReader(clientInput, StandardCharsets.UTF_8)),
                new BufferedWriter(new OutputStreamWriter(clientOutput, StandardCharsets.UTF_8))
        )) {
            client.initialize(new CodexClientInfo("quota_guard", "Codex Quota Guard", "0.1.0"));
            AccountState account = client.readAccount();
            var limits = client.readRateLimits();

            assertThat(account).isEqualTo(new AccountState(true, "plus"));
            assertThat(limits).hasSize(2);
            assertThat(limits.get("codex").primary().usedPercent()).isEqualTo(20.5);
            assertThat(limits.get("codex_other").primary().windowDurationMins()).isEqualTo(60L);
        }

        server.join(1_000);
        assertThat(serverError.get()).isNull();
    }

    @Test
    void startsDeviceLoginAndLogsOutThroughTheOfficialAccountMethods() throws Exception {
        var serverInput = new PipedInputStream();
        var clientOutput = new PipedOutputStream(serverInput);
        var clientInput = new PipedInputStream();
        var serverOutput = new PipedOutputStream(clientInput);
        var serverError = new AtomicReference<Throwable>();

        var server = new Thread(() -> {
            try (
                    var reader = new BufferedReader(new InputStreamReader(serverInput, StandardCharsets.UTF_8));
                    var writer = new BufferedWriter(new OutputStreamWriter(serverOutput, StandardCharsets.UTF_8))
            ) {
                assertThat(reader.readLine()).contains("\"method\":\"initialize\"");
                writeLine(writer, "{\"id\":1,\"result\":{}}");
                assertThat(reader.readLine()).contains("\"method\":\"initialized\"");

                var login = reader.readLine();
                assertThat(login).contains("\"method\":\"account/login/start\"");
                assertThat(login).contains("chatgptDeviceCode");
                writeLine(writer, "{\"id\":2,\"result\":{\"type\":\"chatgptDeviceCode\",\"loginId\":\"login_1\",\"verificationUrl\":\"https://auth.openai.com/codex/device\",\"userCode\":\"ABCD-1234\"}}");

                assertThat(reader.readLine()).contains("\"method\":\"account/logout\"");
                writeLine(writer, "{\"id\":3,\"result\":{}}");
            } catch (Throwable throwable) {
                serverError.set(throwable);
            }
        });
        server.start();

        try (var client = new CodexAppServerClient(
                new BufferedReader(new InputStreamReader(clientInput, StandardCharsets.UTF_8)),
                new BufferedWriter(new OutputStreamWriter(clientOutput, StandardCharsets.UTF_8))
        )) {
            client.initialize(new CodexClientInfo("quota_guard", "Codex Quota Guard", "0.1.0"));
            var login = client.startDeviceLogin();

            assertThat(login.loginId()).isEqualTo("login_1");
            assertThat(login.userCode()).isEqualTo("ABCD-1234");
            client.logout();
        }

        server.join(1_000);
        assertThat(serverError.get()).isNull();
    }

    private static void writeLine(BufferedWriter writer, String message) throws Exception {
        writer.write(message);
        writer.newLine();
        writer.flush();
    }
}
