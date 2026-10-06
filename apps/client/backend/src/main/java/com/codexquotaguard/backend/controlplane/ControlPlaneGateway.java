package com.codexquotaguard.backend.controlplane;

/**
 * Fixed, minimal boundary to the central Java backend.
 *
 * <p>The desktop UI cannot select a URL or pass a bearer token through this
 * boundary. The implementation uses only the product API endpoints declared
 * here.</p>
 */
public interface ControlPlaneGateway {

    ControlPlaneAuthSession login(String email, String password);

    ControlPlaneAuthSession refresh(String refreshToken);

    SystemUser current(String accessToken);

    PersonalQuotaOverview quotaOverview(String accessToken);

    void logout(String accessToken, String refreshToken);
}
