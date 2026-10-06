package com.codexquotaguard.backend.security;

import com.codexquotaguard.backend.QuotaGuardApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
        classes = QuotaGuardApplication.class,
        properties = "quota-guard.api-token=pJ8ybk_mqMDtQ4Qixl2f5rwcuKeB0VLtyQT6N48S9jc"
)
@AutoConfigureMockMvc
class LocalApiSecurityIntegrationTest {

    private static final String TOKEN = "pJ8ybk_mqMDtQ4Qixl2f5rwcuKeB0VLtyQT6N48S9jc";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void onlyServesTheStatusEndpointToTheDesktopProcessWithItsBearerToken() throws Exception {
        mockMvc.perform(get("/api/v1/status"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/v1/status")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.service").value("codex-quota-guard"));
    }

    @Test
    void exposesOnlyASignedOutMetadataEnvelopeBeforeSystemLogin() throws Exception {
        mockMvc.perform(get("/api/v1/session/current")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated").value(false))
                .andExpect(jsonPath("$.user").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.accessToken").doesNotExist())
                .andExpect(jsonPath("$.refreshToken").doesNotExist())
                .andExpect(jsonPath("$.codexAccount").doesNotExist());
    }

    @Test
    void protectsTheFixedQuotaOverviewWithBothTheDesktopBearerAndASystemSession() throws Exception {
        mockMvc.perform(get("/api/v1/quota/overview"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/v1/quota/overview")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN))
                .andExpect(status().isUnauthorized());

    }
}
