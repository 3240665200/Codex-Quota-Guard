package com.pingche.web.quota;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.aMapWithSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pingche.web.auth.JwtService;
import com.pingche.web.auth.RefreshSessionRepository;
import com.pingche.web.sessionbinding.ClientBindingFixture;
import com.pingche.web.team.Team;
import com.pingche.web.team.TeamRepository;
import com.pingche.web.user.User;
import com.pingche.web.user.UserRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ClientTeamMemberUsageIntegrationTest {

    private static final String OVERVIEW = "/api/v1/client/quota/overview";
    private static final Instant NOW = Instant.parse("2026-10-15T12:00:00Z");

    @Autowired private MockMvc mvc;
    @Autowired private JwtService jwtService;
    @Autowired private UserRepository users;
    @Autowired private TeamRepository teams;
    @Autowired private CodexUsageEventRepository events;
    @Autowired private RefreshSessionRepository refreshSessions;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private QuotaOverviewService overview;

    @BeforeEach
    void cleanDatabase() {
        ClientBindingFixture.clearBindings(jdbc);
        events.deleteAll();
        refreshSessions.deleteAll();
        users.deleteAll();
        teams.deleteAll();
    }

    @AfterEach
    void clearBindings() {
        ClientBindingFixture.clearBindings(jdbc);
    }

    @Test
    void ordinaryMemberCanReadAllCurrentMembersIncludingZeroUsageAndDisabledMembers() throws Exception {
        Team team = team("Current team");
        Team otherTeam = team("Other team");
        User caller = member("caller@example.com", "Alice", team);
        personalLimits(caller, 100L, 200L);
        User zero = member("zero@example.com", "Bob", team);
        User sameName = member("same-name@example.com", "Bob", team);
        User disabled = member("disabled@example.com", "Carol", team);
        disabled.updateAdministration(User.Status.DISABLED, null);
        users.saveAndFlush(disabled);
        User other = member("other@example.com", "Other member", otherTeam);
        Instant recent = Instant.now().minusSeconds(10).truncatedTo(ChronoUnit.SECONDS);
        event(caller, team, 10, recent, recent);
        event(sameName, team, 20, recent, recent);
        event(disabled, team, 30, recent, recent);
        event(other, otherTeam, 9999, recent, recent);

        String content = mvc.perform(get(OVERVIEW)
                        .queryParam("includeTeamMembers", "true")
                        .queryParam("teamId", otherTeam.getId().toString())
                        .queryParam("userId", other.getId().toString())
                        .header(HttpHeaders.AUTHORIZATION, bearer(caller))
                        .with(ClientBindingFixture.forUser(jdbc, caller.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.team.id").value(team.getId().toString()))
                .andExpect(jsonPath("$.teamMembers.length()").value(4))
                .andExpect(jsonPath("$.teamMembers[0]", aMapWithSize(11)))
                .andExpect(jsonPath("$.teamMembers[0].fiveHourTokenLimit").value(100))
                .andExpect(jsonPath("$.teamMembers[0].weeklyTokenLimit").value(200))
                .andExpect(jsonPath("$.teamMembers[0].fiveHourRemainingTokens").value(90))
                .andExpect(jsonPath("$.teamMembers[0].weeklyRemainingTokens").value(190))
                .andExpect(jsonPath("$.teamMembers[*].email").doesNotExist())
                .andExpect(jsonPath("$.teamQuota.totalUsedTokens").value(60))
                .andReturn().getResponse().getContentAsString();

        QuotaOverviewResponse response = objectMapper.readValue(content, QuotaOverviewResponse.class);
        List<UUID> expectedOrder = List.of(caller, zero, sameName, disabled).stream()
                .sorted(Comparator.comparing(User::getDisplayName)
                        .thenComparing(user -> user.getId().toString()))
                .map(User::getId).toList();
        assertThat(response.teamMembers()).extracting(TeamMemberUsageResponse::userId)
                .containsExactlyElementsOf(expectedOrder);
        assertThat(memberUsage(response, zero)).isEqualTo(new TeamMemberUsageResponse(
                zero.getId(), zero.getDisplayName(), User.Status.ACTIVE, 0, 0, 0,
                null, null, null, null, null));
        assertThat(memberUsage(response, disabled)).isEqualTo(new TeamMemberUsageResponse(
                disabled.getId(), disabled.getDisplayName(), User.Status.DISABLED, 30, 30, 30,
                recent, null, null, null, null));
        assertThat(memberUsage(response, sameName).totalUsedTokens()).isEqualTo(20);

        mvc.perform(get("/api/v1/admin/teams/{id}/quota-overview", otherTeam.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(caller)))
                .andExpect(status().isForbidden());
    }

    @Test
    void incomingMembersDoNotExposeOtherTeamsHistoryAndDepartedMembersStayInTeamTotals() {
        Team team = team("Destination team");
        Team otherTeam = team("Original team");
        User caller = member("caller@example.com", "Alice", team);
        User incoming = member("incoming@example.com", "Incoming", otherTeam);
        personalLimits(incoming, 1_000L, 2_000L);
        event(incoming, otherTeam, 900, NOW.minusSeconds(3), NOW.minusSeconds(3));
        incoming.updateQuotaPolicy(team.getId(), null, null, NOW);
        users.saveAndFlush(incoming);
        event(incoming, team, 25, NOW.minusSeconds(2), NOW.minusSeconds(2));
        User departed = member("departed@example.com", "Departed", team);
        event(departed, team, 75, NOW.minusSeconds(1), NOW.minusSeconds(1));
        departed.updateQuotaPolicy(otherTeam.getId(), null, null, NOW);
        users.saveAndFlush(departed);

        QuotaOverviewResponse response = fixedOverview().overview(caller.getId(), true);

        assertThat(response.teamMembers()).extracting(TeamMemberUsageResponse::userId)
                .containsExactly(caller.getId(), incoming.getId());
        assertThat(memberUsage(response, incoming)).isEqualTo(new TeamMemberUsageResponse(
                incoming.getId(), incoming.getDisplayName(), User.Status.ACTIVE,
                25, 25, 25, NOW.minusSeconds(2), 1_000L, 2_000L, 75L, 1_075L));
        assertThat(response.teamQuota().totalUsedTokens()).isEqualTo(100);
        QuotaOverviewResponse ownOverview = fixedOverview().overview(incoming.getId());
        assertThat(ownOverview.usage().fiveHourUsedTokens()).isEqualTo(925);
        assertThat(memberUsage(response, incoming).fiveHourRemainingTokens())
                .isEqualTo(ownOverview.usage().fiveHourRemainingTokens());
        assertThat(memberUsage(response, incoming).weeklyRemainingTokens())
                .isEqualTo(ownOverview.usage().weeklyRemainingTokens());
    }

    @Test
    void memberWindowsUseTheSameExclusiveStartsAndInclusiveEndAsTeamTotals() {
        Team team = team("Rolling windows");
        User caller = member("caller@example.com", "Alice", team);
        personalLimits(caller, 100L, 200L);
        event(caller, team, 1, NOW.minus(Duration.ofDays(8)), NOW);
        event(caller, team, 2, NOW.minus(Duration.ofDays(7)), NOW);
        event(caller, team, 4, NOW.minus(Duration.ofDays(7)).plusSeconds(1), NOW);
        event(caller, team, 8, NOW.minus(Duration.ofHours(5)), NOW);
        event(caller, team, 16, NOW.minus(Duration.ofHours(5)).plusSeconds(1), NOW);
        event(caller, team, 32, NOW, NOW);
        event(caller, team, 64, NOW.plusSeconds(1), NOW);
        event(caller, team, 128, NOW, Instant.EPOCH);

        QuotaOverviewResponse response = fixedOverview().overview(caller.getId(), true);

        assertThat(memberUsage(response, caller)).isEqualTo(new TeamMemberUsageResponse(
                caller.getId(), caller.getDisplayName(), User.Status.ACTIVE, 48, 60, 63,
                NOW, 100L, 200L, 52L, 140L));
        assertMemberMatchesTeamTotals(response, caller);
    }

    @Test
    void teamResetExcludesOldAndLateEventsAndDoesNotUsePersonalReset() {
        Team team = team("Reset windows");
        Instant reset = NOW.minus(Duration.ofDays(3));
        team.resetQuota(reset);
        teams.saveAndFlush(team);
        User caller = member("caller@example.com", "Alice", team);
        personalLimits(caller, 100L, 200L);
        caller.resetQuota(NOW.minus(Duration.ofHours(1)));
        users.saveAndFlush(caller);
        User oldOnly = member("old-only@example.com", "Old only", team);
        personalLimits(oldOnly, 100L, 500L);
        event(caller, team, 1024, reset.minusSeconds(1), NOW);
        event(caller, team, 2048, reset, NOW);
        event(caller, team, 4096, NOW.minusSeconds(1), reset);
        event(caller, team, 8192, NOW.minusSeconds(1), reset.minusSeconds(1));
        event(caller, team, 4, reset.plusSeconds(1), NOW.minusSeconds(3));
        event(caller, team, 8, NOW.minus(Duration.ofHours(5)), NOW.minusSeconds(2));
        event(caller, team, 16, NOW.minus(Duration.ofHours(5)).plusSeconds(1), NOW.minusSeconds(1));
        event(caller, team, 32, NOW, NOW);
        event(oldOnly, team, 256, reset.minusSeconds(1), NOW);

        QuotaOverviewResponse response = fixedOverview().overview(caller.getId(), true);

        assertThat(memberUsage(response, caller)).isEqualTo(new TeamMemberUsageResponse(
                caller.getId(), caller.getDisplayName(), User.Status.ACTIVE, 48, 60, 60,
                NOW, 100L, 200L, 68L, 168L));
        assertThat(memberUsage(response, oldOnly)).isEqualTo(new TeamMemberUsageResponse(
                oldOnly.getId(), oldOnly.getDisplayName(), User.Status.ACTIVE, 0, 0, 0,
                null, 100L, 500L, 100L, 244L));
        assertMemberMatchesTeamTotals(response, caller);
        assertThat(response.usage().fiveHourUsedTokens()).isEqualTo(32);
    }

    @Test
    void personalResetExcludesOldAndLateEventsWithoutResettingTeamContributions() {
        Team team = team("Personal reset");
        User caller = member("caller@example.com", "Alice", team);
        personalLimits(caller, 100L, 200L);
        Instant reset = NOW.minus(Duration.ofHours(2));
        caller.resetQuota(reset);
        users.saveAndFlush(caller);
        event(caller, team, 101, reset.minusSeconds(1), NOW);
        event(caller, team, 102, reset, NOW);
        event(caller, team, 103, reset.plusSeconds(1), reset);
        event(caller, team, 104, reset.plusSeconds(2), reset.minusSeconds(1));
        event(caller, team, 5, reset.plusSeconds(3), reset.plusSeconds(1));
        event(caller, team, 7, NOW, NOW);
        event(caller, team, 105, NOW.plusSeconds(1), NOW);

        QuotaOverviewResponse response = fixedOverview().overview(caller.getId(), true);

        assertThat(memberUsage(response, caller)).isEqualTo(new TeamMemberUsageResponse(
                caller.getId(), caller.getDisplayName(), User.Status.ACTIVE, 422, 422, 422,
                NOW, 100L, 200L, 88L, 188L));
        assertThat(response.usage().fiveHourUsedTokens()).isEqualTo(12);
        assertThat(response.usage().weeklyUsedTokens()).isEqualTo(12);
        assertMemberMatchesTeamTotals(response, caller);
    }

    @Test
    void personalLimitsDistinguishUnconfiguredZeroUnusedAndExceededAllowances() {
        Team team = team("Personal limits");
        User caller = member("caller@example.com", "Alice", team);
        event(caller, team, 9_999, NOW, NOW);
        User zero = member("zero@example.com", "Zero", team);
        personalLimits(zero, 0L, 0L);
        User over = member("over@example.com", "Over", team);
        personalLimits(over, 10L, 20L);
        event(over, team, 30, NOW, NOW);
        User unused = member("unused@example.com", "Unused", team);
        personalLimits(unused, 50L, 70L);
        User weeklyOnly = member("weekly-only@example.com", "Weekly only", team);
        personalLimits(weeklyOnly, null, 100L);
        event(weeklyOnly, team, 40, NOW, NOW);

        QuotaOverviewResponse response = fixedOverview().overview(caller.getId(), true);

        assertThat(memberUsage(response, caller)).isEqualTo(new TeamMemberUsageResponse(
                caller.getId(), caller.getDisplayName(), User.Status.ACTIVE, 9_999, 9_999, 9_999,
                NOW, null, null, null, null));
        assertThat(memberUsage(response, zero)).isEqualTo(new TeamMemberUsageResponse(
                zero.getId(), zero.getDisplayName(), User.Status.ACTIVE, 0, 0, 0,
                null, 0L, 0L, 0L, 0L));
        assertThat(memberUsage(response, over)).isEqualTo(new TeamMemberUsageResponse(
                over.getId(), over.getDisplayName(), User.Status.ACTIVE, 30, 30, 30,
                NOW, 10L, 20L, 0L, 0L));
        assertThat(memberUsage(response, unused)).isEqualTo(new TeamMemberUsageResponse(
                unused.getId(), unused.getDisplayName(), User.Status.ACTIVE, 0, 0, 0,
                null, 50L, 70L, 50L, 70L));
        assertThat(memberUsage(response, weeklyOnly)).isEqualTo(new TeamMemberUsageResponse(
                weeklyOnly.getId(), weeklyOnly.getDisplayName(), User.Status.ACTIVE, 40, 40, 40,
                NOW, null, 100L, null, 60L));
    }

    @Test
    void usersWithoutATeamReceiveAnEmptyMemberList() {
        User caller = member("caller@example.com", "Alice", null);

        QuotaOverviewResponse response = overview.overview(caller.getId(), true);

        assertThat(response.team()).isNull();
        assertThat(response.teamQuota()).isNull();
        assertThat(response.teamMembers()).isEmpty();
    }

    @Test
    void plainOverviewOmitsTeamMembersForLegacyClients() throws Exception {
        Team team = team("Legacy overview");
        User caller = member("caller@example.com", "Alice", team);
        personalLimits(caller, 100L, 200L);
        Instant recent = Instant.now().minusSeconds(10).truncatedTo(ChronoUnit.SECONDS);
        event(caller, team, 11, recent, recent);

        String content = mvc.perform(get(OVERVIEW)
                        .header(HttpHeaders.AUTHORIZATION, bearer(caller))
                        .with(ClientBindingFixture.forUser(jdbc, caller.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.usage.fiveHourUsedTokens").value(11))
                .andExpect(jsonPath("$.usage.fiveHourRemainingTokens").value(89))
                .andExpect(jsonPath("$.teamQuota.fiveHourUsedTokens").value(11))
                .andReturn().getResponse().getContentAsString();
        assertThat(objectMapper.readTree(content).has("teamMembers")).isFalse();
        assertThat(overview.overview(caller.getId()).teamMembers()).isNull();

        String explicitFalse = mvc.perform(get(OVERVIEW)
                        .queryParam("includeTeamMembers", "false")
                        .header(HttpHeaders.AUTHORIZATION, bearer(caller))
                        .with(ClientBindingFixture.forUser(jdbc, caller.getId())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(objectMapper.readTree(explicitFalse).has("teamMembers")).isFalse();
    }

    @Test
    void clientOverviewStillRequiresAuthenticationAndAnActiveBinding() throws Exception {
        mvc.perform(get(OVERVIEW).queryParam("includeTeamMembers", "true"))
                .andExpect(status().isUnauthorized());
        User caller = member("caller@example.com", "Alice", team("Unbound team"));
        mvc.perform(get(OVERVIEW).queryParam("includeTeamMembers", "true")
                        .header(HttpHeaders.AUTHORIZATION, bearer(caller)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SESSION_BINDING_REQUIRED"));
    }

    private Team team(String name) {
        return teams.saveAndFlush(new Team(name, "Usage test", Team.Status.ACTIVE, 100, 100, NOW));
    }

    private User member(String email, String displayName, Team team) {
        User user = new User(email, "synthetic-unused-hash", displayName);
        if (team != null) {
            user.updateQuotaPolicy(team.getId(), null, null, NOW);
        }
        return users.saveAndFlush(user);
    }

    private void event(User member, Team team, long tokens, Instant occurredAt, Instant receivedAt) {
        events.saveAndFlush(new CodexUsageEvent(UUID.randomUUID().toString(), member.getId(),
                team.getId(), tokens, occurredAt, receivedAt, "TOKEN_USAGE_RECORD"));
    }

    private void personalLimits(User member, Long fiveHour, Long weekly) {
        member.updateTokenLimits(fiveHour, weekly, NOW);
        users.saveAndFlush(member);
    }

    private QuotaOverviewService fixedOverview() {
        return new QuotaOverviewService(users, teams, events, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private TeamMemberUsageResponse memberUsage(QuotaOverviewResponse response, User user) {
        return response.teamMembers().stream().filter(member -> member.userId().equals(user.getId()))
                .findFirst().orElseThrow();
    }

    private void assertMemberMatchesTeamTotals(QuotaOverviewResponse response, User user) {
        TeamMemberUsageResponse member = memberUsage(response, user);
        assertThat(member.fiveHourUsedTokens()).isEqualTo(response.teamQuota().fiveHourUsedTokens());
        assertThat(member.weeklyUsedTokens()).isEqualTo(response.teamQuota().weeklyUsedTokens());
        assertThat(member.totalUsedTokens()).isEqualTo(response.teamQuota().totalUsedTokens());
    }

    private String bearer(User user) {
        return "Bearer " + jwtService.create(user.getId(), user.getRole().name(), user.getSessionVersion());
    }
}
