package com.pingche.web.quota;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.pingche.web.user.User;
import com.pingche.web.user.UserRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:usage-review;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"})
@ActiveProfiles("test")
@AutoConfigureMockMvc
class UsageReviewIntegrationTest {
    static final Instant NOW = Instant.parse("2026-10-26T10:00:00Z");
    @Autowired UsageReviewService reviews;
    @Autowired UserRepository users;
    @Autowired CodexUsageEventRepository events;
    @Autowired UsageModelPriceRepository prices;
    @Autowired UsageBillingService billing;
    @Autowired CodexUsageReportService reports;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired com.pingche.web.auth.JwtService jwt;
    @Autowired com.pingche.web.meter.MeterDeviceService devices;
    @MockitoBean Clock clock;
    User member;
    User admin;
    UUID device;
    @BeforeEach void setup() {
        when(clock.instant()).thenReturn(NOW);
        events.deleteAll(); users.deleteAll();
        member = users.saveAndFlush(new User("review@example.com", "fixture-hash", "Review member"));
        admin = users.saveAndFlush(User.bootstrapAdministrator("review-admin@example.com", "fixture-hash", "Review admin"));
        device = UUID.randomUUID();
        prices.saveAndFlush(new UsageModelPrice("review-fixture", new BigDecimal("2"), new BigDecimal("10"),
                new BigDecimal("0.2"), new BigDecimal("3"), NOW));
    }
    private CodexUsageReportRequest request(Instant at, char id, String tier) {
        return new CodexUsageReportRequest(List.of(new CodexUsageReportRequest.Event("codex-local-usage:v1:" + String.valueOf(id).repeat(64),
                at, 1500, CodexUsageReportRequest.Source.TOKEN_USAGE_RECORD, "review-fixture", "max",
                1000L, 200L, 100L, 500L, 200L, tier)));
    }
    private String id(char value) { return CodexUsageReportService.eventKey(member.getId(), "codex-local-usage:v1:" + String.valueOf(value).repeat(64)); }
    private String token(User user) { return "Bearer " + jwt.create(user.getId(), user.getRole().name(), user.getSessionVersion()); }

    @Test void receiptIsIdempotentImmutableAndDoesNotCreateCharges() {
        var request = request(NOW.minusSeconds(31 * 86400L), 'a', null);
        assertThat(reviews.receive(member.getId(), device, request)).containsExactly(request.events().get(0).eventId());
        reviews.receive(member.getId(), device, request(NOW, 'a', "fast"));
        var queue = reviews.list("PENDING", member.getId(), 0, 20);
        assertThat(queue.totalElements()).isEqualTo(1);
        assertThat(queue.items().get(0).event()).isEqualTo(request.events().get(0));
        assertThat(queue.items().get(0).reason()).isEqualTo("TOO_OLD");
        assertThat(events.count()).isZero();
        assertThat(jdbc.queryForObject("select count(*) from usage_billing_entries", Long.class)).isZero();
    }
    @Test void explicitImportKeepsOriginalTimeAndRecordsAdministratorAndDollarProvenance() {
        Instant old = NOW.minusSeconds(31 * 86400L);
        reviews.receive(member.getId(), device, request(old, 'a', "fast"));
        assertThat(reviews.review(List.of(id('a')), "IMPORT", "核对了原始日志", admin.getId())).isEqualTo(1);
        var stored = events.findById(id('a')).orElseThrow();
        assertThat(stored.getOccurredAt()).isEqualTo(old);
        assertThat(stored.getMeterDeviceId()).isEqualTo(device);
        var entry = billing.entries(List.of(id('a'))).get(id('a'));
        assertThat(entry.origin()).isEqualTo("ADMIN_REVIEW");
        assertThat(entry.costUsd()).isEqualByComparingTo("0.01011");
        var done = reviews.list("IMPORTED", null, 0, 20).items().get(0);
        assertThat(done.reviewedBy()).isEqualTo(admin.getId());
        assertThat(done.reviewNote()).isEqualTo("核对了原始日志");
        assertThatThrownBy(() -> reviews.review(List.of(id('a')), "IMPORT", "再次", admin.getId())).hasMessageContaining("已处理");
        assertThat(events.count()).isEqualTo(1);
    }
    @Test void archiveDoesNotBillAndLaterNormalReplayCannotBypassReview() {
        var request = request(NOW, 'a', "default");
        reviews.receive(member.getId(), device, request);
        reviews.review(List.of(id('a')), "ARCHIVE", "旧测试记录不计费", admin.getId());
        reports.report(member.getId(), device, request);
        reviews.receive(member.getId(), device, request);
        assertThat(events.count()).isZero();
        assertThat(reviews.list("ARCHIVED", null, 0, 20).totalElements()).isEqualTo(1);
    }
    @Test void aFutureTimestampRollsBackTheWholeBatchWithoutRewritingTime() {
        reviews.receive(member.getId(), device, request(NOW.minusSeconds(60), 'a', "default"));
        reviews.receive(member.getId(), device, request(NOW.plusSeconds(86400), 'b', "fast"));
        assertThatThrownBy(() -> reviews.review(List.of(id('a'), id('b')), "IMPORT", "核对", admin.getId())).hasMessageContaining("未来时间");
        assertThat(events.count()).isZero();
        assertThat(reviews.list("PENDING", null, 0, 20).totalElements()).isEqualTo(2);
    }
    @Test void missingModeStaysPendingUntilExplicitConfirmationAndIsVisibleInTheBillingTab() {
        reviews.receive(member.getId(), device, request(NOW.minusSeconds(60), 'a', null));
        reviews.review(List.of(id('a')), "IMPORT", "日志时间确认", admin.getId());
        var pending = reviews.pendingBilling(member.getId(), 0, 20);
        assertThat(pending.items().get(0).costStatus()).isEqualTo("MISSING_SERVICE_TIER");
        assertThat(billing.confirmMode(List.of(id('a')), "standard", admin.getId())).isEqualTo(1);
        assertThat(reviews.pendingBilling(null, 0, 20).totalElements()).isZero();
    }
    @Test void reviewReceiptsAndAdministratorActionsUseSeparateAuthenticationAndValidateInputs() throws Exception {
        var body = json.writeValueAsString(request(NOW.minusSeconds(31 * 86400L), 'a', "default"));
        mvc.perform(post("/api/v1/meter/usage/reviews").header("Authorization", token(member)).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());
        var grant = devices.enroll(member.getId(), device);
        mvc.perform(post("/api/v1/meter/usage/reviews").header("X-Quota-Guard-Meter", grant.deviceToken()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.quarantinedEventIds[0]").value(request(NOW, 'a', "default").events().get(0).eventId()));
        mvc.perform(get("/api/v1/admin/usage/reviews").header("Authorization", token(member))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/usage/reviews").header("Authorization", token(admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1));
        mvc.perform(get("/api/v1/admin/usage/reviews?size=10000").header("Authorization", token(admin))).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/admin/usage/reviews/actions").header("Authorization", token(admin)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"ids\":[\""+id('a')+"\"],\"action\":\"ARCHIVE\",\"note\":\" \"}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/meter/usage/reviews").header("X-Quota-Guard-Meter", grant.deviceToken()).contentType(MediaType.APPLICATION_JSON).content("{\"events\":[null]}"))
                .andExpect(status().isBadRequest());
    }
    @Test void normalDuplicateImportDoesNotChargeTwiceAndQuotasStayUnchanged() {
        jdbc.update("update users set weekly_usd_limit=667 where id=?", member.getId());
        var request = request(NOW.minusSeconds(10), 'a', "default");
        reports.report(member.getId(), device, request);
        reviews.receive(member.getId(), device, request);
        reviews.review(List.of(id('a')), "IMPORT", "确认重复收件", admin.getId());
        assertThat(events.count()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select weekly_usd_limit from users where id=?", BigDecimal.class, member.getId())).isEqualByComparingTo("667");
        assertThat(billing.entries(List.of(id('a'))).get(id('a')).origin()).isEqualTo("LIVE");
    }
}
