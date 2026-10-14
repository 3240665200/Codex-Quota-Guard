package com.pingche.web.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class ManagementTransportGuardTest {
    @Test
    void defaultRequiresHttpsEvenForLoopbackAndHttpsBehaviorIsUnchanged() {
        ManagementTransportGuard guard = new ManagementTransportGuard(false);
        MockHttpServletRequest request = request("127.0.0.1", "127.0.0.1", "localhost");
        assertThat(guard.isAllowed(request)).isFalse();
        request.setSecure(true);
        assertThat(guard.isAllowed(request)).isTrue();
        request.setRemoteAddr("192.0.2.10");
        request.setLocalAddr("192.0.2.20");
        request.setServerName("admin.example.com");
        assertThat(guard.isAllowed(request)).isTrue();
    }

    @Test
    void optInPermitsOnlyLiteralLoopbackPeersAndLoopbackHosts() {
        ManagementTransportGuard guard = new ManagementTransportGuard(true);
        String[] addresses = {"127.0.0.1", "::1", "0:0:0:0:0:0:0:1", "::ffff:127.0.0.1",
                "0:0:0:0:0:ffff:127.0.0.1", "::ffff:7f00:1", "0:0:0:0:0:ffff:7f00:1"};
        for (String remote : addresses) {
            for (String local : addresses) {
                for (String host : new String[] {"localhost", "127.0.0.1", "[::1]"})
                    assertThat(guard.isAllowed(request(remote, local, host))).as("%s / %s / %s", remote, local, host).isTrue();
            }
        }
    }

    @Test
    void optInStillRejectsExternalPeersBindingsAndHostnamesWithoutDnsResolution() {
        ManagementTransportGuard guard = new ManagementTransportGuard(true);
        for (String address : new String[] {"192.0.2.1", "0.0.0.0", "::", "localhost", "127.0.0.2", "127.1",
                "::ffff:127.0.0.2", "::ffff:c000:201", "::1%lo0", " 127.0.0.1", "127.0.0.1 "}) {
            assertThat(guard.isAllowed(request(address, "127.0.0.1", "localhost"))).as("remote %s", address).isFalse();
            assertThat(guard.isAllowed(request("127.0.0.1", address, "localhost"))).as("local %s", address).isFalse();
        }
        for (String host : new String[] {"admin.example.com", "localhost.evil.test", "localhost.", "127.0.0.1.evil.test", "[::ffff:127.0.0.1]"})
            assertThat(guard.isAllowed(request("127.0.0.1", "127.0.0.1", host))).as("host %s", host).isFalse();
    }

    @Test
    void forwardedHeadersCannotTurnHttpIntoAllowedHttpsOrOverridePeerChecks() {
        ManagementTransportGuard guard = new ManagementTransportGuard(true);
        for (String header : new String[] {"Forwarded", "X-Forwarded-Proto"}) {
            MockHttpServletRequest external = request("192.0.2.1", "127.0.0.1", "localhost");
            external.addHeader(header, header.equals("Forwarded") ? "for=127.0.0.1;proto=https;host=localhost" : "https");
            assertThat(guard.isAllowed(external)).isFalse();
            MockHttpServletRequest local = request("127.0.0.1", "127.0.0.1", "localhost");
            local.addHeader(header, header.equals("Forwarded") ? "proto=https" : "https");
            assertThat(guard.isAllowed(local)).isFalse();
        }
    }

    private MockHttpServletRequest request(String remote, String local, String host) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setSecure(false);
        request.setScheme("http");
        request.setRemoteAddr(remote);
        request.setLocalAddr(local);
        request.setServerName(host);
        return request;
    }
}
