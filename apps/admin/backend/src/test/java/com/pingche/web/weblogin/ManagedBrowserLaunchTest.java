package com.pingche.web.weblogin;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.pingche.web.auth.DomainException;
import com.pingche.web.proxy.*;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** No real child process or network connection is started by these tests. */
class ManagedBrowserLaunchTest {
    private final ObjectMapper json = new ObjectMapper();
    private final UUID job = UUID.randomUUID(), node = UUID.randomUUID();

    @Test
    void ownedLaunchUsesChosenProxyAndPrivateEnvironmentAndClosesAllOwnedResourcesExactlyOnce() {
        Fixture fixture = new Fixture();
        try (var browser = fixture.browser()) {
            var session = browser.open(job, node);
            assertThat(fixture.command).contains("--proxy-server=http://127.0.0.1:12345", "--remote-debugging-port=0");
            assertThat(fixture.environment).doesNotContainKeys("DATABASE_PASSWORD", "JWT_SECRET", "OFFICIAL_SESSION_ENCRYPTION_KEY");
            assertThat(fixture.environment.get("HOME")).isEqualTo(fixture.profile.toString());
            assertThat(fixture.profile).exists();
            session.close(); session.close();
            assertThat(fixture.profile).doesNotExist();
            assertThat(fixture.process.alive).isFalse();
            verify(fixture.lease, times(1)).close();
            verify(fixture.gateway).open(node, ManagedProxyGateway.Purpose.BROWSER_LOGIN);
        }
    }

    @Test
    void failedCdpAndUnexpectedBrowserExitBothCleanUpAndCannotLeaveUsableSessions() {
        Fixture failure = new Fixture(); failure.cdp.failConnect = true;
        try (var browser = failure.browser()) {
            assertThatThrownBy(() -> browser.open(job, node)).isInstanceOf(DomainException.class);
            assertThat(failure.profile).doesNotExist(); assertThat(failure.process.alive).isFalse();
            verify(failure.lease).close();
        }
        Fixture exit = new Fixture();
        try (var browser = exit.browser()) {
            var session = browser.open(job, node); exit.process.destroy();
            assertThatThrownBy(session::frame).hasMessage("WEB_LOGIN_BROWSER_UNAVAILABLE");
            assertThat(exit.profile).doesNotExist(); verify(exit.lease).close();
        }
    }

    @Test
    void proxyLossClosesBrowserAndNeverRequestsADirectConnection() {
        Fixture fixture = new Fixture();
        try (var browser = fixture.browser()) {
            var session = browser.open(job, node); fixture.proxyAlive.set(false);
            assertThatThrownBy(session::capture).hasMessage("WEB_LOGIN_BROWSER_UNAVAILABLE");
            assertThat(fixture.process.alive).isFalse(); assertThat(fixture.profile).doesNotExist();
            verify(fixture.gateway, never()).open(any());
        }
    }

    @Test
    void missingProxyNeverStartsABrowserOrRequestsADirectConnection() {
        Fixture fixture = new Fixture();
        try (var browser = fixture.browser()) {
            assertThatThrownBy(() -> browser.open(job, null)).isInstanceOf(DomainException.class);
            assertThat(fixture.command).isNull();
            verifyNoInteractions(fixture.gateway);
        }
    }

    @Test
    void browserLaunchUsesTheRequestedFrontAndExitWithoutOpeningTheExitAlone() {
        UUID front=UUID.randomUUID();
        Fixture fixture=new Fixture();
        when(fixture.gateway.open(front,node,ManagedProxyGateway.Purpose.BROWSER_LOGIN)).thenReturn(fixture.lease);
        try(var browser=fixture.browser()) {
            var session=browser.open(job,front,node);
            assertThat(fixture.command).contains("--proxy-server=http://127.0.0.1:12345");
            verify(fixture.gateway).open(front,node,ManagedProxyGateway.Purpose.BROWSER_LOGIN);
            verify(fixture.gateway,never()).open(node,ManagedProxyGateway.Purpose.BROWSER_LOGIN);
            session.close();verify(fixture.lease).close();
        }
    }

    @Test
    void rejectsDirectOrNonLoopbackLeaseBeforeStartingAProcessAndReportsDisabledOrMissingBrowser() {
        Fixture fixture = new Fixture();
        when(fixture.lease.proxySelector()).thenReturn(ProxySelector.of(null));
        try (var browser = fixture.browser()) {
            assertThatThrownBy(() -> browser.open(job, node)).isInstanceOf(DomainException.class);
            assertThat(fixture.profile).isNull(); verify(fixture.lease).close();
        }
        var gateway = mock(ManagedProxyGateway.class);
        var disabled = new ChromiumManagedLoginBrowser(json, gateway, false, "", true);
        assertThat(disabled.available()).isFalse();
        assertThatThrownBy(() -> disabled.open(job, node)).isInstanceOf(DomainException.class);
        disabled.shutdown();
        var missing = new ChromiumManagedLoginBrowser(json, gateway, true, Path.of(System.getProperty("java.io.tmpdir"), "nonexistent-managed-browser").toString(), true);
        assertThat(missing.available()).isFalse();
        assertThatThrownBy(() -> missing.open(job, node)).isInstanceOf(DomainException.class);
        missing.shutdown(); verifyNoInteractions(gateway);
    }

    private final class Fixture {
        final ManagedProxyGateway gateway = mock(ManagedProxyGateway.class);
        final ProxyLease lease = mock(ProxyLease.class);
        final AtomicBoolean proxyAlive = new AtomicBoolean(true);
        final FakeProcess process = new FakeProcess();
        final FakeCdp cdp = new FakeCdp();
        List<String> command; Map<String, String> environment; Path profile;
        Fixture() {
            when(gateway.open(node, ManagedProxyGateway.Purpose.BROWSER_LOGIN)).thenReturn(lease);
            when(lease.proxySelector()).thenReturn(ProxySelector.of(new InetSocketAddress("127.0.0.1", 12345)));
            when(lease.isAlive()).thenAnswer(ignored -> proxyAlive.get());
        }
        TestBrowser browser() {
            String java = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
            var browser = new ChromiumManagedLoginBrowser(json, gateway, true, java, true, builder -> {
                command = List.copyOf(builder.command()); environment = Map.copyOf(builder.environment());
                profile = Path.of(command.stream().filter(value -> value.startsWith("--user-data-dir=")).findFirst().orElseThrow().substring(16));
                Files.writeString(profile.resolve("DevToolsActivePort"), "12345\n/devtools/browser/owned\n");
                return process;
            }, (mapper, http, events) -> cdp);
            assertThat(browser.available()).isTrue();
            return new TestBrowser(browser);
        }
    }
    private record TestBrowser(ChromiumManagedLoginBrowser delegate) implements AutoCloseable {
        ManagedLoginBrowser.Session open(UUID job, UUID node) { return delegate.open(job, node); }
        ManagedLoginBrowser.Session open(UUID job, UUID front, UUID node) { return delegate.open(job,front,node); }
        @Override public void close() { delegate.shutdown(); }
    }
    private static final class FakeProcess extends Process {
        boolean alive = true; final CompletableFuture<Process> exit = new CompletableFuture<>();
        @Override public boolean isAlive() { return alive; }
        @Override public CompletableFuture<Process> onExit() { return exit; }
        @Override public Stream<ProcessHandle> descendants() { return Stream.empty(); }
        @Override public void destroy() { alive = false; exit.complete(this); }
        @Override public Process destroyForcibly() { destroy(); return this; }
        @Override public boolean waitFor(long time, TimeUnit unit) { return !alive; }
        @Override public int waitFor() { return 0; }
        @Override public int exitValue() { return alive ? 1 : 0; }
        @Override public OutputStream getOutputStream() { return new ByteArrayOutputStream(); }
        @Override public InputStream getInputStream() { return InputStream.nullInputStream(); }
        @Override public InputStream getErrorStream() { return InputStream.nullInputStream(); }
    }
    private final class FakeCdp implements BrowserCdp {
        final CompletableFuture<Void> ended = new CompletableFuture<>(); boolean failConnect;
        @Override public void connect(URI uri) { if (failConnect) throw new IllegalStateException("synthetic-private-protocol-body"); }
        @Override public CompletableFuture<JsonNode> send(String method, Map<String, ?> params, String session) {
            Object value = switch (method) {
                case "Target.getTargets" -> Map.of("targetInfos", List.of(Map.of("type", "page", "url", "about:blank", "targetId", "target")));
                case "Target.attachToTarget" -> Map.of("sessionId", "owned");
                default -> Map.of();
            };
            return CompletableFuture.completedFuture(json.valueToTree(value));
        }
        @Override public CompletableFuture<Void> termination() { return ended; }
        @Override public void close() { ended.complete(null); }
    }
}
