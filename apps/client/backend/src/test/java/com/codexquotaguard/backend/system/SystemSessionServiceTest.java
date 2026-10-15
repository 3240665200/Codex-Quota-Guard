package com.codexquotaguard.backend.system;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.codexquotaguard.backend.controlplane.ControlPlaneAuthSession;
import com.codexquotaguard.backend.controlplane.ControlPlaneException;
import com.codexquotaguard.backend.controlplane.ControlPlaneGateway;
import com.codexquotaguard.backend.controlplane.PersonalQuotaOverview;
import com.codexquotaguard.backend.controlplane.QuotaMeasurementStatus;
import com.codexquotaguard.backend.controlplane.QuotaPolicy;
import com.codexquotaguard.backend.controlplane.QuotaUsage;
import com.codexquotaguard.backend.controlplane.SystemUser;
import com.codexquotaguard.backend.usage.CodexUsageEvent;
import java.util.List;
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
        assertThat(overview.usage().fiveHourUsedTokens()).isEqualTo(25_000L);
        assertThat(overview.toString()).doesNotContain(ACCESS_ONE, REFRESH_ONE);
    }

    @Test
    void refusesToAttributeScannerEventsToADifferentSystemUser() {
        var alice = user("Alice");
        var gateway = new FakeGateway(new ControlPlaneAuthSession(ACCESS_ONE, REFRESH_ONE, alice));
        var service = new SystemSessionService(gateway);
        service.login("alice@example.com", "password");

        assertThatThrownBy(() -> service.reportUsage(UUID.randomUUID(), List.of()))
                .isInstanceOf(SystemSessionRequiredException.class);
        assertThat(gateway.reportCalls).isZero();
    }

    @Test
    void classifiesCentralInvalidRequestsAsRejectedInsteadOfUnavailable() {
        var alice = user("Alice");
        var gateway = new FakeGateway(new ControlPlaneAuthSession(ACCESS_ONE, REFRESH_ONE, alice));
        gateway.reportFailure = ControlPlaneException.invalidRequest();
        var service = new SystemSessionService(gateway);
        service.login("alice@example.com", "password");

        assertThatThrownBy(() -> service.reportUsage(alice.id(), List.of()))
                .isInstanceOf(ControlPlaneRequestRejectedException.class)
                .hasMessage("CONTROL_PLANE_REQUEST_REJECTED")
                .isNotInstanceOf(ControlPlaneUnavailableException.class);
        assertThat(gateway.reportCalls).isEqualTo(1);
    }

    @Test
    void returnsOnlyAFixedBadRequestCodeForRejectedControlPlaneRequests() {
        var response = new LocalSessionExceptionHandler().controlPlaneRequestRejected();

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("CONTROL_PLANE_REQUEST_REJECTED");
        assertThat(response.getBody().message()).isEqualTo("中央后台拒绝请求，请检查请求内容后重试。");
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

    @Test
    void aLoginResponseThatArrivesAfterLogoutCannotRestoreTheSession() {
        var alice = user("Alice");
        var gateway = mock(ControlPlaneGateway.class);
        var service = new SystemSessionService(gateway);
        when(gateway.login("alice@example.com", "synthetic-password")).thenAnswer(call -> {
            service.logout();
            return new ControlPlaneAuthSession(ACCESS_ONE, REFRESH_ONE, alice);
        });
        when(gateway.current(ACCESS_ONE)).thenReturn(alice);

        assertThatThrownBy(() -> service.login("alice@example.com", "synthetic-password"))
                .isInstanceOf(SystemSessionRequiredException.class);
        assertThat(service.currentOverview().authenticated()).isFalse();
    }

    @Test
    void aRefreshForAnotherUserNeverAttachesThatIdentityToTheCurrentSession() {
        var alice = user("Alice");
        var bob = user("Bob");
        var gateway = mock(ControlPlaneGateway.class);
        when(gateway.login("alice@example.com", "synthetic-password"))
                .thenReturn(new ControlPlaneAuthSession(ACCESS_ONE, REFRESH_ONE, alice));
        when(gateway.current(ACCESS_ONE)).thenThrow(ControlPlaneException.unauthorized());
        when(gateway.refresh(REFRESH_ONE)).thenReturn(new ControlPlaneAuthSession(ACCESS_TWO, REFRESH_TWO, bob));
        when(gateway.current(ACCESS_TWO)).thenReturn(bob);
        var service = new SystemSessionService(gateway);
        service.login("alice@example.com", "synthetic-password");

        assertThat(service.currentOverview().authenticated()).isFalse();
        assertThat(service.localUserId()).isEmpty();
    }

    @Test
    void aMetadataRefreshDoesNotMistakeAnUnchangedExpiredTokenForRotatedCredentials() {
        var alice = user("Alice");
        var gateway = mock(ControlPlaneGateway.class);
        when(gateway.login("alice@example.com", "synthetic-password"))
                .thenReturn(new ControlPlaneAuthSession(ACCESS_ONE, REFRESH_ONE, alice));
        when(gateway.current(ACCESS_ONE)).thenReturn(alice);
        when(gateway.refresh(REFRESH_ONE)).thenReturn(new ControlPlaneAuthSession(ACCESS_TWO, REFRESH_TWO, alice));
        when(gateway.quotaOverview(ACCESS_TWO)).thenReturn(overview(alice));
        var service = new SystemSessionService(gateway);
        service.login("alice@example.com", "synthetic-password");
        when(gateway.quotaOverview(ACCESS_ONE)).thenAnswer(call -> {
            service.current();
            throw ControlPlaneException.unauthorized();
        });

        assertThat(service.quotaOverview().user()).isEqualTo(alice);
        verify(gateway).refresh(REFRESH_ONE);
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
                null,
                null
        );
    }

    private static PersonalQuotaOverview overview(SystemUser user) {
        return new PersonalQuotaOverview(
                user,
                null,
                new QuotaPolicy(100_000L, 500_000L, "USER", "TEAM"),
                new QuotaUsage(
                        QuotaMeasurementStatus.CLIENT_REPORTED,
                        25_000L,
                        15_000L,
                        75_000L,
                        485_000L,
                        null,
                        null,
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
        private ControlPlaneException reportFailure;
        private int currentCalls;
        private int quotaOverviewCalls;
        private int reportCalls;
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
        public int reportUsage(String accessToken, List<CodexUsageEvent> events) {
            reportCalls++;
            lastAccessToken = accessToken;
            if (reportFailure != null) {
                throw reportFailure;
            }
            return events.size();
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
