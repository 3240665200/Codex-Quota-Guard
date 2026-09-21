package com.codexquotaguard.backend.account;

import com.codexquotaguard.backend.codex.DeviceLogin;
import com.codexquotaguard.backend.quota.AccountState;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AccountControllerTest {

    @Test
    void exposesOnlyTheSanitizedAccountOverview() {
        var gateway = new StubGateway(new AccountState(true, "plus"));
        var controller = new AccountController(new CodexAccountService(
                gateway, "room_design", "member_alice", "Alice", () -> Instant.parse("2026-09-08T00:00:00Z")
        ));

        AccountOverview overview = controller.overview();

        assertThat(overview.authenticated()).isTrue();
        assertThat(overview.planType()).isEqualTo("plus");
        assertThat(overview.toString()).doesNotContain("alice@example.com", "token");
    }

    @Test
    void delegatesDeviceLoginAndLogoutToTheAccountService() {
        var login = new DeviceLogin("chatgptDeviceCode", "login_1", "https://auth.openai.com/codex/device", "ABCD-1234");
        var gateway = new StubGateway(new AccountState(false, ""));
        gateway.login = login;
        var controller = new AccountController(new CodexAccountService(
                gateway, "room_design", "member_alice", "Alice", Instant::now
        ));

        assertThat(controller.startDeviceLogin()).isEqualTo(login);
        controller.logout();

        assertThat(gateway.logoutCalls).isEqualTo(1);
    }

    private static final class StubGateway implements CodexGateway {
        private final AccountState account;
        private DeviceLogin login;
        private int logoutCalls;

        private StubGateway(AccountState account) {
            this.account = account;
        }

        @Override
        public AccountState readAccount() {
            return account;
        }

        @Override
        public Map<String, com.codexquotaguard.backend.quota.RawRateLimitBucket> readRateLimits() {
            return Map.of();
        }

        @Override
        public DeviceLogin startDeviceLogin() {
            return login;
        }

        @Override
        public void logout() {
            logoutCalls++;
        }
    }
}
