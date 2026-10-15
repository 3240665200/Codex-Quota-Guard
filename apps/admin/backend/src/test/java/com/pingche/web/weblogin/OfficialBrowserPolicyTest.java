package com.pingche.web.weblogin;

import static org.assertj.core.api.Assertions.*;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class OfficialBrowserPolicyTest {
    @Test
    void onlyFixedOfficialHttpsDestinationsArePermitted() {
        for (String url : List.of("https://chatgpt.com/settings/security?view=sessions", "https://auth.openai.com/log-in",
                "https://cdn.oaistatic.com/assets/main.js", "https://challenges.cloudflare.com/turnstile/v0/api.js")) {
            assertThat(OfficialDomainPolicy.allowed(url)).as(url).isTrue();
        }
        for (String url : List.of("http://chatgpt.com", "https://chatgpt.com:444", "https://chatgpt.com.evil.test",
                "https://chatgpt.com@127.0.0.1", "https://127.0.0.1", "https://localhost", "file:///etc/passwd",
                "chrome://settings", "https://accounts.google.com", "https://unlisted.openai.com", "https://auth.openai.com\\@evil.test")) {
            assertThat(OfficialDomainPolicy.allowed(url)).as(url).isFalse();
        }
    }

    @Test
    void inputIsBoundedAndNavigationAndDeveloperShortcutsAreBlocked() {
        BrowserInputPolicy.validate(new ManagedLoginBrowser.Input("CLICK", 20, 30, null, null, null, false, false));
        BrowserInputPolicy.validate(new ManagedLoginBrowser.Input("KEY", null, null, null, "Tab", null, false, false));
        BrowserInputPolicy.validate(new ManagedLoginBrowser.Input("KEY", null, null, null, "a", null, true, false));
        for (String key : List.of("l", "o", "n", "t", "w", "j", "i", "F12", "Escape")) {
            assertThatThrownBy(() -> BrowserInputPolicy.validate(new ManagedLoginBrowser.Input("KEY", null, null, null, key, null, true, true)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> BrowserInputPolicy.validate(new ManagedLoginBrowser.Input("CLICK", 1024, 0, null, null, null, false, false)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BrowserInputPolicy.validate(new ManagedLoginBrowser.Input("TEXT", null, null, null, null, "x".repeat(2049), false, false)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BrowserInputPolicy.validate(new ManagedLoginBrowser.Input("JS", null, null, null, null, "alert(1)", false, false)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new ManagedLoginBrowser.Input("TEXT", null, null, null, null, "synthetic-password", false, false).toString())
                .doesNotContain("synthetic-password");
    }

    @Test
    void commandUsesOwnedProfileLoopbackDebuggingAndSelectedProxyWithNoBypassOrWeakenedSecurity() {
        List<String> command = ChromiumManagedLoginBrowser.command(Path.of("browser"), Path.of("profile"), 12345, true);
        assertThat(command).contains("--remote-debugging-port=0", "--remote-debugging-address=127.0.0.1",
                "--proxy-server=http://127.0.0.1:12345", "--proxy-bypass-list=<-loopback>", "--headless=new", "about:blank");
        assertThat(String.join(" ", command)).doesNotContain("--no-sandbox", "--disable-web-security", "--ignore-certificate-errors",
                "AutomationControlled", "remote-allow-origins", "user-agent");
    }

    @Test
    void credentialsNeverRenderTheirContents() {
        assertThat(new ManagedLoginBrowser.Credentials("synthetic-cookie", "Bearer synthetic-token", "synthetic-account").toString())
                .doesNotContain("synthetic-cookie", "synthetic-token", "synthetic-account");
    }
}
