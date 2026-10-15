package com.codexquotaguard.backend.security;

import com.codexquotaguard.backend.QuotaGuardApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
    void ledgerAndRuntimeLogsRequireBothDesktopBearerAndProductLogin() throws Exception {
        for (String path : java.util.List.of("/api/v1/usage/events", "/api/v1/usage/logs")) {
            mockMvc.perform(get(path)).andExpect(status().isUnauthorized());
            mockMvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN))
                    .andExpect(status().isUnauthorized());
        }
        mockMvc.perform(get("/api/v1/usage/events?page=-1")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN))
                .andExpect(status().isUnauthorized());
    }

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

    @Test
    void doesNotExposeManagedCodexLoginCommands() throws Exception {
        mockMvc.perform(get("/api/v1/codex/session")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN))
                .andExpect(status().isNotFound());

        mockMvc.perform(post("/api/v1/codex/login")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN))
                .andExpect(status().isNotFound());
    }

    @Test
    void refusesMeterStatusAndManualScanningBeforeTheProductSessionAndBindingGate() throws Exception {
        mockMvc.perform(get("/api/v1/usage/status"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/usage/status")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("SYSTEM_SESSION_REQUIRED"));
        mockMvc.perform(post("/api/v1/usage/scan")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN))
                .andExpect(status().isUnauthorized());
    }
}
