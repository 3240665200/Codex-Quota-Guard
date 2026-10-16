package com.pingche.web.officialsession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.pingche.web.auth.DomainException;
import com.pingche.web.proxy.ManagedProxyGateway;
import java.net.http.HttpClient;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class OfficialSessionProxyRequiredTest {
    @Test
    void missingProxyBlocksReadsAndWritesBeforeCreatingAnyHttpClient() {
        HttpClient http = mock(HttpClient.class);
        ManagedProxyGateway gateway = mock(ManagedProxyGateway.class);
        OfficialSessionClient client = new OfficialSessionClient(new ObjectMapper(), gateway, selector -> http);
        var credentials = new OfficialSessionCredentials("Bearer synthetic-token", null, "synthetic-account");
        assertThatThrownBy(() -> client.fetch(credentials, null)).isInstanceOfSatisfying(DomainException.class,
                error -> assertThat(error.getCode()).isEqualTo("OFFICIAL_PROXY_REQUIRED"));
        assertThat(client.logoutAll(credentials, null).status()).isEqualTo("REJECTED");
        assertThat(client.revokeSession(credentials, null, "session-one").status()).isEqualTo("REJECTED");
        verifyNoInteractions(http, gateway);
    }
}
