package com.pingche.web.proxy;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.pingche.web.auth.DomainException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class BrowserDirectGatewayTest {
    @Test
    void everyPurposeRequiresAnExplicitNodeWithoutReadingSecretsOrStartingTransport() {
        var nodes = mock(ProxyNodeRepository.class); var cipher = mock(ProxySecretCipher.class);
        var endpoints = mock(ProxyEndpointPolicy.class); var runner = mock(SingBoxProcessRunner.class);
        var gateway = new SingBoxManagedProxyGateway(nodes, cipher, endpoints, new SingBoxConfiguration(new ObjectMapper()), runner, false);
        for (var purpose : ManagedProxyGateway.Purpose.values())
            assertThatThrownBy(() -> gateway.open(null, purpose)).isInstanceOfSatisfying(DomainException.class,
                    error -> assertThat(error.getCode()).isEqualTo("PROXY_NODE_UNAVAILABLE"));
        verifyNoInteractions(nodes, cipher, endpoints, runner);
    }
}
