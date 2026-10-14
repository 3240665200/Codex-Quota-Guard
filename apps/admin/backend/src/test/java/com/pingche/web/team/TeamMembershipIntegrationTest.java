package com.pingche.web.team;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pingche.web.auth.JwtService;
import com.pingche.web.auth.RefreshSession;
import com.pingche.web.auth.RefreshSessionRepository;
import com.pingche.web.quota.CodexUsageEvent;
import com.pingche.web.quota.CodexUsageEventRepository;
import com.pingche.web.user.User;
import com.pingche.web.user.UserRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:pingche-team-members;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TeamMembershipIntegrationTest {
    private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.MILLIS);
    private static final Instant RESET_AT = NOW.minusSeconds(600);

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JwtService jwt;
    @Autowired UserRepository users;
    @Autowired TeamRepository teams;
    @Autowired RefreshSessionRepository sessions;
    @Autowired CodexUsageEventRepository events;
    private Team target;
    private Team other;
    private User admin;

    @BeforeEach
    void setup() {
        events.deleteAll();
        sessions.deleteAll();
        users.deleteAll();
        teams.deleteAll();
        target = teams.saveAndFlush(new Team("Target", "Keep this policy", Team.Status.ACTIVE, 83, 91, NOW));
        other = teams.saveAndFlush(new Team("Other", "", Team.Status.ACTIVE, 100, 100, NOW));
        admin = users.saveAndFlush(User.bootstrapAdministrator("admin@example.com", "synthetic-unused-hash", "Admin"));
    }

    @Test
    void requiresAdministratorAndRejectsAnonymousOrOrdinaryUsers() throws Exception {
        User member = member("ordinary", null);
        String body = body(List.of(change(member, null, true)));
        mvc.perform(put(url(target.getId())).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        mvc.perform(put(url(target.getId())).header(HttpHeaders.AUTHORIZATION, token(member))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        assertThat(reload(member).getTeamId()).isNull();
    }

    @Test
    void appliesOnlyExplicitChangesPreservingPersonalPolicyHistoryAndUnchangedSessions() throws Exception {
        User added = member("added", null);
        User transferred = member("transferred", other.getId());
        transferred.updateAdministration(User.Status.DISABLED, User.Role.ADMIN);
        transferred = users.saveAndFlush(transferred);
        User removed = member("removed", target.getId());
        User kept = member("kept", target.getId());
        User untouched = member("untouched", target.getId());
        List<User> participants = List.of(added, transferred, removed, kept, untouched);
        List<RefreshSession> refresh = participants.stream().map(this::refreshSession).toList();
        String addedToken = token(added), removedToken = token(removed), keptToken = token(kept);
        events.saveAndFlush(new CodexUsageEvent("a".repeat(64), transferred.getId(), other.getId(),
                120, NOW, NOW, "TOKEN_USAGE_RECORD"));

        update(target.getId(), List.of(change(removed, target.getId(), false),
                change(kept, target.getId(), true), change(transferred, other.getId(), true), change(added, null, true)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(target.getId().toString()))
                .andExpect(jsonPath("$.memberCount").value(4)).andExpect(jsonPath("$.fiveHourQuotaPercent").value(83))
                .andExpect(jsonPath("$.weeklyQuotaPercent").value(91));

        assertThat(reload(added).getTeamId()).isEqualTo(target.getId());
        assertThat(reload(transferred).getTeamId()).isEqualTo(target.getId());
        assertThat(reload(removed).getTeamId()).isNull();
        assertThat(reload(kept).getTeamId()).isEqualTo(target.getId());
        assertThat(reload(untouched).getTeamId()).isEqualTo(target.getId());
        for (int index = 0; index < participants.size(); index++) {
            User before = participants.get(index), after = reload(before);
            assertThat(after.getFiveHourTokenLimit()).isEqualTo(before.getFiveHourTokenLimit());
            assertThat(after.getWeeklyTokenLimit()).isEqualTo(before.getWeeklyTokenLimit());
            assertThat(after.getFiveHourQuotaPercent()).isEqualTo(before.getFiveHourQuotaPercent());
            assertThat(after.getWeeklyQuotaPercent()).isEqualTo(before.getWeeklyQuotaPercent());
            assertThat(after.getQuotaResetAt()).isEqualTo(RESET_AT);
            assertThat(after.getRole()).isEqualTo(before.getRole());
            assertThat(after.getStatus()).isEqualTo(before.getStatus());
            boolean changed = index < 3;
            assertThat(after.getSessionVersion()).isEqualTo(before.getSessionVersion() + (changed ? 1 : 0));
            assertThat(sessions.findById(refresh.get(index).getId()).orElseThrow().isUsable(NOW)).isEqualTo(!changed);
        }
        assertThat(events.findById("a".repeat(64)).orElseThrow().getTeamId()).isEqualTo(other.getId());
        mvc.perform(get("/api/v1/users/me").header(HttpHeaders.AUTHORIZATION, addedToken)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/users/me").header(HttpHeaders.AUTHORIZATION, removedToken)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/users/me").header(HttpHeaders.AUTHORIZATION, keptToken)).andExpect(status().isOk());
    }

    @Test
    void staleExpectedMembershipRejectsTheWholeBatchBeforeChangingUsersOrSessions() throws Exception {
        User first = member("first", target.getId());
        User stale = member("stale", other.getId());
        RefreshSession firstSession = refreshSession(first), staleSession = refreshSession(stale);
        update(target.getId(), List.of(change(first, target.getId(), false), change(stale, null, true)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("TEAM_MEMBERSHIP_CONFLICT"));
        assertUnchanged(first, firstSession);
        assertUnchanged(stale, staleSession);
        assertThat(users.countByTeamId(target.getId())).isEqualTo(1);
    }

    @Test
    void removalCannotClearAnotherTeamsMembershipEvenWhenItsExpectedTeamMatches() throws Exception {
        User first = member("first", target.getId());
        User outsider = member("outsider", other.getId());
        RefreshSession firstSession = refreshSession(first), outsiderSession = refreshSession(outsider);
        update(target.getId(), List.of(change(first, target.getId(), false), change(outsider, other.getId(), false)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("TEAM_MEMBERSHIP_CONFLICT"));
        assertUnchanged(first, firstSession);
        assertUnchanged(outsider, outsiderSession);
    }

    @Test
    void nonexistentUserRejectsTheWholeBatch() throws Exception {
        User first = member("first", null);
        RefreshSession session = refreshSession(first);
        update(target.getId(), List.of(change(first, null, true), new Change(UUID.randomUUID(), null, true)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        assertUnchanged(first, session);
        assertThat(users.countByTeamId(target.getId())).isZero();
    }

    @Test
    void disabledTargetRejectsRealAdditionAndRollsBackRemovalInTheSameBatch() throws Exception {
        disableTarget();
        User removed = member("removed", target.getId());
        User added = member("added", null);
        User transferred = member("transferred", other.getId());
        RefreshSession removedSession = refreshSession(removed), addedSession = refreshSession(added);
        RefreshSession transferredSession = refreshSession(transferred);
        for (User joining : List.of(added, transferred)) {
            update(target.getId(), List.of(change(removed, target.getId(), false), change(joining, joining.getTeamId(), true)))
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("TEAM_UNAVAILABLE"));
            assertUnchanged(removed, removedSession);
            assertUnchanged(added, addedSession);
            assertUnchanged(transferred, transferredSession);
        }
    }

    @Test
    void disabledTargetAllowsRemovalAndExistingMemberNoOp() throws Exception {
        disableTarget();
        User removed = member("removed", target.getId());
        User kept = member("kept", target.getId());
        RefreshSession removedSession = refreshSession(removed), keptSession = refreshSession(kept);
        update(target.getId(), List.of(change(removed, target.getId(), false), change(kept, target.getId(), true)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DISABLED"))
                .andExpect(jsonPath("$.memberCount").value(1));
        assertThat(reload(removed).getTeamId()).isNull();
        assertThat(reload(removed).getSessionVersion()).isEqualTo(removed.getSessionVersion() + 1);
        assertThat(sessions.findById(removedSession.getId()).orElseThrow().isUsable(NOW)).isFalse();
        assertUnchanged(kept, keptSession);
    }

    @Test
    void noOpChangesDoNotInvalidateSessionsOrTouchUsers() throws Exception {
        User member = member("member", target.getId());
        User unassigned = member("unassigned", null);
        RefreshSession memberSession = refreshSession(member), unassignedSession = refreshSession(unassigned);
        update(target.getId(), List.of(change(member, target.getId(), true), change(unassigned, null, false)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.memberCount").value(1));
        assertUnchanged(member, memberSession);
        assertUnchanged(unassigned, unassignedSession);
    }

    @Test
    void rejectsEmptyNullMalformedAndIncompleteChanges() throws Exception {
        User member = member("member", null);
        RefreshSession session = refreshSession(member);
        String id = member.getId().toString();
        for (String body : List.of("{}", "{\"changes\":null}", "{\"changes\":[]}", "{\"changes\":[null]}",
                "{\"changes\":[{}]}",
                "{\"changes\":[{\"userId\":\"" + id + "\",\"selected\":true}]}",
                "{\"changes\":[{\"userId\":\"" + id + "\",\"expectedTeamId\":null}]}",
                "{\"changes\":[{\"userId\":null,\"expectedTeamId\":null,\"selected\":true}]}",
                "{\"changes\":[{\"userId\":\"not-a-uuid\",\"expectedTeamId\":null,\"selected\":true}]}")) {
            send(target.getId(), body).andExpect(status().isBadRequest());
            assertUnchanged(member, session);
        }
    }

    @Test
    void duplicateUsersAreInvalidEvenWithConflictingSelections() throws Exception {
        User member = member("member", null);
        RefreshSession session = refreshSession(member);
        update(target.getId(), List.of(change(member, null, true), change(member, null, false)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        assertUnchanged(member, session);
    }

    @Test
    void rejectsMoreThan100ChangesWithoutApplyingAny() throws Exception {
        User member = member("member", null);
        RefreshSession session = refreshSession(member);
        List<Change> changes = new ArrayList<>();
        changes.add(change(member, null, true));
        IntStream.range(0, 100).forEach(index -> changes.add(new Change(UUID.randomUUID(), null, true)));
        update(target.getId(), changes).andExpect(status().isBadRequest());
        assertUnchanged(member, session);
    }

    @Test
    void acceptsExactly100ExplicitChanges() throws Exception {
        List<User> members = IntStream.range(0, 100)
                .mapToObj(index -> new User("batch" + index + "@example.com", "synthetic-unused-hash", "Batch " + index)).toList();
        users.saveAllAndFlush(members);
        update(target.getId(), members.stream().map(member -> change(member, null, true)).toList())
                .andExpect(status().isOk()).andExpect(jsonPath("$.memberCount").value(100));
        assertThat(users.countByTeamId(target.getId())).isEqualTo(100);
        for (User member : members) assertThat(reload(member).getSessionVersion()).isEqualTo(1);
    }

    @Test
    void rejectsMissingTargetTeamWithoutChangingMembership() throws Exception {
        User member = member("member", null);
        RefreshSession session = refreshSession(member);
        update(UUID.randomUUID(), List.of(change(member, null, true)))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("TEAM_NOT_FOUND"));
        assertUnchanged(member, session);
    }

    private User member(String name, UUID teamId) {
        User user = new User(name + "@example.com", "synthetic-unused-hash", name);
        user.updateQuotaPolicy(teamId, 37, null, NOW);
        user.updateTokenLimits(12_345L, 98_765L, NOW);
        user.resetQuota(RESET_AT);
        return users.saveAndFlush(user);
    }

    private RefreshSession refreshSession(User user) {
        String hash = UUID.randomUUID().toString().replace("-", "").repeat(2);
        return sessions.saveAndFlush(new RefreshSession(UUID.randomUUID(), user.getId(), UUID.randomUUID(),
                hash, NOW.plusSeconds(3600), NOW, user.getSessionVersion()));
    }

    private void assertUnchanged(User before, RefreshSession session) {
        User after = reload(before);
        assertThat(after.getTeamId()).isEqualTo(before.getTeamId());
        assertThat(after.getSessionVersion()).isEqualTo(before.getSessionVersion());
        assertThat(after.getUpdatedAt()).isEqualTo(before.getUpdatedAt());
        assertThat(sessions.findById(session.getId()).orElseThrow().isUsable(NOW)).isTrue();
    }

    private void disableTarget() {
        target.update(target.getName(), target.getDescription(), Team.Status.DISABLED,
                target.getFiveHourQuotaPercent(), target.getWeeklyQuotaPercent(), NOW);
        target = teams.saveAndFlush(target);
    }

    private User reload(User user) { return users.findById(user.getId()).orElseThrow(); }
    private Change change(User user, UUID expectedTeamId, boolean selected) { return new Change(user.getId(), expectedTeamId, selected); }
    private String token(User user) { return "Bearer " + jwt.create(user.getId(), user.getRole().name(), user.getSessionVersion()); }
    private String url(UUID id) { return "/api/v1/admin/teams/" + id + "/members"; }
    private String body(List<Change> changes) { return json.writeValueAsString(Map.of("changes", changes)); }
    private ResultActions update(UUID id, List<Change> changes) throws Exception { return send(id, body(changes)); }
    private ResultActions send(UUID id, String body) throws Exception {
        return mvc.perform(put(url(id)).header(HttpHeaders.AUTHORIZATION, token(admin))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }
    private record Change(UUID userId, UUID expectedTeamId, boolean selected) { }
}
