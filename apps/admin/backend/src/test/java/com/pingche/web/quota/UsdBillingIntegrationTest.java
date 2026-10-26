package com.pingche.web.quota;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.pingche.web.user.User;
import com.pingche.web.user.UserRepository;
import com.pingche.web.team.Team;
import com.pingche.web.team.TeamRepository;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:usd-billing;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "app.restriction-scheduler-enabled=false"})
@ActiveProfiles("test")
@AutoConfigureMockMvc
class UsdBillingIntegrationTest {
    static final Instant NOW = Instant.parse("2026-10-25T12:00:00Z");
    @Autowired UserRepository users;
    @Autowired TeamRepository teams;
    @Autowired OfficialAccountRepository accounts;
    @Autowired CodexUsageEventRepository events;
    @Autowired UsageModelPriceRepository prices;
    @Autowired UsageBillingService billing;
    @Autowired CodexUsageReportService reports;
    @Autowired QuotaOverviewService overview;
    @Autowired UsdQuotaService usd;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired com.pingche.web.auth.JwtService jwt;
    @Autowired com.pingche.web.meter.MeterDeviceRepository meterDevices;
    @Autowired com.pingche.web.meter.MeterDeviceService meterService;
    @MockitoBean Clock clock;
    User user;
    Team team;
    OfficialAccount account;
    @BeforeEach void setup() {
        when(clock.instant()).thenReturn(NOW);
        events.deleteAll(); users.deleteAll(); teams.deleteAll(); accounts.deleteAll();
        account = accounts.saveAndFlush(new OfficialAccount("USD account", 9_000_000, null, NOW));
        team = new Team("USD team","",Team.Status.ACTIVE,100,100,NOW);
        team.updateTokenLimits(0L,0L,0L,account.getId(),NOW); team=teams.saveAndFlush(team);
        user = new User("usd@example.com","synthetic-hash","USD member");
        user.updateQuotaPolicy(team.getId(),null,null,NOW); user.updateTokenLimits(0L,0L,NOW); user=users.saveAndFlush(user);
        prices.saveAndFlush(new UsageModelPrice("usd-fixture",new BigDecimal("2"),new BigDecimal("10"),new BigDecimal("0.2"),new BigDecimal("3"),NOW));
    }
    private CodexUsageReportRequest request(String tier, char id) {
        return requestAt(NOW, tier, id);
    }
    private CodexUsageReportRequest requestAt(Instant at, String tier, char id) {
        return new CodexUsageReportRequest(List.of(new CodexUsageReportRequest.Event("codex-local-usage:v1:"+String.valueOf(id).repeat(64),
                at,1_500_000,CodexUsageReportRequest.Source.TOKEN_USAGE_RECORD,"usd-fixture","max",
                1_000_000L,200_000L,100_000L,500_000L,200_000L,tier)));
    }
    @Test void ordinaryAndFastChargesUseCacheBreakdownWithoutDoubleChargingReasoning() {
        reports.report(user.getId(),request("default",'a'));
        assertThat(usd.personal(user.getId()).total().usedUsd()).isEqualByComparingTo("6.74");
        reports.report(user.getId(),request("fast",'b'));
        assertThat(usd.personal(user.getId()).total().usedUsd()).isEqualByComparingTo("16.85");
        var fast=jdbc.queryForMap("select * from usage_billing_entries where multiplier=1.5");
        assertThat((BigDecimal)fast.get("cost_usd")).isEqualByComparingTo("10.11");
        assertThat(fast.get("mode_status")).isEqualTo("FAST");
        assertThat(usd.officialAccount(account.getId()).quota().total().usedUsd()).isEqualByComparingTo("16.85");
    }
    @Test void meterRepairsOnlyItsExistingUnbilledEventsWithoutRecreatingDeletedHistory() throws Exception {
        UUID device=UUID.randomUUID(),other=UUID.randomUUID();
        var grant=meterService.enroll(user.getId(),device);
        var otherGrant=meterService.enroll(user.getId(),other);
        reports.report(user.getId(),device,request(null,'a'));
        String body="{\"events\":[{\"eventId\":\"codex-local-usage:v1:"+"a".repeat(64)+"\",\"serviceTier\":\"priority\"}]}";
        mvc.perform(post("/api/v1/meter/usage/modes").header("X-Quota-Guard-Meter",otherGrant.deviceToken()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.updatedEvents").value(0));
        mvc.perform(post("/api/v1/meter/usage/modes").header("X-Quota-Guard-Meter",grant.deviceToken()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.updatedEvents").value(1));
        assertThat(usd.personal(user.getId()).total().usedUsd()).isEqualByComparingTo("10.11");
        assertThat(usd.personal(user.getId()).total().pendingEvents()).isZero();
        mvc.perform(post("/api/v1/meter/usage/modes").header("X-Quota-Guard-Meter",grant.deviceToken()).contentType(MediaType.APPLICATION_JSON).content(body.replace("priority","default")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.updatedEvents").value(0));
        assertThat(usd.personal(user.getId()).total().usedUsd()).isEqualByComparingTo("10.11");
        events.deleteAll();
        mvc.perform(post("/api/v1/meter/usage/modes").header("X-Quota-Guard-Meter",grant.deviceToken()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.updatedEvents").value(0));
        assertThat(events.count()).isZero();
        mvc.perform(post("/api/v1/meter/usage/modes").header("Authorization",administratorToken()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        for(String invalid:List.of("{\"events\":[null]}",body.replace("priority","unknown")))
            mvc.perform(post("/api/v1/meter/usage/modes").header("X-Quota-Guard-Meter",grant.deviceToken()).contentType(MediaType.APPLICATION_JSON).content(invalid))
                    .andExpect(status().isBadRequest());
    }
    @Test void tokenCapsAreArchivedAndNullDollarCapsRemainUnlimited() {
        assertThat(overview.overview(user.getId()).allowed()).isTrue();
        reports.report(user.getId(),request("default",'c'));
        assertThat(overview.overview(user.getId()).allowed()).isTrue();
        assertThat(usd.personal(user.getId()).fiveHour().limitUsd()).isNull();
        jdbc.update("update users set five_hour_usd_limit=0 where id=?",user.getId());
        assertThat(overview.overview(user.getId()).blockReason()).isEqualTo("FIVE_HOUR_USD_QUOTA_EXCEEDED");
    }
    @Test void repeatedReportsAndLaterPriceChangesNeverRewriteBilledAmounts() {
        var request=request("priority",'d'); reports.report(user.getId(),request);
        prices.saveAndFlush(new UsageModelPrice("usd-fixture",new BigDecimal("99"),new BigDecimal("99"),BigDecimal.ONE,BigDecimal.ONE,NOW));
        reports.report(user.getId(),request);
        assertThat(events.count()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from usage_billing_entries",Long.class)).isEqualTo(1);
        assertThat(usd.personal(user.getId()).total().usedUsd()).isEqualByComparingTo("10.11");
        assertThat(billing.reconcileMissingPrices()).isZero();
    }
    @Test void officialAccountCapAppliesAcrossTeamsAndKeepsTheOriginalAttribution() {
        reports.report(user.getId(),request("fast",'e'));
        jdbc.update("update official_accounts set budget_usd=10 where id=?",account.getId());
        assertThat(overview.overview(user.getId()).blockReason()).isEqualTo("OFFICIAL_ACCOUNT_USD_QUOTA_EXCEEDED");
        OfficialAccount another=accounts.saveAndFlush(new OfficialAccount("Another USD",0,null,NOW));
        team.updateTokenLimits(null,null,null,another.getId(),NOW); teams.saveAndFlush(team);
        assertThat(usd.officialAccount(account.getId()).quota().total().usedUsd()).isEqualByComparingTo("10.11");
        assertThat(usd.officialAccount(another.getId()).quota().total().usedUsd()).isEqualByComparingTo("0");
    }
    @Test void missingPricesRemainUnresolvedUntilExplicitReconciliation() {
        prices.deleteById("usd-fixture");
        reports.report(user.getId(),request("fast",'f'));
        assertThat(usd.personal(user.getId()).fiveHour().pendingEvents()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select cost_usd from usage_billing_entries",BigDecimal.class)).isNull();
        var price=new UsageModelPrice("usd-fixture",new BigDecimal("2"),new BigDecimal("10"),new BigDecimal("0.2"),new BigDecimal("3"),NOW);
        prices.saveAndFlush(price);
        assertThat(billing.reconcileMissingPrices()).isEqualTo(1);
        assertThat(usd.personal(user.getId()).fiveHour().pendingEvents()).isZero();
        assertThat(usd.personal(user.getId()).fiveHour().usedUsd()).isEqualByComparingTo("10.11");
    }
    @Test void unpricedUsageCannotMasqueradeAsAZeroDollarBalanceWhenAnActiveBudgetExists() {
        prices.deleteById("usd-fixture");
        jdbc.update("update users set five_hour_usd_limit=100 where id=?",user.getId());
        reports.report(user.getId(),request("fast",'a'));
        assertThat(usd.personal(user.getId()).fiveHour().remainingUsd()).isNull();
        assertThat(overview.overview(user.getId()).blockReason()).isEqualTo("BILLING_PENDING");
        assertThat(usd.personalExhausted(user.getId())).isFalse();
    }
    @Test void personalAndTeamBudgetsUseDollarAmountsInsteadOfTokenQuantities() {
        jdbc.update("update users set five_hour_usd_limit=12 where id=?",user.getId());
        reports.report(user.getId(),request("default",'a'));
        assertThat(overview.overview(user.getId()).allowed()).isTrue();
        reports.report(user.getId(),request("fast",'b'));
        assertThat(overview.overview(user.getId()).blockReason()).isEqualTo("FIVE_HOUR_USD_QUOTA_EXCEEDED");
        jdbc.update("update users set five_hour_usd_limit=null where id=?",user.getId());
        jdbc.update("update teams set total_usd_limit=15 where id=?",team.getId());
        assertThat(overview.overview(user.getId()).blockReason()).isEqualTo("TEAM_TOTAL_USD_QUOTA_EXCEEDED");
    }
    private String administratorToken() {
        User admin=users.saveAndFlush(User.bootstrapAdministrator("usd-admin@example.com","synthetic-hash","USD admin"));
        return "Bearer "+jwt.create(admin.getId(),admin.getRole().name(),admin.getSessionVersion());
    }
    @Test void onlyAdministratorsCanWriteDollarBudgetsAndDecimalValuesAreNotRounded() throws Exception {
        String route="/api/v1/admin/users/"+user.getId()+"/usd-limits";
        String body="{\"fiveHourUsdLimit\":\"1.000000000001\",\"weeklyUsdLimit\":null}";
        mvc.perform(put(route).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());
        String memberToken="Bearer "+jwt.create(user.getId(),user.getRole().name(),user.getSessionVersion());
        mvc.perform(put(route).header("Authorization",memberToken).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        long sessionVersion=user.getSessionVersion();
        mvc.perform(put(route).header("Authorization",administratorToken()).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
                .andExpect(jsonPath("$.fiveHour.limitUsd").value("1.000000000001"));
        assertThat(users.findById(user.getId()).orElseThrow().getSessionVersion()).isEqualTo(sessionVersion);
        assertThat(users.findById(user.getId()).orElseThrow().getFiveHourTokenLimit()).isZero();
        assertThat(usd.personal(user.getId()).fiveHour().limitUsd()).isEqualByComparingTo("1.000000000001");
    }
    @Test void rejectsNegativeOverPreciseAndOutOfRangeMoneyInsteadOfRoundingIt() throws Exception {
        String token=administratorToken();
        for(String value:List.of("-0.01","0.0000000000001","1000000000001")) {
            mvc.perform(put("/api/v1/admin/users/"+user.getId()+"/usd-limits").header("Authorization",token)
                    .contentType(MediaType.APPLICATION_JSON).content("{\"fiveHourUsdLimit\":\""+value+"\"}"))
                    .andExpect(status().isBadRequest());
        }
        assertThat(usd.personal(user.getId()).fiveHour().limitUsd()).isNull();
    }
    @Test void changingMoneyOnlyKeepsTheTeamIdentityAndDerivesTheSharedCapFromTheAccount() throws Exception {
        jdbc.update("update official_accounts set budget_usd=100 where id=?",account.getId());
        long version=team.getVersion(),sessionVersion=user.getSessionVersion();
        mvc.perform(put("/api/v1/admin/teams/"+team.getId()+"/usd-limits").header("Authorization",administratorToken())
                .contentType(MediaType.APPLICATION_JSON).content("{\"fiveHourUsdLimit\":\"10.01\",\"weeklyUsdLimit\":\"25\",\"officialAccountId\":\""+account.getId()+"\",\"version\":"+version+"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total.limitUsd").value("100.000000000000"));
        assertThat(teams.findById(team.getId()).orElseThrow().getVersion()).isEqualTo(version);
        assertThat(users.findById(user.getId()).orElseThrow().getSessionVersion()).isEqualTo(sessionVersion);
        assertThat(usd.team(team.getId()).fiveHour().limitUsd()).isEqualByComparingTo("10.01");
    }

    @Test void newUnrecordedModeStaysPendingUntilAnAdministratorExplicitlyConfirmsIt() throws Exception {
        jdbc.update("update users set five_hour_usd_limit=100 where id=?", user.getId());
        reports.report(user.getId(), request(null,'a'));
        assertThat(usd.personal(user.getId()).fiveHour().remainingUsd()).isNull();
        assertThat(usd.personalExhausted(user.getId())).isFalse();
        String id=events.findAll().get(0).getId();
        assertThat(billing.entries(List.of(id)).get(id).costStatus()).isEqualTo("MISSING_SERVICE_TIER");
        String body="{\"eventIds\":[\""+id+"\"],\"serviceTier\":\"fast\"}";
        String member="Bearer "+jwt.create(user.getId(),user.getRole().name(),user.getSessionVersion());
        mvc.perform(post("/api/v1/admin/usage/billing/confirm-mode").header("Authorization",member)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        String admin=administratorToken();
        mvc.perform(post("/api/v1/admin/usage/billing/confirm-mode").header("Authorization",admin)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk()).andExpect(jsonPath("$.updated").value(1));
        var entry=billing.entries(List.of(id)).get(id);
        assertThat(entry.costUsd()).isEqualByComparingTo("10.11");
        assertThat(entry.modeSource()).isEqualTo("ADMIN_CONFIRMED");
        mvc.perform(post("/api/v1/admin/usage/billing/confirm-mode").header("Authorization",admin)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isConflict());
        assertThat(usd.personal(user.getId()).total().usedUsd()).isEqualByComparingTo("10.11");
    }

    @Test void deviceDeclarationsUseEventTimeAndCannotRepriceOlderOfflineEvents() {
        UUID device=UUID.randomUUID();
        meterDevices.saveAndFlush(new com.pingche.web.meter.MeterDevice(device,user.getId(),UUID.randomUUID().toString(),NOW,NOW.plusSeconds(3600)));
        billing.declareMode(user.getId(),device,"fast",user.getId());
        reports.report(user.getId(),device,requestAt(NOW.minusSeconds(1),null,'a'));
        reports.report(user.getId(),device,request(null,'b'));
        assertThat(usd.personal(user.getId()).total().usedUsd()).isEqualByComparingTo("10.11");
        assertThat(usd.personal(user.getId()).total().pendingEvents()).isEqualTo(1);
        when(clock.instant()).thenReturn(NOW.plusSeconds(60));
        billing.declareMode(user.getId(),device,"standard",user.getId());
        reports.report(user.getId(),device,requestAt(NOW.plusSeconds(30),null,'c'));
        reports.report(user.getId(),device,requestAt(NOW.plusSeconds(60),null,'d'));
        assertThat(usd.personal(user.getId()).total().usedUsd()).isEqualByComparingTo("26.96");
        assertThat(jdbc.queryForObject("select count(*) from usage_billing_entries where mode_source='ADMIN_DECLARED'",Integer.class)).isEqualTo(3);
        // Explicit per-event evidence takes precedence over a manual default.
        reports.report(user.getId(),device,requestAt(NOW.plusSeconds(60),"fast",'e'));
        assertThat(usd.personal(user.getId()).total().usedUsd()).isEqualByComparingTo("37.07");
        billing.declareMode(user.getId(),device,"unknown",user.getId());
        reports.report(user.getId(),device,requestAt(NOW.plusSeconds(60),null,'f'));
        assertThat(usd.personal(user.getId()).total().pendingEvents()).isEqualTo(2);
    }

    @Test void deviceDeclarationRejectsWrongOwnerAndInvalidModesAtTheAdminBoundary() throws Exception {
        UUID device=UUID.randomUUID();
        meterDevices.saveAndFlush(new com.pingche.web.meter.MeterDevice(device,user.getId(),UUID.randomUUID().toString(),NOW,NOW.plusSeconds(3600)));
        String admin=administratorToken();
        mvc.perform(put("/api/v1/admin/users/"+user.getId()+"/billing-modes/"+device).header("Authorization",admin)
                .contentType(MediaType.APPLICATION_JSON).content("{\"serviceTier\":\"fast\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].serviceTier").value("fast"));
        mvc.perform(put("/api/v1/admin/users/"+UUID.randomUUID()+"/billing-modes/"+device).header("Authorization",admin)
                .contentType(MediaType.APPLICATION_JSON).content("{\"serviceTier\":\"standard\"}")).andExpect(status().isConflict());
        mvc.perform(put("/api/v1/admin/users/"+user.getId()+"/billing-modes/"+device).header("Authorization",admin)
                .contentType(MediaType.APPLICATION_JSON).content("{\"serviceTier\":\"discount\"}")).andExpect(status().isBadRequest());
    }

    @Test void confirmingAnUnpricedFastRecordKeepsItsModeWhenPricesBecomeAvailable() {
        prices.deleteById("usd-fixture");
        reports.report(user.getId(),request(null,'a'));
        String id=events.findAll().get(0).getId();
        billing.confirmMode(List.of(id),"fast",user.getId());
        assertThat(billing.entries(List.of(id)).get(id).costStatus()).isEqualTo("MISSING_PRICE");
        prices.saveAndFlush(new UsageModelPrice("usd-fixture",new BigDecimal("2"),new BigDecimal("10"),new BigDecimal("0.2"),new BigDecimal("3"),NOW));
        assertThat(billing.reconcileMissingPrices()).isEqualTo(1);
        var entry=billing.entries(List.of(id)).get(id);
        assertThat(entry.costUsd()).isEqualByComparingTo("10.11");
        assertThat(entry.modeSource()).isEqualTo("ADMIN_CONFIRMED");
    }
}
