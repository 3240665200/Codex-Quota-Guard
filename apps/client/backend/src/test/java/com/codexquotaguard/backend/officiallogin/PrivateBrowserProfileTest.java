package com.codexquotaguard.backend.officiallogin;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PrivateBrowserProfileTest {
    @TempDir Path temporary;

    @Test void createsAndCleansOnlyItsPrivateProfile() throws Exception {
        Path unrelated=Files.writeString(temporary.resolve("unrelated.txt"),"synthetic");
        var profile=PrivateBrowserProfile.create(temporary);
        Path path=profile.path();
        assertThat(path.getParent()).isEqualTo(temporary.toRealPath());
        Files.writeString(path.resolve("synthetic-cookie-file"),"synthetic");
        profile.close();
        assertThat(path).doesNotExist();assertThat(unrelated).exists();
    }

    @Test void validatesItsOwnDebuggerFileAndNeverAcceptsRemoteWebSockets() throws Exception {
        try(var profile=PrivateBrowserProfile.create(temporary)) {
            Files.writeString(profile.path().resolve("DevToolsActivePort"),"12345\n/devtools/browser/synthetic-id\n");
            assertThat(profile.debuggerUri().toString()).isEqualTo("ws://127.0.0.1:12345/devtools/browser/synthetic-id");
            Files.writeString(profile.path().resolve("DevToolsActivePort"),"12345\nws://evil.test/debugger\n");
            assertThatThrownBy(profile::debuggerUri).isInstanceOf(OfficialLoginException.class);
        }
    }

    @Test void usesAnIndependentProfileAndLoopbackOnlyDebuggingWithoutLoginSecrets() {
        List<String> command=CdpOfficialLoginBrowser.command(Path.of("C:/Program Files/Google/Chrome/Application/chrome.exe"),temporary);
        assertThat(command).contains("--remote-debugging-port=0","--remote-debugging-address=127.0.0.1","about:blank");
        assertThat(command).anyMatch(value -> value.startsWith("--user-data-dir="));
        assertThat(command.toString()).doesNotContain("--headless","--user-agent","AutomationControlled","synthetic-token","--proxy-server");
    }

    @Test void waitsForACompleteOwnedDebuggerFileInsteadOfFailingOnItsCreationInstant() throws Exception {
        try(var profile=PrivateBrowserProfile.create(temporary)) {
            Files.writeString(profile.path().resolve("DevToolsActivePort"),"");
            var uri=CdpOfficialLoginBrowser.awaitDebugger(profile,() -> true,() -> 0L,() -> {
                try { Files.writeString(profile.path().resolve("DevToolsActivePort"),"12345\n/devtools/browser/synthetic-id\n"); }
                catch(java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
            });
            assertThat(uri.toString()).isEqualTo("ws://127.0.0.1:12345/devtools/browser/synthetic-id");
            assertThatThrownBy(() -> CdpOfficialLoginBrowser.awaitDebugger(profile,() -> false,() -> 0L,() -> {}))
                    .isInstanceOf(OfficialLoginException.class);
        }
    }
}
