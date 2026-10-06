package com.codexquotaguard.backend.system;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.codexquotaguard.backend.controlplane.ControlPlaneAuthSession;
import com.codexquotaguard.backend.controlplane.ControlPlaneException;
import com.codexquotaguard.backend.controlplane.ControlPlaneGateway;
import com.codexquotaguard.backend.controlplane.PersonalQuotaOverview;
import com.codexquotaguard.backend.controlplane.QuotaMeasurementStatus;
import com.codexquotaguard.backend.controlplane.QuotaPolicy;
import com.codexquotaguard.backend.controlplane.QuotaUsage;
import com.codexquotaguard.backend.controlplane.SystemUser;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SystemSessionServiceTest {

    private static final String ACCESS_ONE = "access_very_secret_value_111111111111";
    private static final String ACCESS_TWO = "access_very_secret_value_222222222222";
    private static final String REFRESH_ONE = "refresh_very_secret_value_11111111111";
    private static final String REFRESH_TWO = "refresh_very_secret_value_22222222222";

    @Test
    void returnsOnlySafeMetadataAfterLoginAndKeepsTokensInsideTheGatewayBoundary() {
        var user = user("Alice");
        var gateway = new FakeGateway(new ControlPlaneAuthSession(ACCESS_ONE, REFRESH_ONE, user));
        var service = new SystemSessionService(gateway);

        var result = service.login("alice@example.com", "correct horse battery staple");

        assertThat(result.authenticated()).isTrue();
        assertThat(result.user()).isEqualTo(user);
        assertThat(result.toString()).doesNotContain(ACCESS_ONE, REFRESH_ONE);
        assertThat(gateway.lastPassword).isEqualTo("correct horse battery staple");
    }

    @Test
    void requestsOnlyTheSignedInUsersPersonalQuotaAndKeepsTokensOutOfTheResponse() {
        var user = user("Alice");
        var gateway = new FakeGateway(new ControlPlaneAuthSession(ACCESS_ONE, REFRESH_ONE, user));
        var service = new SystemSessionService(gateway);
        service.login("alice@example.com", "password");

        var overview = service.quotaOverview();

        assertThat(gateway.quotaOverviewCalls).isEqualTo(1);
        assertThat(gateway.lastAccessToken).isEqualTo(ACCESS_ONE);
        assertThat(overview.user()).isEqualTo(user);
        assertThat(overview.usage().fiveHourUsedPercent()).isEqualTo(25.0);
        assertThat(overview.toString()).doesNotContain(ACCESS_ONE, REFRESH_ONE);
    }

    @Test
    void refreshesTheInMemorySessionAfterTheCentralServiceRejectsAnExpiredAccessToken() {
        var user = user("Alice");
        var gateway = new FakeGateway(new ControlPlaneAuthSession(ACCESS_ONE, REFRESH_ONE, user));
        gateway.rejectFirstAccessToken = true;
        gateway.refreshedSession = new ControlPlaneAuthSession(ACCESS_TWO, REFRESH_TWO, user);
        var service = new SystemSessionService(gateway);
        service.login("alice@example.com", "password");

        var result = service.currentOverview();

        assertThat(result.authenticated()).isTrue();
        assertThat(gateway.currentCalls).isEqualTo(2);
        assertThat(gateway.refreshCalls).isEqualTo(1);
        assertThat(gateway.lastRefreshToken).isEqualTo(REFRESH_ONE);
        assertThat(gateway.lastAccessToken).isEqualTo(ACCESS_TWO);
    }

    @Test
    void turnsAForcedLogoutIntoASignedOutStartupState() {
        var user = user("Alice");
        var gateway = new FakeGateway(new ControlPlaneAuthSession(ACCESS_ONE, REFRESH_ONE, user));
        gateway.rejectAllAccessTokens = true;
        gateway.rejectRefresh = true;
        var service = new SystemSessionService(gateway);
        service.login("alice@example.com", "password");

        var result = service.currentOverview();

        assertThat(result.authenticated()).isFalse();
        assertThat(result.user()).isNull();
        assertThat(service.currentOverview().authenticated()).isFalse();
    }

    @Test
    void doesNotReplaceAnExistingSessionWhenTheNextLoginIsDenied() {
        var alice = user("Alice");
        var gateway = new FakeGateway(new ControlPlaneAuthSession(ACCESS_ONE, REFRESH_ONE, alice));
        var service = new SystemSessionService(gateway);
        service.login("alice@example.com", "password");
        gateway.rejectLogin = true;

        assertThatThrownBy(() -> service.login("bob@example.com", "wrong"))
                .isInstanceOf(LoginDeniedException.class);

        assertThat(service.currentOverview().authenticated()).isTrue();
        assertThat(service.currentOverview().user()).isEqualTo(alice);
    }

    @Test
    void clearsTheLocalSessionEvenWhenRemoteLogoutCannotBeReached() {
        var user = user("Alice");
        var gateway = new FakeGateway(new ControlPlaneAuthSession(ACCESS_ONE, REFRESH_ONE, user));
        gateway.logoutFailure = true;
        var service = new SystemSessionService(gateway);
        service.login("alice@example.com", "password");

        service.logout();

        assertThat(service.currentOverview().authenticated()).isFalse();
        assertThat(gateway.logoutCalls).isEqualTo(1);
    }

    @Test
    void refreshesOnceToRevokeTheServerSessionWhenAccessExpiresDuringLogout() {
        var user = user("Alice");
        var gateway = new FakeGateway(new ControlPlaneAuthSession(ACCESS_ONE, REFRESH_ONE, user));
        gateway.rejectFirstLogout = true;
        gateway.refreshedSession = new ControlPlaneAuthSession(ACCESS_TWO, REFRESH_TWO, user);
        var service = new SystemSessionService(gateway);
        service.login("alice@example.com", "password");

        service.logout();

        assertThat(gateway.refreshCalls).isEqualTo(1);
        assertThat(gateway.logoutCalls).isEqualTo(2);
        assertThat(gateway.lastRefreshToken).isEqualTo(REFRESH_ONE);
        assertThat(service.currentOverview().authenticated()).isFalse();
    }

    private static SystemUser user(String displayName) {
        return new SystemUser(
                UUID.randomUUID(),
                displayName.toLowerCase() + "@example.com",
                displayName,
                "USER",
                "ACTIVE",
                null,
                null,
                null,
                null,
                null,
                null
        );
    }

    private static PersonalQuotaOverview overview(SystemUser user) {
        return new PersonalQuotaOverview(
                false,
                user,
                null,
                null,
                new QuotaPolicy(80, 90, "USER", "DEFAULT"),
                new QuotaUsage(
                        QuotaMeasurementStatus.CURRENT,
                        25.0,
                        15.0,
                        75.0,
                        85.0,
                        "2026-01-01T05:00:00Z",
                        "2026-01-08T00:00:00Z",
                        "2026-01-01T00:00:00Z"
                ),
                true,
                null
        );
    }

    private static final class FakeGateway implements ControlPlaneGateway {
        private final ControlPlaneAuthSession loginSession;
        private ControlPlaneAuthSession refreshedSession;
        private boolean rejectLogin;
        private boolean rejectFirstAccessToken;
        private boolean rejectAllAccessTokens;
        private boolean rejectRefresh;
        private boolean logoutFailure;
        private boolean rejectFirstLogout;
        private int currentCalls;
        private int quotaOverviewCalls;
        private int refreshCalls;
        private int logoutCalls;
        private String lastPassword;
        private String lastAccessToken;
        private String lastRefreshToken;

        private FakeGateway(ControlPlaneAuthSession loginSession) {
            this.loginSession = loginSession;
            this.refreshedSession = loginSession;
        }

        @Override
        public ControlPlaneAuthSession login(String email, String password) {
            lastPassword = password;
            if (rejectLogin) {
                throw ControlPlaneException.unauthorized();
            }
            return loginSession;
        }

        @Override
        public ControlPlaneAuthSession refresh(String refreshToken) {
            refreshCalls++;
            lastRefreshToken = refreshToken;
            if (rejectRefresh) {
                throw ControlPlaneException.unauthorized();
            }
            return refreshedSession;
        }

        @Override
        public SystemUser current(String accessToken) {
            currentCalls++;
            lastAccessToken = accessToken;
            if (rejectAllAccessTokens || (rejectFirstAccessToken && ACCESS_ONE.equals(accessToken))) {
                throw ControlPlaneException.unauthorized();
            }
            return loginSession.user();
        }

        @Override
        public PersonalQuotaOverview quotaOverview(String accessToken) {
            quotaOverviewCalls++;
            lastAccessToken = accessToken;
            if (rejectAllAccessTokens || (rejectFirstAccessToken && ACCESS_ONE.equals(accessToken))) {
                throw ControlPlaneException.unauthorized();
            }
            return overview(loginSession.user());
        }

        @Override
        public void logout(String accessToken, String refreshToken) {
            logoutCalls++;
            if (rejectFirstLogout) {
                rejectFirstLogout = false;
                throw ControlPlaneException.unauthorized();
            }
            if (logoutFailure) {
                throw ControlPlaneException.unavailable();
            }
        }
    }
}
