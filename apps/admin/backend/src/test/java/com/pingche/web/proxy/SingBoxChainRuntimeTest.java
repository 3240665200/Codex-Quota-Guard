package com.pingche.web.proxy;

import java.net.InetAddress;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.ObjectMapper;

@EnabledIfEnvironmentVariable(named = "MANAGED_PROXY_TEST_KERNEL", matches = ".+")
class SingBoxChainRuntimeTest {
    @Test void realKernelUsesBothHopsAndNeverFallsBackToExit() throws Exception {
        ProxyNodeParser parser = new ProxyNodeParser(new ObjectMapper());
        SingBoxConfiguration configuration = new SingBoxConfiguration(new ObjectMapper());
        InetAddress loopback = InetAddress.getByName("127.0.0.1");
        SingBoxChainRuntimeHarness.verify(System.getenv("MANAGED_PROXY_TEST_KERNEL"), (front, exit, local) ->
                configuration.build(parser.parse("socks5://127.0.0.1:" + front), loopback,
                        parser.parse("socks5://127.0.0.1:" + exit), loopback, local,
                        ManagedProxyGateway.Purpose.NODE_TEST).toString());
    }
}
