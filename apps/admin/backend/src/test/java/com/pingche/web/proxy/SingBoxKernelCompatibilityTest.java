package com.pingche.web.proxy;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.ObjectMapper;

@EnabledIfEnvironmentVariable(named = "MANAGED_PROXY_TEST_KERNEL", matches = ".+")
class SingBoxKernelCompatibilityTest {
    @Test
    void installedKernelChecksEverySupportedProtocolWithoutStartingNetworking() throws Exception {
        Path kernel = Path.of(System.getenv("MANAGED_PROXY_TEST_KERNEL"));
        assertThat(Files.isRegularFile(kernel)).isTrue();
        ObjectMapper json = new ObjectMapper();
        ProxyNodeParser parser = new ProxyNodeParser(json);
        SingBoxConfiguration configuration = new SingBoxConfiguration(json);
        String id = "550e8400-e29b-41d4-a716-446655440000";
        String vmess = "{\"v\":\"2\",\"add\":\"proxy.example.com\",\"port\":443,\"id\":\"" + id
                + "\",\"aid\":0,\"net\":\"ws\",\"path\":\"/ws\",\"tls\":\"tls\"}";
        List<String> fixtures = List.of(
                "http://alice:synthetic-secret@proxy.example.com:8080",
                "https://alice:synthetic-secret@proxy.example.com:443",
                "socks5://alice:synthetic-secret@proxy.example.com:1080",
                "vless://" + id + "@proxy.example.com:443?security=tls&type=ws&path=%2Fws",
                "vmess://" + Base64.getEncoder().encodeToString(vmess.getBytes(StandardCharsets.UTF_8)),
                "trojan://synthetic-secret@proxy.example.com:443?security=tls&type=grpc&serviceName=Tunnel",
                "ss://aes-128-gcm:synthetic-secret@proxy.example.com:8388",
                "vless://" + id + "@proxy.example.com:443?security=reality&sni=tls.example.com&pbk=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA&sid=abcdef12&flow=xtls-rprx-vision");
        for (String fixture : fixtures) {
            ProxyNodeDefinition node = parser.parse(fixture);
            Process process = new ProcessBuilder(kernel.toString(), "check", "-c", "stdin")
                    .redirectErrorStream(true).start();
            try {
                try (var input = process.getOutputStream()) {
                    input.write(configuration.build(node, InetAddress.getByAddress(new byte[] {8, 8, 8, 8}), 19432)
                            .toString().getBytes(StandardCharsets.UTF_8));
                }
                assertThat(process.waitFor(10, TimeUnit.SECONDS)).as("kernel check deadline for %s", node.protocol()).isTrue();
                String output = new String(process.getInputStream().readNBytes(8192), StandardCharsets.UTF_8);
                assertThat(process.exitValue()).as("kernel accepts %s configuration: %s", node.protocol(), output).isZero();
            } finally { if (process.isAlive()) process.destroyForcibly(); }
        }
    }
}
