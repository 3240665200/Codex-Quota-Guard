package com.pingche.web.weblogin;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PrivateBrowserProfileTest {
    @TempDir Path temp;
    @Test
    void profileIsNewOwnedAndCleanupCannotTouchItsParent() throws Exception {
        Path sentinel = Files.createFile(temp.resolve("parent-sentinel"));
        var profile = PrivateBrowserProfile.create(temp); Path owned = profile.path();
        assertThat(owned).startsWith(temp).isNotEqualTo(temp);
        Files.writeString(owned.resolve("DevToolsActivePort"), "12345\n/devtools/browser/owned\n");
        assertThat(profile.debuggerUri().toString()).isEqualTo("ws://127.0.0.1:12345/devtools/browser/owned");
        Files.createDirectories(owned.resolve("nested")); Files.writeString(owned.resolve("nested/synthetic-cookie"), "synthetic");
        profile.close(); profile.close();
        assertThat(owned).doesNotExist(); assertThat(sentinel).exists();
    }
    @Test
    void debuggerFileCannotSelectAnArbitraryHostOrPort() throws Exception {
        var profile = PrivateBrowserProfile.create(temp);
        try {
            for (String contents : java.util.List.of("65536\n/devtools/browser/owned", "12345\n/devtools/page/foreign", "12345\nws://evil.test", "x".repeat(513))) {
                Files.writeString(profile.path().resolve("DevToolsActivePort"), contents);
                assertThatThrownBy(profile::debuggerUri).isInstanceOf(IllegalStateException.class);
            }
        } finally { profile.close(); }
    }

    @Test
    void temporaryDeletionLockIsRetriedOnlyAfterRevalidatingTheSameOwnedProfile() throws Exception {
        var profile = PrivateBrowserProfile.create(temp); Path root = profile.path();
        Path locked = Files.writeString(root.resolve("synthetic-locked-file"), "synthetic");
        AtomicInteger attempts = new AtomicInteger();
        try (var files = mockStatic(Files.class, CALLS_REAL_METHODS)) {
            files.when(() -> Files.delete(locked)).thenAnswer(invocation -> {
                if (attempts.incrementAndGet() <= 2) throw new FileSystemException(locked.toString(), null, "synthetic temporary lock");
                invocation.callRealMethod(); return null;
            });
            // CALLS_REAL_METHODS performs the initial stubbed invocation once while recording it.
            Files.writeString(locked, "synthetic");
            profile.close();
            assertThat(root).doesNotExist();
            assertThat(attempts.get()).isEqualTo(3);
            files.verify(() -> Files.readAttributes(root, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS), atLeast(3));
        } finally { profile.close(); }
    }

    @Test
    void persistentDeletionFailureDoesNotPermanentlyDisableALaterSafeCleanupAttempt() throws Exception {
        var profile = PrivateBrowserProfile.create(temp); Path root = profile.path();
        Path locked = Files.writeString(root.resolve("synthetic-locked-file"), "synthetic");
        try (var files = mockStatic(Files.class, CALLS_REAL_METHODS)) {
            files.when(() -> Files.delete(locked)).thenThrow(new FileSystemException(locked.toString(), null, "synthetic persistent lock"));
            Files.writeString(locked, "synthetic");
            profile.close();
            assertThat(root).exists();
        }
        profile.close();
        assertThat(root).doesNotExist();
    }
}
