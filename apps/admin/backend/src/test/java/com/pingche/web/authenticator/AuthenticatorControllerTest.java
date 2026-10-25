package com.pingche.web.authenticator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.pingche.web.common.ManagementTransportGuard;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.Authentication;

class AuthenticatorControllerTest {
    @Test void previewRejectsDisallowedTransportBeforeReadingAnySecret() {
        var service = mock(AuthenticatorService.class);
        var transport = mock(ManagementTransportGuard.class);
        var http = new MockHttpServletRequest();
        var controller = new AuthenticatorController(service, transport);
        assertThatThrownBy(() -> controller.preview(UUID.randomUUID(), mock(Authentication.class), http))
                .hasMessageContaining("传输方式不被服务器允许");
        verifyNoInteractions(service);
    }

    @Test void previewIsNotCacheableAndUsesTheAuthenticatedAdministrator() {
        var service = mock(AuthenticatorService.class);
        var transport = mock(ManagementTransportGuard.class);
        var http = new MockHttpServletRequest();
        var auth = mock(Authentication.class);
        UUID accountId = UUID.randomUUID(), administratorId = UUID.randomUUID();
        when(transport.isAllowed(http)).thenReturn(true);
        when(auth.getPrincipal()).thenReturn(administratorId);
        var code = new AuthenticatorService.Code("123456", Instant.EPOCH, Instant.EPOCH.plusSeconds(30), 30, accountId);
        when(service.preview(accountId, administratorId)).thenReturn(code);
        var response = new AuthenticatorController(service, transport).preview(accountId, auth, http);
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
        assertThat(response.getHeaders().getFirst("Pragma")).isEqualTo("no-cache");
        assertThat(response.getBody()).isEqualTo(code);
        verify(service).preview(accountId, administratorId);
    }
}
