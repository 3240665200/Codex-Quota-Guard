package com.pingche.web.proxy;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

import com.pingche.web.auth.DomainException;
import java.net.InetAddress;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.function.IntFunction;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class ProxyChainGatewayTest {
    private final ObjectMapper json = new ObjectMapper();
    private final ProxyNodeRepository nodes = mock(ProxyNodeRepository.class);
    private final ProxySecretCipher cipher = mock(ProxySecretCipher.class);
    private final ProxyEndpointPolicy policy = mock(ProxyEndpointPolicy.class);
    private final SingBoxProcessRunner runner = mock(SingBoxProcessRunner.class);
    private final SingBoxManagedProxyGateway gateway = new SingBoxManagedProxyGateway(nodes, cipher, policy,
            new SingBoxConfiguration(json), runner, false);

    @Test void pinsBothAddressesBeforeTheKernelAndPreservesTheRequestedOrder() throws Exception {
        UUID front=insert("front.example.com",true), exit=insert("exit.example.com",true);
        when(policy.resolve("front.example.com",false)).thenReturn(InetAddress.getByName("8.8.8.8"));
        when(policy.resolve("exit.example.com",false)).thenReturn(InetAddress.getByName("1.1.1.1"));
        when(runner.open(any(),eq(ManagedProxyGateway.Purpose.OFFICIAL_SESSION))).thenAnswer(call -> {
            IntFunction<JsonNode> factory=call.getArgument(0);
            JsonNode config=factory.apply(12345);
            assertThat(config.path("outbounds").get(0).path("server").asString()).isEqualTo("8.8.8.8");
            assertThat(config.path("outbounds").get(1).path("server").asString()).isEqualTo("1.1.1.1");
            return mock(ProxyLease.class);
        });
        assertThat(gateway.open(front,exit,ManagedProxyGateway.Purpose.OFFICIAL_SESSION)).isNotNull();
        var ordered=inOrder(policy,runner);
        ordered.verify(policy).resolve("front.example.com",false);
        ordered.verify(policy).resolve("exit.example.com",false);
        ordered.verify(runner).open(any(),eq(ManagedProxyGateway.Purpose.OFFICIAL_SESSION));
    }

    @Test void missingDisabledDuplicateAndUnsafeHopsFailBeforeTheKernel() throws Exception {
        UUID front=insert("front.example.com",false), exit=insert("exit.example.com",true);
        assertCode(() -> gateway.open(front,exit,ManagedProxyGateway.Purpose.NODE_TEST),"PROXY_NODE_DISABLED");
        assertCode(() -> gateway.open(exit,exit,ManagedProxyGateway.Purpose.NODE_TEST),"PROXY_CHAIN_INVALID");
        assertCode(() -> gateway.open(UUID.randomUUID(),exit,ManagedProxyGateway.Purpose.NODE_TEST),"PROXY_NODE_UNAVAILABLE");
        UUID safeFront=insert("safe.example.com",true);
        when(policy.resolve("safe.example.com",false)).thenReturn(InetAddress.getByName("8.8.8.8"));
        when(policy.resolve("exit.example.com",false)).thenThrow(new DomainException("PROXY_ENDPOINT_BLOCKED","blocked",HttpStatus.BAD_REQUEST));
        assertCode(() -> gateway.open(safeFront,exit,ManagedProxyGateway.Purpose.NODE_TEST),"PROXY_ENDPOINT_BLOCKED");
        verifyNoInteractions(runner);
    }

    private UUID insert(String host,boolean enabled) {
        UUID id=UUID.randomUUID();
        var definition=new ProxyNodeParser(json).parse("https://alice:synthetic@"+host+":8443");
        var node=new ProxyNode(id,host,definition,"cipher-"+id,enabled,Instant.EPOCH);
        when(nodes.findById(id)).thenReturn(Optional.of(node));
        when(cipher.decrypt("cipher-"+id,id.toString())).thenReturn(definition.outboundJson());
        return id;
    }
    private void assertCode(Runnable action,String code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(DomainException.class,error -> assertThat(error.getCode()).isEqualTo(code));
    }
}
