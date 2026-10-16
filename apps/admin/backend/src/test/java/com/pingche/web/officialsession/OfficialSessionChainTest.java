package com.pingche.web.officialsession;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.pingche.web.auth.DomainException;
import com.pingche.web.proxy.ManagedProxyGateway;
import java.net.http.HttpClient;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.ObjectMapper;

class OfficialSessionChainTest {
    @Test void readsAndAllMutationsKeepTheFrontAndNeverRetryTheExitDirectly() {
        UUID front = UUID.randomUUID(), exit = UUID.randomUUID();
        ManagedProxyGateway gateway = mock(ManagedProxyGateway.class);
        HttpClient http = mock(HttpClient.class);
        when(gateway.open(front, exit, ManagedProxyGateway.Purpose.OFFICIAL_SESSION))
                .thenThrow(new DomainException("PROXY_NODE_DISABLED", "unavailable", HttpStatus.SERVICE_UNAVAILABLE));
        var client = new OfficialSessionClient(new ObjectMapper(), gateway, ignored -> http);
        var credentials = new OfficialSessionCredentials("Bearer synthetic", null, "synthetic-account");
        assertThatThrownBy(() -> client.fetch(credentials, front, exit)).isInstanceOf(DomainException.class);
        assertThat(client.logoutAll(credentials, front, exit).errorCode()).isEqualTo("PROXY_NODE_DISABLED");
        assertThat(client.revokeSession(credentials, front, exit, "session-one").errorCode()).isEqualTo("PROXY_NODE_DISABLED");
        verify(gateway, times(3)).open(front, exit, ManagedProxyGateway.Purpose.OFFICIAL_SESSION);
        verify(gateway, never()).open(exit);
        verifyNoInteractions(http);
    }

    @Test void requestPreservesIndependentFrontAndExitWhileLegacyConstructorsRemainSingleNode() {
        UUID front = UUID.randomUUID(), exit = UUID.randomUUID();
        var request = new OfficialConnectionRequest("Bearer synthetic", null, "account", exit, 0L, front);
        assertThat(request.frontProxyNodeId()).isEqualTo(front);
        assertThat(new OfficialConnectionRequest("Bearer synthetic", null, "account", exit, 0L).frontProxyNodeId()).isNull();
        var preset = new OfficialAuthorizationPresetRequest(0L, exit, "a".repeat(64), front);
        assertThat(preset.frontProxyNodeId()).isEqualTo(front);
    }
}
