package com.pingche.web.officialsession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class OfficialQuotaPayloadTest {
    private final ObjectMapper mapper = new ObjectMapper();
    @Test void supportsWeeklyPrimaryAndAdditionalLimitsWithoutAssumingSlotDuration() {
        var result = OfficialQuotaPayload.parse(mapper.readTree("""
            {"account_id":"account","user_id":"user","plan_type":"team",
             "rate_limit":{"primary_window":{"used_percent":40,"limit_window_seconds":604800,"reset_at":2000000000},"secondary_window":null},
             "additional_rate_limits":[{"metered_feature":"code_review","rate_limit":{"primary_window":{"used_percent":100,"limit_window_seconds":3600}}}]}
            """), "account", "user", Instant.EPOCH);
        assertThat(result.windows()).hasSize(2);
        assertThat(result.windows().get(0).windowDurationMins()).isEqualTo(10080);
        assertThat(result.windows().get(1).limitId()).isEqualTo("code_review");
        assertThat(result.windows().get(1).resetsAt()).isNull();
    }
    @Test void missingWindowsAreUnknownAndMalformedOrWrongAccountResponsesFailClosed() {
        assertThat(OfficialQuotaPayload.parse(mapper.readTree("{\"plan_type\":\"free\",\"rate_limit\":null}"), "a", "u", Instant.EPOCH).windows()).isEmpty();
        for (String json : new String[]{"{}", "{\"plan_type\":\"plus\"}",
                "{\"plan_type\":\"plus\",\"account_id\":\"other\",\"rate_limit\":null}",
                "{\"plan_type\":\"plus\",\"rate_limit\":{\"primary_window\":{\"used_percent\":-1,\"limit_window_seconds\":18000}}}",
                "{\"plan_type\":\"plus\",\"rate_limit\":{\"primary_window\":{\"used_percent\":\"0\",\"limit_window_seconds\":18000}}}"})
            assertThatThrownBy(() -> OfficialQuotaPayload.parse(mapper.readTree(json), "a", "u", Instant.EPOCH)).isInstanceOf(com.pingche.web.auth.DomainException.class);
    }
}
