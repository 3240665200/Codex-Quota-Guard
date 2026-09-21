package com.codexquotaguard.backend.security;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class LocalApiSecurityConfigurationTest {

    private static final String TOKEN = "pJ8ybk_mqMDtQ4Qixl2f5rwcuKeB0VLtyQT6N48S9jc";

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(LocalApiSecurityConfiguration.class);

    @Test
    void refusesToCreateTheSecurityConfigurationWithoutAnEphemeralToken() {
        contextRunner.run(context ->
                assertThat(context).hasFailed()
                        .getFailure().hasRootCauseInstanceOf(IllegalArgumentException.class));
    }

    @Test
    void createsTheVerifierWhenQuotaGuardTokenPropertyIsPresent() {
        contextRunner.withPropertyValues("quota-guard.api-token=" + TOKEN).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(ApiTokenVerifier.class);
        });
    }
}
