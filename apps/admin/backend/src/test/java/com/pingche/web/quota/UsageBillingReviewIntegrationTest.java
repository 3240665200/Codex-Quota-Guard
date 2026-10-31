package com.pingche.web.quota;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.pingche.web.user.*;
import com.pingche.web.team.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;
import static com.pingche.web.quota.UsageBillingReview.*;

@SpringBootTest(properties="spring.datasource.url=jdbc:h2:mem:billing-review;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
@AutoConfigureMockMvc
class UsageBillingReviewIntegrationTest {
    private static final Instant NOW=Instant.parse("2026-10-31T02:00:00Z");
    @Autowired UserRepository users;
    @Autowired TeamRepository teams;
    @Autowired OfficialAccountRepository accounts;
    @Autowired CodexUsageEventRepository events;
    @Autowired UsageModelPriceRepository prices;
    @Autowired UsageBillingService billing;
    @Autowired UsageBillingReviewService reviews;
    @Autowired CodexUsageReportService reports;
    @Autowired UsageEventAuditService ledger;
    @Autowired UsageAnalyticsService analytics;
    @Autowired UsdQuotaService usd;
    @Autowired UsageReviewService queues;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired com.pingche.web.auth.JwtService jwt;
    @MockitoBean Clock clock;
    User user,admin;
    Team team;
    OfficialAccount account;

    @BeforeEach void setup() {
        when(clock.instant()).thenReturn(NOW);
        events.deleteAll();users.deleteAll();teams.deleteAll();accounts.deleteAll();
        account=accounts.saveAndFlush(new OfficialAccount("Review account",0,null,NOW));
        team=new Team("Review team","",Team.Status.ACTIVE,100,100,NOW);
        team.updateTokenLimits(null,null,null,account.getId(),NOW);team=teams.saveAndFlush(team);
        user=new User("billing-review@example.com","synthetic-hash","Review member");
        user.updateQuotaPolicy(team.getId(),null,null,NOW);user=users.saveAndFlush(user);
        admin=users.saveAndFlush(User.bootstrapAdministrator("billing-review-admin@example.com","synthetic-hash","Review admin"));
        prices.saveAndFlush(price("review-catalog","2"));
        prices.findById("review-future").ifPresent(prices::delete);
    }
    UsageModelPrice price(String model,String input) {
        return new UsageModelPrice(model,new BigDecimal(input),new BigDecimal("10"),new BigDecimal("0.2"),new BigDecimal("3"),NOW);
    }
    String token(User identity) { return "Bearer "+jwt.create(identity.getId(),identity.getRole().name(),identity.getSessionVersion()); }
    String route(String id) { return "/api/v1/admin/usage/billing/entries/"+id; }
    CodexUsageReportRequest report(boolean counts,String tier) {
        return new CodexUsageReportRequest(List.of(new CodexUsageReportRequest.Event("codex-local-usage:v1:"+"a".repeat(64),NOW,
                1_500_000,CodexUsageReportRequest.Source.TOKEN_USAGE_RECORD,null,null,
                counts?1_000_000L:null,counts?200_000L:null,counts?100_000L:null,counts?500_000L:null,counts?200_000L:null,tier)));
    }
    String record(boolean counts,String tier) { reports.report(user.getId(),report(counts,tier));return events.findAll().get(0).getId(); }
    Request input(Detail detail,String model,String tier,String mode,Prices custom) {
        return new Request(detail.revision(),detail.fingerprint(),new Facts(model,"max",1_500_000L,1_000_000L,200_000L,100_000L,
                500_000L,200_000L,tier),mode,custom,"根据原始设备日志核对模型、分项和价格",null);
    }
    String confirmed(Request request,String previewToken) {
        return json.writeValueAsString(new Request(request.revision(),request.fingerprint(),request.facts(),request.pricingMode(),request.customPrice(),request.note(),previewToken));
    }
    Preview preview(String id,Request request) throws Exception {
        return json.readValue(mvc.perform(post(route(id)+"/preview").header("Authorization",token(admin))
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(request)))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andReturn().getResponse().getContentAsString(),Preview.class);
    }
    void save(String id,Request request,Preview preview) throws Exception {
        mvc.perform(post(route(id)+"/review").header("Authorization",token(admin)).contentType(MediaType.APPLICATION_JSON)
                .content(confirmed(request,preview.previewToken()))).andExpect(status().isOk());
    }

    @Test void completesAllFieldsWithoutChangingRawReportsOrDoubleChargingAndUpdatesEveryLedger() throws Exception {
        String id=record(false,null);var raw=events.findById(id).orElseThrow();
        Prices custom=new Prices(new BigDecimal("2"),new BigDecimal("10"),new BigDecimal("0.2"),new BigDecimal("3"),new BigDecimal("1.5"));
        var request=input(reviews.detail(id),"review-custom","fast","CUSTOM",custom);
        var preview=preview(id,request);
        assertThat(preview.costUsd()).isEqualTo("10.11");
        assertThat(usd.personal(user.getId()).fiveHour().pendingEvents()).isEqualTo(1);
        assertThat(usd.personal(user.getId()).total().usedUsd()).isEqualByComparingTo("0");
        save(id,request,preview);
        var detail=reviews.detail(id);
        assertThat(detail.editable()).isFalse();assertThat(detail.history()).hasSize(1);
        assertThat(detail.effective().model()).isEqualTo("review-custom");
        assertThat(detail.original().model()).isNull();assertThat(detail.original().inputTokens()).isNull();
        assertThat(detail.appliedPrice().inputPrice()).isEqualByComparingTo("2");
        assertThat(detail.history().get(0).administratorName()).isEqualTo("Review admin");
        assertThat(events.findById(id).orElseThrow().getModel()).isNull();
        assertThat(events.findById(id).orElseThrow().getUserId()).isEqualTo(raw.getUserId());
        assertThat(events.findById(id).orElseThrow().getOccurredAt()).isEqualTo(NOW);
        assertThat(prices.findById("review-custom")).isEmpty();
        assertThat(usd.personal(user.getId()).total().usedUsd()).isEqualByComparingTo("10.11");
        assertThat(usd.team(team.getId()).total().usedUsd()).isEqualByComparingTo("10.11");
        assertThat(usd.officialAccount(account.getId()).quota().total().usedUsd()).isEqualByComparingTo("10.11");
        assertThat(usd.personal(user.getId()).fiveHour().pendingEvents()).isZero();
        assertThat(ledger.list(user.getId(),0,20).items().get(0).model()).isEqualTo("review-custom");
        var stats=analytics.analytics(new UsageAnalyticsService.Filter(NOW.minusSeconds(60),NOW.plusSeconds(1),UsageAnalyticsService.Granularity.HOUR,
                "review-custom",null,null,null,0,20));
        assertThat(stats.summary().eventCount()).isEqualTo(1);
        assertThat(stats.summary().estimatedCostUsd()).isEqualByComparingTo("10.11");
        assertThat(queues.reviewedBilling(user.getId(),0,20,null,null).items()).hasSize(1);
        mvc.perform(post(route(id)+"/review").header("Authorization",token(admin)).contentType(MediaType.APPLICATION_JSON)
                .content(confirmed(request,preview.previewToken()))).andExpect(status().isConflict());
        reports.report(user.getId(),report(false,null));
        assertThat(events.count()).isEqualTo(1);
        assertThat(reviews.detail(id).history()).hasSize(1);
        assertThat(usd.personal(user.getId()).total().usedUsd()).isEqualByComparingTo("10.11");
    }
    @Test void catalogReconciliationUsesCorrectedModelAndRetainsManualHistory() throws Exception {
        String id=record(true,"default");
        var request=input(reviews.detail(id),"review-future","standard","CATALOG",null);
        var preview=preview(id,request);
        assertThat(preview.costUsd()).isNull();assertThat(preview.missingFields()).contains("price");
        save(id,request,preview);
        assertThat(reviews.detail(id).editable()).isTrue();
        assertThat(queues.pendingBilling(null,0,20).items().get(0).model()).isEqualTo("review-future");
        prices.saveAndFlush(price("review-future","2"));
        assertThat(billing.reconcileMissingPrices()).isEqualTo(1);
        assertThat(reviews.detail(id).costUsd()).isEqualTo("6.74");
        assertThat(reviews.detail(id).history()).hasSize(1);
        assertThat(events.findById(id).orElseThrow().getModel()).isNull();
    }
    @Test void requiresAFreshPreviewWhenPricesChangeAndRejectsAnOldRevision() throws Exception {
        String id=record(true,"default");
        var request=input(reviews.detail(id),"review-catalog","standard","CATALOG",null);
        var preview=preview(id,request);
        prices.saveAndFlush(price("review-catalog","4"));
        mvc.perform(post(route(id)+"/review").header("Authorization",token(admin)).contentType(MediaType.APPLICATION_JSON)
                .content(confirmed(request,preview.previewToken()))).andExpect(status().isConflict());
        assertThat(reviews.detail(id).revision()).isZero();
        assertThat(jdbc.queryForObject("select count(*) from usage_billing_review_audit",Long.class)).isZero();
        var partial=input(reviews.detail(id),"review-future","standard","CATALOG",null);
        save(id,partial,preview(id,partial));
        mvc.perform(post(route(id)+"/preview").header("Authorization",token(admin)).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(request))).andExpect(status().isConflict());
    }
    @Test void checksAdministratorAuthorityEvidenceAndCountConsistency() throws Exception {
        String id=record(false,null);var request=input(reviews.detail(id),"review-catalog","standard","CATALOG",null);
        mvc.perform(get(route(id))).andExpect(status().isUnauthorized());
        mvc.perform(get(route(id)).header("Authorization",token(user))).andExpect(status().isForbidden());
        for(String suffix:List.of("/preview","/review")) mvc.perform(post(route(id)+suffix).header("Authorization",token(user))
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(request))).andExpect(status().isForbidden());
        String valid=json.writeValueAsString(request);
        for(String body:List.of(valid.replace("1000000","1000001"),valid.replace("200000","-1"),
                valid.replace("根据原始设备日志核对模型、分项和价格"," "),valid.replace("review-catalog","<bad-model>"),
                valid.replace("\"inputTokens\":1000000","\"inputTokens\":1000000.5"))) {
            mvc.perform(post(route(id)+"/preview").header("Authorization",token(admin)).contentType(MediaType.APPLICATION_JSON)
                    .content(body)).andExpect(status().isBadRequest());
        }
        mvc.perform(post(route(id)+"/review").header("Authorization",token(admin)).contentType(MediaType.APPLICATION_JSON)
                .content(valid)).andExpect(status().isConflict());
        assertThat(reviews.detail(id).revision()).isZero();
    }
    @Test void anExplicitlyUnconfirmedModeStaysPendingWhenTheCatalogIsReconciled() throws Exception {
        String id=record(true,"default");
        var request=input(reviews.detail(id),"review-future","unknown","CATALOG",null);
        save(id,request,preview(id,request));
        prices.saveAndFlush(price("review-future","2"));
        billing.reconcileMissingPrices();
        assertThat(reviews.detail(id).costStatus()).isEqualTo("MISSING_SERVICE_TIER");
        assertThat(reviews.detail(id).effective().serviceTier()).isEqualTo("unknown");
        assertThat(billing.correctReportedMode(user.getId(),UUID.randomUUID(),id,"fast")).isZero();
        billing.confirmMode(List.of(id),"standard",admin.getId());
        assertThat(reviews.detail(id).costUsd()).isEqualTo("6.74");
    }

    @Test void customPricesRequireExactBoundedDecimalsAndZeroCostIsStillImmutable() throws Exception {
        String id=record(true,"default");var detail=reviews.detail(id);
        for(String invalid:List.of("2.0000001","-0.01","1000001")) {
            var p=new Prices(new BigDecimal(invalid),BigDecimal.TEN,BigDecimal.ZERO,BigDecimal.ZERO,new BigDecimal("1.5"));
            mvc.perform(post(route(id)+"/preview").header("Authorization",token(admin)).contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(input(detail,"review-custom","standard","CUSTOM",p)))).andExpect(status().isBadRequest());
        }
        var zero=new Prices(BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO,new BigDecimal("1.5"));
        var request=input(detail,"review-custom","standard","CUSTOM",zero);
        save(id,request,preview(id,request));
        assertThat(reviews.detail(id).costUsd()).isEqualTo("0");
        assertThat(reviews.detail(id).editable()).isFalse();
    }
    @Test void retainedUsageFromADeletedMemberIsStillReviewableWithoutReassigningItsOwner() throws Exception {
        String id=record(true,"default");
        jdbc.update("update codex_usage_events set user_id=null where id=?",id);
        assertThat(queues.pendingBilling(null,0,20).items().get(0).displayName()).isEqualTo("已删除用户");
        var request=input(reviews.detail(id),"review-catalog","standard","CATALOG",null);
        save(id,request,preview(id,request));
        assertThat(reviews.detail(id).userId()).isNull();
        assertThat(queues.reviewedBilling(null,0,20,null,null).items()).hasSize(1);
        assertThat(usd.team(team.getId()).total().usedUsd()).isEqualByComparingTo("6.74");
    }
}
