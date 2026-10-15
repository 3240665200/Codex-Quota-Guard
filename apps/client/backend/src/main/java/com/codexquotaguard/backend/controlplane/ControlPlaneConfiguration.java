package com.codexquotaguard.backend.controlplane;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;
import java.nio.file.Path;

@Configuration(proxyBeanMethods = false)
public class ControlPlaneConfiguration {

    @Bean
    ControlPlaneGateway controlPlaneGateway(
            ObjectMapper objectMapper,
            @Value("${quota-guard.control-plane.base-url:http://127.0.0.1:8080}") String baseUrl,
            @Value("${quota-guard.meter.device-id-file:${user.home}/AppData/Roaming/com.codexquotaguard.desktop/meter-device-id.txt}") String deviceIdFile
    ) {
        return new HttpControlPlaneGateway(baseUrl, objectMapper, new ClientDeviceIdentity(Path.of(deviceIdFile)));
    }
}
