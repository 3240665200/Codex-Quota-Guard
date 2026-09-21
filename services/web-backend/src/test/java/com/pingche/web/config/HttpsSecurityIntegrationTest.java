package com.pingche.web.config;

import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "app.require-https=true")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class HttpsSecurityIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void productionModeRedirectsHttpAndAcceptsForwardedHttps() throws Exception {
        mvc.perform(get("/api/v1/users/me"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string(
                        HttpHeaders.LOCATION,
                        startsWith("https://")
                ));

        mvc.perform(get("/api/v1/users/me").with(request -> {
                    request.setSecure(true);
                    request.setScheme("https");
                    request.setServerPort(443);
                    return request;
                }))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
    }
}
