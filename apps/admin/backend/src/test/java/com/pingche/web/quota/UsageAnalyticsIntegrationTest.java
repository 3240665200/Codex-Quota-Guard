package com.pingche.web.quota;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pingche.web.auth.AuthService;
import com.pingche.web.sessionbinding.ClientBindingFixture;
import com.pingche.web.team.Team;
import com.pingche.web.team.TeamRepository;
import com.pingche.web.user.User;
import com.pingche.web.user.UserRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class UsageAnalyticsIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired AuthService auth;
    @Autowired UserRepository users;
    @Autowired TeamRepository teams;
    @Autowired CodexUsageEventRepository events;
    @Autowired UsageModelPriceRepository prices;
    @Autowired UsageAnalyticsService analytics;
    @Autowired Clock clock;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void clearBindingPrerequisites() { ClientBindingFixture.clearBindings(jdbc); }

    @Test
    void importsCcSwitchPriceSnapshotAndLabelsManualOverrides() throws Exception {
        var seeded = prices.findById("gpt-6.1-sol").orElseThrow();
        assertThat(seeded.getInputPrice()).isEqualByComparingTo("2");
        assertThat(seeded.getOutputPrice()).isEqualByComparingTo("10");
        assertThat(seeded.getCacheReadPrice()).isEqualByComparingTo("0.10");
        assertThat(seeded.getCacheWritePrice()).isEqualByComparingTo("2.50");
        assertThat(seeded.getPriceSource()).isEqualTo("CC_SWITCH");
        assertThat(seeded.getSourceReference()).contains("eda410e57b4659d6bbb2a10a069d8bac2213099a");
        assertThat(seeded.getSourceCheckedAt()).hasToString("2026-10-10");

        String model = "gpt-6.1-sol";
        mvc.perform(put("/api/v1/admin/usage/model-prices").contentType("application/json")
                        .content("""
                                {"model":"%s","inputPrice":3,"outputPrice":11,"cacheReadPrice":0,"cacheWritePrice":0}
                                """.formatted(model))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.priceSource").value("MANUAL"))
                .andExpect(jsonPath("$.sourceReference").isEmpty())
                .andExpect(jsonPath("$.sourceCheckedAt").isEmpty());
        assertThat(prices.findById(model).orElseThrow().getInputPrice()).isEqualByComparingTo("3");
    }

    @Test
    void ingestsBreakdownKeepsOldClientsCompatibleAndAggregatesUsdWithFilters() throws Exception {
        var member = auth.register("usage-" + UUID.randomUUID() + "@example.com", "long-test-password", "Usage User");
        var user = users.findById(member.user().id()).orElseThrow();
        Team team = teams.saveAndFlush(new Team("Usage " + UUID.randomUUID(), "test", Team.Status.ACTIVE, 100, 100, Instant.now()));
        user.updateQuotaPolicy(team.getId(), null, null, Instant.now());
        users.saveAndFlush(user);
        String memberToken = auth.login(user.getEmail(), "long-test-password").accessToken();
        String adminToken = adminToken();
        String model = "test-model-" + UUID.randomUUID();
        mvc.perform(put("/api/v1/admin/usage/model-prices").contentType("application/json")
                        .content("""
                                {"model":"%s","inputPrice":2,"outputPrice":8,"cacheReadPrice":0.5,"cacheWritePrice":3}
                                """.formatted(model)).header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.currency").value("USD"));
        String body = """
                {"events":[{"eventId":"codex-local-usage:v1:%s","occurredAt":"%s",
                "totalTokens":1200,"source":"TOKEN_USAGE_RECORD","model":"%s","reasoningEffort":"high",
                "inputTokens":1000,"cachedInputTokens":300,"cacheWriteInputTokens":100,
                "outputTokens":200,"reasoningOutputTokens":150}]}
                """.formatted(UUID.randomUUID().toString().replace("-", "").repeat(2), Instant.now().minusSeconds(60), model);
        for (int attempt = 0; attempt < 2; attempt++) {
            mvc.perform(post("/api/v1/client/usage/events").contentType("application/json").content(body)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + memberToken)).andExpect(status().isOk());
        }
        assertThat(events.sumTokensSince(user.getId(), Instant.EPOCH, clock.instant(), Instant.EPOCH)).isEqualTo(1200);
        assertThat(events.sumTeamTokensSince(team.getId(), Instant.EPOCH, clock.instant(), Instant.EPOCH)).isEqualTo(1200);
        mvc.perform(get("/api/v1/client/usage/events?includeMetadata=true")
                        .with(ClientBindingFixture.forUser(jdbc, user.getId()))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + memberToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].inputTokens").doesNotExist())
                .andExpect(jsonPath("$.items[0].estimatedCostUsd").doesNotExist())
                .andExpect(jsonPath("$.items[0].model").value(model));
        mvc.perform(get("/api/v1/client/usage/events?includeTokenBreakdown=true")
                        .with(ClientBindingFixture.forUser(jdbc, user.getId()))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + memberToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].inputTokens").value(1000))
                .andExpect(jsonPath("$.items[0].reasoningOutputTokens").value(150))
                .andExpect(jsonPath("$.items[0].estimatedCostUsd").value(0.00325))
                .andExpect(jsonPath("$.totalElements").value(1));
        for (String granularity : new String[] {"HOUR", "DAY"}) {
            mvc.perform(get("/api/v1/admin/usage/analytics").param("model", model).param("userId", user.getId().toString())
                            .param("teamId", team.getId().toString()).param("granularity", granularity)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.currency").value("USD"))
                    .andExpect(jsonPath("$.summary.eventCount").value(1))
                    .andExpect(jsonPath("$.summary.totalTokens").value(1200))
                    .andExpect(jsonPath("$.summary.estimatedCostUsd").value(0.00325))
                    .andExpect(jsonPath("$.summary.cacheHitRatePercent").value(30))
                    .andExpect(jsonPath("$.trend[0].stats.totalTokens").value(1200))
                    .andExpect(jsonPath("$.models[0].label").value(model))
                    .andExpect(jsonPath("$.users[0].label").value("Usage User"))
                    .andExpect(jsonPath("$.teams[0].label").value(team.getName()))
                    .andExpect(jsonPath("$.events.items[0].estimatedCostUsd").value(0.00325));
        }
        mvc.perform(get("/api/v1/admin/usage/analytics").param("model", model).param("reasoningEffort", "low")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.summary.eventCount").value(0))
                .andExpect(jsonPath("$.summary.estimatedCostUsd").isEmpty());
        mvc.perform(get("/api/v1/admin/usage/options").header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.models").isArray());
        mvc.perform(delete("/api/v1/admin/usage/model-prices").param("model", model)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/admin/usage/analytics").param("model", model)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.summary.costedEventCount").value(0))
                .andExpect(jsonPath("$.events.items[0].costStatus").value("MISSING_PRICE"));
    }

    @Test
    void rejectsUnprivilegedAccessInvalidPriceAndUnboundedQueries() throws Exception {
        String memberToken = auth.register("usage-perm-" + UUID.randomUUID() + "@example.com",
                "long-test-password", "Member").accessToken();
        String adminToken = adminToken();
        for (String path : new String[] {"/analytics", "/options", "/model-prices"}) {
            mvc.perform(get("/api/v1/admin/usage" + path)).andExpect(status().isUnauthorized());
            mvc.perform(get("/api/v1/admin/usage" + path).header(HttpHeaders.AUTHORIZATION, "Bearer " + memberToken))
                    .andExpect(status().isForbidden());
        }
        mvc.perform(get("/api/v1/admin/usage/analytics").param("from", "2025-01-01T00:00:00Z")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/admin/usage/analytics").param("size", "101")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)).andExpect(status().isBadRequest());
        mvc.perform(put("/api/v1/admin/usage/model-prices").contentType("application/json")
                        .content("""
                                {"model":"test-model","inputPrice":-1,"outputPrice":8,"cacheReadPrice":0,"cacheWritePrice":0}
                                """).header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isBadRequest());
    }

    @Test
    void personalStatisticsUseTheAuthenticatedUserAndOnlyBoundedRanges() throws Exception {
        var first = auth.register("usage-personal-" + UUID.randomUUID() + "@example.com", "long-test-password", "First");
        var second = auth.register("usage-other-" + UUID.randomUUID() + "@example.com", "long-test-password", "Other");
        Instant at = clock.instant().minusSeconds(60);
        events.saveAndFlush(event(first.user().id(), "personal-model", at, 10, null, null));
        events.saveAndFlush(event(second.user().id(), "other-model", at, 999, null, null));
        mvc.perform(get("/api/v1/client/usage/analytics")).andExpect(status().isUnauthorized());
        for (int days : new int[] {1, 7, 30}) {
            mvc.perform(get("/api/v1/client/usage/analytics").param("days", String.valueOf(days))
                            .with(ClientBindingFixture.forUser(jdbc, first.user().id()))
                            .param("userId", second.user().id().toString())
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + first.accessToken()))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.currency").value("USD"))
                    .andExpect(jsonPath("$.timezone").value("UTC"))
                    .andExpect(jsonPath("$.granularity").value(days == 1 ? "HOUR" : "DAY"))
                    .andExpect(jsonPath("$.summary.eventCount").value(1))
                    .andExpect(jsonPath("$.summary.totalTokens").value(10))
                    .andExpect(jsonPath("$.summary.estimatedCostUsd").isEmpty())
                    .andExpect(jsonPath("$.users").doesNotExist())
                    .andExpect(jsonPath("$.events").doesNotExist());
        }
        for (String days : new String[] {"0", "2", "31", "abc"}) {
            mvc.perform(get("/api/v1/client/usage/analytics").param("days", days)
                            .with(ClientBindingFixture.forUser(jdbc, first.user().id()))
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + first.accessToken()))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    void databaseAndLedgerCalculatorAgreeOnMicroUsdAndPreserveUnknownCoverage() {
        var session = auth.register("usage-precision-" + UUID.randomUUID() + "@example.com", "long-test-password", "Precision");
        String model = "precision-" + UUID.randomUUID();
        var price = prices.saveAndFlush(new UsageModelPrice(model, new BigDecimal("0.000001"), BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, clock.instant()));
        Instant at = clock.instant().minusSeconds(60);
        var one = events.saveAndFlush(event(session.user().id(), model, at, 1, 1L, 0L));
        events.saveAndFlush(event(session.user().id(), model, at.minusSeconds(1), 1, 1L, 0L));
        events.saveAndFlush(event(session.user().id(), model, at.minusSeconds(2), 7, null, null));
        var filter = new UsageAnalyticsService.Filter(at.minusSeconds(10), at.plusSeconds(1),
                UsageAnalyticsService.Granularity.HOUR, model, null, session.user().id(), null, 0, 20);
        var data = analytics.analytics(filter);
        assertThat(data.summary().eventCount()).isEqualTo(3);
        assertThat(data.summary().totalTokens()).isEqualTo(9);
        assertThat(data.summary().costedEventCount()).isEqualTo(2);
        assertThat(data.summary().estimatedCostUsd()).isEqualByComparingTo("0.000000000002");
        assertThat(data.events().items().get(0).estimatedCostUsd())
                .isEqualByComparingTo(UsagePricingCalculator.estimate(one, price).estimatedCostUsd());
        assertThat(data.events().items().get(2).costStatus()).isEqualTo("MISSING_BREAKDOWN");
        prices.saveAndFlush(new UsageModelPrice(model, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, clock.instant()));
        assertThat(analytics.analytics(filter).summary().estimatedCostUsd()).isEqualByComparingTo("0");
        prices.deleteById(model);
        assertThat(analytics.analytics(filter).summary().estimatedCostUsd()).isNull();
    }

    @Test
    void respectsExclusiveEndUtcBucketsAndStablePagination() throws Exception {
        var session = auth.register("usage-boundary-" + UUID.randomUUID() + "@example.com", "long-test-password", "Boundary");
        String model = "boundary-" + UUID.randomUUID();
        Instant from = Instant.parse("2026-10-09T00:00:00Z");
        Instant to = from.plus(Duration.ofDays(1));
        var start = events.saveAndFlush(event(session.user().id(), model, from, 1, 1L, 0L));
        var inside = events.saveAndFlush(event(session.user().id(), model, from.plusSeconds(3600), 2, 2L, 0L));
        events.saveAndFlush(event(session.user().id(), model, to, 99, 99L, 0L));
        String token = adminToken();
        for (int page = 0; page < 2; page++) {
            mvc.perform(get("/api/v1/admin/usage/analytics").param("model", model)
                            .param("from", from.toString()).param("to", "2026-10-10T08:00:00+08:00")
                            .param("granularity", "HOUR").param("size", "1").param("page", String.valueOf(page))
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.summary.totalTokens").value(3))
                    .andExpect(jsonPath("$.trend[0].bucket").value(from.toString()))
                    .andExpect(jsonPath("$.trend[1].bucket").value(from.plusSeconds(3600).toString()))
                    .andExpect(jsonPath("$.events.totalElements").value(2))
                    .andExpect(jsonPath("$.events.totalPages").value(2))
                    .andExpect(jsonPath("$.events.items[0].id").value(page == 0 ? inside.getId() : start.getId()));
        }
    }

    private CodexUsageEvent event(UUID userId, String model, Instant at, long total, Long input, Long output) {
        return new CodexUsageEvent(UUID.randomUUID().toString().replace("-", "").repeat(2), userId, null, total,
                at, at, "TOKEN_USAGE_RECORD", model, "high", input, 0L, 0L, output, 0L);
    }

    private String adminToken() {
        var session = auth.register("usage-admin-" + UUID.randomUUID() + "@example.com", "long-test-password", "Admin");
        User admin = users.findById(session.user().id()).orElseThrow();
        admin.updateAdministration(User.Status.ACTIVE, User.Role.ADMIN);
        users.saveAndFlush(admin);
        return auth.login(admin.getEmail(), "long-test-password").accessToken();
    }
}
