package com.codexquotaguard.backend.officiallogin;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class OfficialLoginControllerTest {
    @Test void exposesOnlySafeStatusAndFixedErrors() throws Exception {
        var service=mock(OfficialLoginService.class);
        when(service.status()).thenReturn(new OfficialLoginService.Status(null,"IDLE",null,null));
        when(service.start(any(),any())).thenThrow(new OfficialLoginException("OFFICIAL_LOGIN_ADMIN_REQUIRED"));
        var mvc=MockMvcBuilders.standaloneSetup(new OfficialLoginController(service)).setControllerAdvice(new OfficialLoginExceptionHandler()).build();
        mvc.perform(get("/api/v1/official-login-helper/status")).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
                .andExpect(jsonPath("$.state").value("IDLE")).andExpect(jsonPath("$.authorization").doesNotExist());
        mvc.perform(post("/api/v1/official-login-helper/jobs/"+UUID.randomUUID()+"/start").contentType("application/json").content("{\"browser\":\"EDGE\"}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("OFFICIAL_LOGIN_ADMIN_REQUIRED"));
        mvc.perform(post("/api/v1/official-login-helper/jobs/"+UUID.randomUUID()+"/start").contentType("application/json").content("{\"browser\":\"secret-invalid-choice\"}"))
                .andExpect(status().isBadRequest()).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("secret-invalid-choice"))));
    }
}
