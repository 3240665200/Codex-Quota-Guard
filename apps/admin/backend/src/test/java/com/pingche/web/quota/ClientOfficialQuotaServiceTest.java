package com.pingche.web.quota;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.pingche.web.officialsession.OfficialQuotaPollingService;
import com.pingche.web.team.Team;
import com.pingche.web.team.TeamRepository;
import com.pingche.web.user.User;
import com.pingche.web.user.UserRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ClientOfficialQuotaServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-27T04:00:00Z");
    private final UserRepository users = mock(UserRepository.class);
    private final TeamRepository teams = mock(TeamRepository.class);
    private final OfficialAccountRepository accounts = mock(OfficialAccountRepository.class);
    private final OfficialQuotaPollingService polling = mock(OfficialQuotaPollingService.class);
    private final ClientOfficialQuotaService service = new ClientOfficialQuotaService(users, teams, accounts, polling, Clock.fixed(NOW, ZoneOffset.UTC));
    private User user;
    private Team team;
    private OfficialAccount account;

    @BeforeEach void setup() {
        user = new User("member@example.com", "synthetic-unused", "Member");
        team = new Team("Team", "", Team.Status.ACTIVE, 100, 100, NOW);
        account = new OfficialAccount("Shared official account", 0, null, NOW);
        team.updateTokenLimits(null, null, null, account.getId(), NOW);
        user.updateQuotaPolicy(team.getId(), null, null, NOW);
        when(users.findById(user.getId())).thenReturn(Optional.of(user));
        when(teams.findById(team.getId())).thenReturn(Optional.of(team));
        when(accounts.findById(account.getId())).thenReturn(Optional.of(account));
    }

    @Test void everyMemberReadsTheTeamsServerSnapshotWithoutAReporterOrDesktopUpload() {
        when(polling.read(account.getId())).thenReturn(state("SUCCESS", NOW, true));
        var result = service.read(user.getId());
        assertThat(result.source()).isEqualTo("SERVER");
        assertThat(result.accountLabel()).isEqualTo("Shared official account");
        assertThat(result.windows().get(0).usedPercent()).isEqualByComparingTo("27.5");
        assertThat(result.stale()).isFalse();
        verify(polling).read(account.getId());
        verifyNoMoreInteractions(polling);
    }

    @Test void upstreamFailureRetainsTheLastSnapshotWithAnExplicitStaleFlag() {
        when(polling.read(account.getId())).thenReturn(state("RETRY_WAIT", NOW, true));
        var result = service.read(user.getId());
        assertThat(result.stale()).isTrue();
        assertThat(result.windows()).hasSize(1);
    }

    @Test void stoppedSchedulerCannotMakeAnOldSuccessfulSnapshotAppearLive() {
        when(polling.read(account.getId())).thenReturn(state("SUCCESS", NOW.minusSeconds(601), true));
        assertThat(service.read(user.getId()).stale()).isTrue();
    }

    @Test void missingOfficialAuthorizationHasEmptyWindowsRatherThanZeroUsage() {
        when(polling.read(account.getId())).thenReturn(new OfficialQuotaPollingService.Response(true, 300, "AUTH_REQUIRED", null, null, null, null, 0, null));
        var result = service.read(user.getId());
        assertThat(result.status()).isEqualTo("AUTH_REQUIRED");
        assertThat(result.windows()).isEmpty();
        assertThat(result.fetchedAt()).isNull();
    }

    @Test void usersWithoutATeamCannotReadAnUnrelatedOfficialAccount() {
        user.updateQuotaPolicy(null, null, null, NOW);
        var result = service.read(user.getId());
        assertThat(result.status()).isEqualTo("NOT_LINKED");
        assertThat(result.windows()).isEmpty();
        verifyNoInteractions(polling);
    }

    private OfficialQuotaPollingService.Response state(String status, Instant fetched, boolean enabled) {
        var quota = new OfficialQuotaSnapshotResponse("pro", List.of(new OfficialQuotaSnapshotRequest.Window(
                "codex", "primary", 300, new BigDecimal("27.5"), NOW.plusSeconds(3600))), fetched, fetched);
        return new OfficialQuotaPollingService.Response(enabled, 300, status, NOW, fetched, NOW.plusSeconds(300), null, 0, quota);
    }
}
