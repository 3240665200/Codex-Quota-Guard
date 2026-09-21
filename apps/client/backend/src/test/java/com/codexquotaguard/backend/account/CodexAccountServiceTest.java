package com.codexquotaguard.backend.account;

import com.codexquotaguard.backend.codex.DeviceLogin;
import com.codexquotaguard.backend.quota.AccountState;
import com.codexquotaguard.backend.quota.RawRateLimitBucket;
import com.codexquotaguard.backend.quota.RawRateLimitWindow;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CodexAccountServiceTest {

    @Test
    void returnsAnExactLocalQuotaSnapshotWithoutTheAccountEmail() {
        var now = Instant.parse("2026-09-08T00:00:00Z");
        var gateway = new FakeCodexGateway(
                new AccountState(true, "plus"),
                Map.of("codex", new RawRateLimitBucket(
                        "codex", "", new RawRateLimitWindow(23.0, 300L, now.plusSeconds(7_200).getEpochSecond()), null, "plus", false
                )),
                null
        );
        var service = new CodexAccountService(gateway, "room_design", "member_alice", "Alice", () -> now);

        var overview = service.readOverview();

        assertThat(overview.authenticated()).isTrue();
        assertThat(overview.planType()).isEqualTo("plus");
        assertThat(overview.snapshot().memberId()).isEqualTo("member_alice");
        assertThat(overview.snapshot().buckets().get(0).primary().usedPercent()).isEqualTo(23.0);
        assertThat(overview.toString()).doesNotContain("alice@example.com", "email", "token");
    }

    @Test
    void doesNotReadRateLimitsWhenTheAccountIsNotAuthenticated() {
        var gateway = new FakeCodexGateway(new AccountState(false, ""), Map.of(), null);
        var service = new CodexAccountService(gateway, "room_design", "member_alice", "Alice", Instant::now);

        var overview = service.readOverview();

        assertThat(overview.authenticated()).isFalse();
        assertThat(overview.snapshot().buckets()).isEmpty();
        assertThat(gateway.rateLimitsCalls).isZero();
    }

    @Test
    void delegatesOfficialLoginAndLogoutOperations() {
        var login = new DeviceLogin("chatgptDeviceCode", "login_1", "https://auth.openai.com/codex/device", "ABCD-1234");
        var gateway = new FakeCodexGateway(new AccountState(false, ""), Map.of(), login);
        var service = new CodexAccountService(gateway, "room_design", "member_alice", "Alice", Instant::now);

        assertThat(service.startDeviceLogin()).isEqualTo(login);
        service.logout();
        assertThat(gateway.logoutCalls).isEqualTo(1);
    }

    private static final class FakeCodexGateway implements CodexGateway {
        private final AccountState account;
        private final Map<String, RawRateLimitBucket> rateLimits;
        private final DeviceLogin login;
        private int rateLimitsCalls;
        private int logoutCalls;

        private FakeCodexGateway(AccountState account, Map<String, RawRateLimitBucket> rateLimits, DeviceLogin login) {
            this.account = account;
            this.rateLimits = rateLimits;
            this.login = login;
        }

        @Override
        public AccountState readAccount() {
            return account;
        }

        @Override
        public Map<String, RawRateLimitBucket> readRateLimits() {
            rateLimitsCalls++;
            return rateLimits;
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
