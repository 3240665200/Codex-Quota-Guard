package com.codexquotaguard.backend.security;

import com.codexquotaguard.backend.QuotaGuardApplication;
import com.codexquotaguard.backend.controlplane.ClientAccess;
import com.codexquotaguard.backend.system.ControlPlaneUnavailableException;
import com.codexquotaguard.backend.system.SystemSessionService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes = QuotaGuardApplication.class, properties = {
    "quota-guard.api-token=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", "quota-guard.codex.usage-scan-initial-delay-ms=3600000"
})
@AutoConfigureMockMvc
class ClientBindingGateIntegrationTest {
    @Autowired MockMvc mvc;
    @MockitoBean SystemSessionService sessions;
    @MockitoBean com.codexquotaguard.backend.system.LoginAssistanceEventBridge events;
    private static final String AUTH = "Bearer AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";

    @Test void directLocalBusinessRequestsRequireAFreshCentralBindingDecision() throws Exception {
        when(sessions.clientAccess()).thenReturn(new ClientAccess(false, null, "SESSION_BINDING_REQUIRED"));
        for (String path : java.util.List.of("/api/v1/quota/overview", "/api/v1/usage/status", "/api/v1/usage/logs",
                "/api/v1/usage/events", "/api/v1/usage/analytics", "/api/v1/codex/rate-limits")) {
            mvc.perform(get(path).header("Authorization", AUTH)).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("SESSION_BINDING_REQUIRED"));
        }
        mvc.perform(post("/api/v1/usage/scan").header("Authorization", AUTH)).andExpect(status().isForbidden());
        verify(sessions, times(7)).clientAccess();
        verify(sessions, never()).quotaOverview();
    }

    @Test void gateFailsClosedOnOutageAndDoesNotCacheAPreviousGrant() throws Exception {
        when(sessions.clientAccess()).thenReturn(new ClientAccess(true, UUID.randomUUID(), null))
                .thenThrow(ControlPlaneUnavailableException.class);
        mvc.perform(get("/api/v1/usage/status").header("Authorization", AUTH)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/usage/status").header("Authorization", AUTH)).andExpect(status().isServiceUnavailable());
        verify(sessions, times(2)).clientAccess();
    }

    @Test void localTokenAndAllowedAssistanceEndpointsRemainIndependent() throws Exception {
        mvc.perform(get("/api/v1/usage/status")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/login-assistance").header("Authorization", AUTH)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/status").header("Authorization", AUTH)).andExpect(status().isOk());
        verify(sessions, never()).clientAccess();
    }

    @Test void restoredMailAndAsyncNotificationsKeepLocalAuthenticationWithoutRequiringBinding() throws Exception {
        mvc.perform(get("/api/v1/login-assistance/events")).andExpect(status().isUnauthorized());
        verify(events,never()).subscribe();
        when(sessions.currentLoginAssistance()).thenReturn(new com.codexquotaguard.backend.controlplane.LoginAssistanceCurrent(null));
        mvc.perform(get("/api/v1/login-assistance/listeners/current").header("Authorization",AUTH))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
                .andExpect(jsonPath("$.listener").isEmpty());
        var emitter=new org.springframework.web.servlet.mvc.method.annotation.SseEmitter(5000L);
        when(events.subscribe()).thenReturn(emitter);
        var request=mvc.perform(get("/api/v1/login-assistance/events").header("Authorization",AUTH))
                .andExpect(status().isOk()).andExpect(request().asyncStarted())
                .andExpect(header().string("X-Accel-Buffering","no"))
                .andExpect(header().string("Cache-Control","no-store")).andReturn();
        emitter.send(org.springframework.web.servlet.mvc.method.annotation.SseEmitter.event().name("connected").data("{}"));
        org.assertj.core.api.Assertions.assertThat(request.getResponse().getContentAsString()).contains("event:connected","data:{}");
        emitter.complete();
        mvc.perform(asyncDispatch(request)).andExpect(status().isOk());
        verify(sessions,never()).clientAccess();
        verify(sessions,never()).cancelLoginAssistance(any());
    }
}
