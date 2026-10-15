package com.pingche.web.proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.pingche.web.auth.DomainException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class SingBoxProcessRunnerTest {
    @Test
    void browserLeaseHasItsOwnTenMinuteLifetimeAndFixedLoginTargets() {
        FakeProcess legacy = new FakeProcess();
        FakeProcess browser = new FakeProcess();
        var queue = new java.util.ArrayDeque<Process>(java.util.List.of(legacy, browser));
        SingBoxProcessRunner runner = new SingBoxProcessRunner("synthetic-kernel", 2, 100, 30,
                executable -> queue.remove(), port -> true);
        try {
            runner.open(port -> new ObjectMapper().createObjectNode());
            ProxyLease lease = runner.open(port -> new ObjectMapper().createObjectNode(), ManagedProxyGateway.Purpose.BROWSER_LOGIN);
            assertThat(lease.proxySelector().select(URI.create("https://auth.openai.com/log-in"))).hasSize(1);
            assertThatThrownBy(() -> lease.proxySelector().select(URI.create("https://accounts.google.com"))).isInstanceOf(IllegalArgumentException.class);
            await().atMost(Duration.ofSeconds(2)).until(() -> !legacy.alive);
            assertThat(browser.alive).isTrue();
            assertThat(lease.isAlive()).isTrue();
            browser.destroy();
            assertThat(lease.isAlive()).isFalse();
            lease.close();
        } finally { runner.destroy(); }
    }
    @Test
    void sendsConfigurationOnlyOverStdinRestrictsProxyAndReleasesCapacityOnClose() {
        FakeProcess process = new FakeProcess();
        SingBoxProcessRunner runner = new SingBoxProcessRunner("synthetic-kernel", 1, 100, 10_000,
                executable -> process, port -> true);
        try {
            ProxyLease lease = runner.open(port -> new ObjectMapper().readTree("{\"password\":\"synthetic-secret\"}"));
            assertThat(process.input.toString()).contains("synthetic-secret");
            assertThat(lease.proxySelector().select(URI.create("https://chatgpt.com/backend-api/wham/usage"))).hasSize(1)
                    .allSatisfy(proxy -> assertThat(proxy.type()).isEqualTo(java.net.Proxy.Type.HTTP));
            assertThatThrownBy(() -> lease.proxySelector().select(URI.create("https://example.com/"))).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> runner.open(port -> new ObjectMapper().createObjectNode())).isInstanceOf(DomainException.class);
            lease.close(); lease.close();
            assertThat(process.alive).isFalse();
            assertThatThrownBy(() -> lease.proxySelector().select(URI.create("https://chatgpt.com/"))).isInstanceOf(IllegalStateException.class);
        } finally { runner.destroy(); }
    }

    @Test
    void failedStartupAndExpiredLeaseDestroyTheProcess() {
        FakeProcess failed = new FakeProcess();
        SingBoxProcessRunner runner = new SingBoxProcessRunner("synthetic-kernel", 1, 20, 100,
                executable -> failed, port -> false);
        try {
            assertThatThrownBy(() -> runner.open(port -> new ObjectMapper().createObjectNode())).isInstanceOf(DomainException.class);
            assertThat(failed.alive).isFalse();
        } finally { runner.destroy(); }
        FakeProcess expires = new FakeProcess();
        SingBoxProcessRunner deadline = new SingBoxProcessRunner("synthetic-kernel", 1, 100, 30,
                executable -> expires, port -> true);
        try {
            deadline.open(port -> new ObjectMapper().createObjectNode());
            await().atMost(Duration.ofSeconds(2)).until(() -> !expires.alive);
        } finally { deadline.destroy(); }
    }

    private static final class FakeProcess extends Process {
        private final ByteArrayOutputStream input = new ByteArrayOutputStream();
        private volatile boolean alive = true;
        @Override public OutputStream getOutputStream() { return input; }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(new byte[0]); }
        @Override public InputStream getErrorStream() { return new ByteArrayInputStream(new byte[0]); }
        @Override public int waitFor() { return 0; }
        @Override public boolean waitFor(long timeout, TimeUnit unit) { return !alive; }
        @Override public int exitValue() { return alive ? 1 : 0; }
        @Override public void destroy() { alive = false; }
        @Override public Process destroyForcibly() { alive = false; return this; }
        @Override public boolean isAlive() { return alive; }
    }
}
