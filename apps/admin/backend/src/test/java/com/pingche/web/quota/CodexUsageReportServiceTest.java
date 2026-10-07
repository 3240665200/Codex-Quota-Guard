package com.pingche.web.quota;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.pingche.web.user.User;
import com.pingche.web.user.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class CodexUsageReportServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-06T00:00:00Z");
    private static final String EVENT_ID = "codex-local-usage:v1:" + "a".repeat(64);

    private final UserRepository users = mock(UserRepository.class);
    private final CodexUsageEventRepository events = mock(CodexUsageEventRepository.class);
    private final QuotaOverviewService overview = mock(QuotaOverviewService.class);
    private final CodexUsageReportService service = new CodexUsageReportService(users,
            events, overview, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void deduplicatesRepeatedLocalEventWithinOneReport() {
        UUID userId = UUID.randomUUID();
        activeUser(userId);
        var event = new CodexUsageReportRequest.Event(EVENT_ID, NOW, 120,
                CodexUsageReportRequest.Source.TOKEN_USAGE_RECORD);

        service.report(userId, new CodexUsageReportRequest(List.of(event, event)));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<CodexUsageEvent>> captor = ArgumentCaptor.forClass(List.class);
        verify(events).saveAllAndFlush(captor.capture());
        org.assertj.core.api.Assertions.assertThat(captor.getValue()).hasSize(1);
        org.assertj.core.api.Assertions.assertThat(captor.getValue().get(0).getTotalTokens()).isEqualTo(120);
    }

    @Test
    void acceptsUsageForAnActiveSystemUserWithoutManagedCodexAccount() {
        UUID userId = UUID.randomUUID();
        User user = mock(User.class);
        when(users.findByIdForUpdate(userId)).thenReturn(Optional.of(user));
        when(user.getStatus()).thenReturn(User.Status.ACTIVE);
        var event = new CodexUsageReportRequest.Event(EVENT_ID, NOW, 120,
                CodexUsageReportRequest.Source.TOKEN_USAGE_RECORD);

        service.report(userId, new CodexUsageReportRequest(List.of(event)));

        verify(events).saveAllAndFlush(anyList());
    }

    @Test
    void recordsLocalUsageIndependentlyOfTeamPolicy() {
        UUID userId = UUID.randomUUID();
        User user = mock(User.class);
        when(users.findByIdForUpdate(userId)).thenReturn(Optional.of(user));
        when(user.getStatus()).thenReturn(User.Status.ACTIVE);

        service.report(userId, new CodexUsageReportRequest(List.of(
                new CodexUsageReportRequest.Event(EVENT_ID, NOW, 120,
                        CodexUsageReportRequest.Source.TOKEN_USAGE_RECORD))));

        verify(events).saveAllAndFlush(anyList());
    }

    private void activeUser(UUID userId) {
        User user = mock(User.class);
        when(users.findByIdForUpdate(userId)).thenReturn(Optional.of(user));
        when(user.getStatus()).thenReturn(User.Status.ACTIVE);
    }
}
