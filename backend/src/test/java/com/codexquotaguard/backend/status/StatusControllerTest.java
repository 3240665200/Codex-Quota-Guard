package com.codexquotaguard.backend.status;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StatusControllerTest {

    @Test
    void reportsThatTheLocalServiceIsOnline() {
        var response = new StatusController().status();

        assertThat(response.service()).isEqualTo("codex-quota-guard");
        assertThat(response.state()).isEqualTo("online");
    }
}
