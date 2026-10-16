package com.codexquotaguard.backend.system;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.codexquotaguard.backend.codex.CodexCcSwitchSessionConflictException;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

class CodexConflictResponseTest {
    @RestController
    static class SyntheticController {
        @GetMapping("/synthetic-conflict") public void read() { throw new CodexCcSwitchSessionConflictException(); }
    }

    @Test void sendsOnlyFixedConflictCodeWith409() throws Exception {
        MockMvcBuilders.standaloneSetup(new SyntheticController())
                .setControllerAdvice(new LocalSessionExceptionHandler()).build()
                .perform(get("/synthetic-conflict"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CODEX_CC_SWITCH_SESSION_CONFLICT"))
                .andExpect(jsonPath("$.message").isString());
    }
}
