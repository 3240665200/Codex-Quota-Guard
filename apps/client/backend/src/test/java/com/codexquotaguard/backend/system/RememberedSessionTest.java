package com.codexquotaguard.backend.system;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.codexquotaguard.backend.controlplane.ControlPlaneAuthSession;
import com.codexquotaguard.backend.controlplane.ControlPlaneException;
import com.codexquotaguard.backend.controlplane.ControlPlaneGateway;
import com.codexquotaguard.backend.controlplane.SystemUser;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RememberedSessionTest {
    private static final Instant NOW = Instant.parse("2026-10-13T00:00:00Z");
    private static final String ORIGIN = "https://server.example.com";
    private static final long LONG_TTL = Duration.ofDays(30).toSeconds();
    private static final SystemUser USER = new SystemUser(
            UUID.fromString("00000000-0000-4000-8000-000000000001"), "user@example.com", "User", "USER", "ACTIVE",
            null, null, null, null, null, null, null);
    @TempDir Path directory;

    @Test
    void restartRefreshesTheSavedTokenAndKeepsTheOriginalSevenDayDeadline() {
        var clock = new MutableClock();
        var firstGateway = gateway("access-one", "refresh-one", LONG_TTL);
        var firstStore = store(ORIGIN);
        var first = new SystemSessionService(firstGateway, firstStore, clock);
        first.login(USER.email(), "synthetic-password");
        assertThat(firstStore.load().orElseThrow().expiresAt()).isEqualTo(NOW.plus(Duration.ofDays(7)));
        first.clearOnShutdown();

        clock.advance(Duration.ofDays(2));
        var nextGateway = mock(ControlPlaneGateway.class);
        when(nextGateway.refresh("refresh-one")).thenReturn(auth("access-two", "refresh-two", LONG_TTL));
        when(nextGateway.current("access-two")).thenReturn(USER);
        try (var nextStore = store(ORIGIN)) {
            var next = new SystemSessionService(nextGateway, nextStore, clock);
            assertThat(next.localUserId()).isEmpty();
            assertThat(next.currentOverview().user()).isEqualTo(USER);
            var saved = nextStore.load().orElseThrow();
            assertThat(saved.refreshToken()).isEqualTo("refresh-two");
            assertThat(saved.loginAt()).isEqualTo(NOW);
            assertThat(saved.expiresAt()).isEqualTo(NOW.plus(Duration.ofDays(7)));
            verify(nextGateway, never()).login(any(), any());
        }
    }

    @Test
    void aShorterServerRefreshTokenLifetimeCapsTheInitialRememberedDeadline() {
        var clock = new MutableClock();
        try (var storage = store(ORIGIN)) {
            var service = new SystemSessionService(gateway("access-one", "refresh-one", 3600), storage, clock);
            service.login(USER.email(), "synthetic-password");
            assertThat(storage.load().orElseThrow().expiresAt()).isEqualTo(NOW.plusSeconds(3600));
            clock.advance(Duration.ofHours(1));
            assertThat(service.localUserId()).isEmpty();
            assertThat(storage.load()).isEmpty();
            assertThatThrownBy(service::requireLocalUserId).isInstanceOf(SystemSessionRequiredException.class);
        }
    }

    @Test
    void refreshingAnExpiredAccessTokenNeverRenewsTheLocalSevenDayWindow() {
        var clock = new MutableClock();
        var gateway = gateway("access-one", "refresh-one", LONG_TTL);
        when(gateway.current("access-one")).thenThrow(ControlPlaneException.unauthorized());
        when(gateway.refresh("refresh-one")).thenReturn(auth("access-two", "refresh-two", LONG_TTL));
        when(gateway.current("access-two")).thenReturn(USER);
        try (var storage = store(ORIGIN)) {
            var service = new SystemSessionService(gateway, storage, clock);
            service.login(USER.email(), "synthetic-password");
            clock.advance(Duration.ofDays(5));
            assertThat(service.currentOverview().authenticated()).isTrue();
            assertThat(storage.load().orElseThrow().expiresAt()).isEqualTo(NOW.plus(Duration.ofDays(7)));
            clock.advance(Duration.ofDays(2));
            assertThat(service.localUserId()).isEmpty();
            assertThat(storage.load()).isEmpty();
            assertThatThrownBy(service::quotaOverview).isInstanceOf(SystemSessionRequiredException.class);
        }
    }

    @Test
    void anOfflineRestoreKeepsTheUnverifiedCacheForAnExplicitRetry() {
        save("refresh-one", NOW.plus(Duration.ofDays(7)));
        var gateway = mock(ControlPlaneGateway.class);
        when(gateway.refresh("refresh-one")).thenThrow(ControlPlaneException.unavailable())
                .thenReturn(auth("access-two", "refresh-two", LONG_TTL));
        when(gateway.current("access-two")).thenReturn(USER);
        try (var storage = store(ORIGIN)) {
            var service = new SystemSessionService(gateway, storage, new MutableClock());
            assertThatThrownBy(service::currentOverview).isInstanceOf(ControlPlaneUnavailableException.class);
            assertThat(storage.load().orElseThrow().refreshToken()).isEqualTo("refresh-one");
            assertThat(service.localUserId()).isEmpty();
            assertThat(service.currentOverview().authenticated()).isTrue();
        }
    }

    @Test
    void anExplicitlyRevokedSavedTokenIsClearedWithoutAutoEnteringTheApplication() {
        save("refresh-one", NOW.plus(Duration.ofDays(7)));
        var gateway = mock(ControlPlaneGateway.class);
        when(gateway.refresh("refresh-one")).thenThrow(ControlPlaneException.unauthorized());
        try (var storage = store(ORIGIN)) {
            var service = new SystemSessionService(gateway, storage, new MutableClock());
            assertThat(service.currentOverview().authenticated()).isFalse();
            assertThat(storage.load()).isEmpty();
            verify(gateway, never()).current(any());
        }
    }

    @Test
    void anExpiredSavedSessionDoesNotContactTheCentralServer() {
        save("refresh-one", NOW);
        var gateway = mock(ControlPlaneGateway.class);
        try (var storage = store(ORIGIN)) {
            var service = new SystemSessionService(gateway, storage, new MutableClock());
            assertThat(service.currentOverview().authenticated()).isFalse();
            assertThat(storage.load()).isEmpty();
            verifyNoInteractions(gateway);
        }
    }

    @Test
    void aChangedServerOriginNeverReceivesThePreviousServersSavedToken() {
        save("refresh-one", NOW.plus(Duration.ofDays(7)));
        var gateway = mock(ControlPlaneGateway.class);
        try (var storage = store("https://other.example.com")) {
            var service = new SystemSessionService(gateway, storage, new MutableClock());
            assertThat(service.currentOverview().authenticated()).isFalse();
            verifyNoInteractions(gateway);
        }
        try (var original = store(ORIGIN)) { assertThat(original.load()).isPresent(); }
    }

    @Test
    void aRestoredTokenForTheWrongOrDisabledUserIsNeverAttached() {
        for (SystemUser user : new SystemUser[] {
                new SystemUser(UUID.randomUUID(), "other@example.com", "Other", "USER", "ACTIVE", null, null, null, null, null, null, null),
                new SystemUser(USER.id(), USER.email(), "User", "USER", "DISABLED", null, null, null, null, null, null, null) }) {
            save("refresh-one", NOW.plus(Duration.ofDays(7)));
            var gateway = mock(ControlPlaneGateway.class);
            when(gateway.refresh("refresh-one"))
                    .thenReturn(new ControlPlaneAuthSession("access-two", "refresh-two", user, LONG_TTL));
            try (var storage = store(ORIGIN)) {
                var service = new SystemSessionService(gateway, storage, new MutableClock());
                assertThat(service.currentOverview().authenticated()).isFalse();
                assertThat(service.localUserId()).isEmpty();
                assertThat(storage.load()).isEmpty();
                verify(gateway, never()).current(any());
            }
        }
    }

    @Test
    void logoutDeletesAnUnrestoredCacheBeforeAnyRemoteRevocationAttempt() {
        save("refresh-one", NOW.plus(Duration.ofDays(7)));
        var gateway = mock(ControlPlaneGateway.class);
        try (var storage = store(ORIGIN)) {
            var service = new SystemSessionService(gateway, storage, new MutableClock());
            service.logout();
            assertThat(storage.load()).isEmpty();
            assertThat(service.currentOverview().authenticated()).isFalse();
        }
    }

    @Test
    void logoutDuringRestoreCannotPublishOrPersistItsLateRotatedToken() {
        save("refresh-one", NOW.plus(Duration.ofDays(7)));
        var gateway = mock(ControlPlaneGateway.class);
        try (var storage = store(ORIGIN)) {
            var service = new SystemSessionService(gateway, storage, new MutableClock());
            when(gateway.refresh("refresh-one")).thenAnswer(call -> {
                service.logout();
                return auth("access-two", "refresh-two", LONG_TTL);
            });
            assertThat(service.currentOverview().authenticated()).isFalse();
            assertThat(storage.load()).isEmpty();
            verify(gateway).logout("access-two", "refresh-two");
        }
    }

    @Test
    void aFailedRotationWriteClearsTheConsumedOldTokenAndNeverUsesTheNewAccessToken() {
        var storage = mock(SessionStorage.class);
        when(storage.load()).thenReturn(Optional.empty());
        doNothing().doThrow(new SessionStorageException()).when(storage).save(any());
        var gateway = gateway("access-one", "refresh-one", LONG_TTL);
        when(gateway.current("access-one")).thenThrow(ControlPlaneException.unauthorized());
        when(gateway.refresh("refresh-one")).thenReturn(auth("access-two", "refresh-two", LONG_TTL));
        var service = new SystemSessionService(gateway, storage, new MutableClock());
        service.login(USER.email(), "synthetic-password");
        assertThatThrownBy(service::currentOverview).isInstanceOf(SessionStorageException.class);
        assertThat(service.localUserId()).isEmpty();
        verify(storage).clear();
        verify(gateway).logout("access-two", "refresh-two");
        verify(gateway, never()).current("access-two");
    }

    @Test
    void requiredMissingStorageConfigurationFailsBeforeLoginOrRestoreContactsTheServer() {
        var storage = SessionStorageConfiguration.create("", "", true, ORIGIN);
        var gateway = mock(ControlPlaneGateway.class);
        var service = new SystemSessionService(gateway, storage, new MutableClock());
        assertThatThrownBy(service::currentOverview).isInstanceOf(SessionStorageException.class)
                .hasMessage("SESSION_STORAGE_UNAVAILABLE");
        assertThatThrownBy(() -> service.login(USER.email(), "synthetic-password"))
                .isInstanceOf(SessionStorageException.class);
        verifyNoInteractions(gateway);
    }

    @Test
    void twoConcurrentExpiredAccessRequestsRotateTheRefreshTokenOnlyOnce() throws Exception {
        var gateway = gateway("access-one", "refresh-one", LONG_TTL);
        var bothOldRequests = new CountDownLatch(2);
        when(gateway.current("access-one")).thenAnswer(call -> {
            bothOldRequests.countDown();
            if (!bothOldRequests.await(5, TimeUnit.SECONDS)) throw new AssertionError("both requests must use the old access token");
            throw ControlPlaneException.unauthorized();
        });
        when(gateway.refresh("refresh-one")).thenReturn(auth("access-two", "refresh-two", LONG_TTL));
        when(gateway.current("access-two")).thenReturn(USER);
        var worker = Executors.newFixedThreadPool(2);
        try (var storage = store(ORIGIN)) {
            var service = new SystemSessionService(gateway, storage, new MutableClock());
            service.login(USER.email(), "synthetic-password");
            var first = worker.submit(service::currentOverview);
            var second = worker.submit(service::currentOverview);
            assertThat(first.get(5, TimeUnit.SECONDS).authenticated()).isTrue();
            assertThat(second.get(5, TimeUnit.SECONDS).authenticated()).isTrue();
            verify(gateway).refresh("refresh-one");
            assertThat(storage.load().orElseThrow().refreshToken()).isEqualTo("refresh-two");
        } finally { worker.shutdownNow(); }
    }

    private EncryptedSessionStorage store(String origin) {
        byte[] key = new byte[32];
        Arrays.fill(key, (byte) 7);
        return new EncryptedSessionStorage(directory.resolve("secure-session"), key, origin);
    }

    private void save(String token, Instant expiresAt) {
        try (var storage = store(ORIGIN)) { storage.save(new SavedSession(token, USER.id(), expiresAt.minus(Duration.ofDays(7)), expiresAt)); }
    }

    private static ControlPlaneGateway gateway(String access, String refresh, long ttl) {
        var gateway = mock(ControlPlaneGateway.class);
        when(gateway.login(USER.email(), "synthetic-password")).thenReturn(auth(access, refresh, ttl));
        when(gateway.current(access)).thenReturn(USER);
        return gateway;
    }

    private static ControlPlaneAuthSession auth(String access, String refresh, long ttl) {
        return new ControlPlaneAuthSession(access, refresh, USER, ttl);
    }

    private static final class MutableClock extends Clock {
        private volatile Instant now = NOW;
        void advance(Duration duration) { now = now.plus(duration); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
