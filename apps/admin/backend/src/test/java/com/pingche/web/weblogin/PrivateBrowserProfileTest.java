package com.pingche.web.weblogin;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.*;
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
}
