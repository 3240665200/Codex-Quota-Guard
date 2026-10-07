package com.codexquotaguard.backend.usage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.codexquotaguard.backend.controlplane.ControlPlaneAuthSession;
import com.codexquotaguard.backend.controlplane.ControlPlaneException;
import com.codexquotaguard.backend.controlplane.ControlPlaneGateway;
import com.codexquotaguard.backend.controlplane.PersonalQuotaOverview;
import com.codexquotaguard.backend.controlplane.QuotaMeasurementStatus;
import com.codexquotaguard.backend.controlplane.QuotaPolicy;
import com.codexquotaguard.backend.controlplane.QuotaUsage;
import com.codexquotaguard.backend.controlplane.SystemUser;
import com.codexquotaguard.backend.system.SystemSessionService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

class CodexUsageReportingServiceTest {

    @TempDir Path temporaryDirectory;

    @Test
    void baselinesExistingOfficialSessionsThenRetriesNewEventsUntilAccepted() throws Exception {
        var gateway = new Gateway();
        var systemSession = new SystemSessionService(gateway);
        systemSession.login("alice@example.com", "password");
        Path officialHome = temporaryDirectory.resolve("official-codex-home");
        Path stateRoot = temporaryDirectory.resolve("quota-guard-state");
        Path sessionFile = officialHome.resolve("sessions/2026/run.jsonl");
        Files.createDirectories(sessionFile.getParent());
        Files.writeString(sessionFile, record("old-response", 10), StandardCharsets.UTF_8);

        var store = new ManagedCodexUsageStateStore(new ObjectMapper());
        var reporter = new CodexUsageReportingService(systemSession, store, officialHome, stateRoot);
        reporter.scanAndReportNow();
        assertThat(reporter.currentStatus().state()).isEqualTo("BASELINE_READY");
        assertThat(gateway.reportedEventIds).isEmpty();
        assertThat(store.stateFile(stateRoot.resolve(gateway.user.id().toString()))).exists();

        Files.writeString(sessionFile, record("new-response", 20), StandardCharsets.UTF_8,
                StandardOpenOption.APPEND);
        gateway.rejectReports = true;
        assertThatThrownBy(reporter::scanAndReportNow).isInstanceOf(RuntimeException.class);
        assertThat(reporter.currentStatus().state()).isEqualTo("REPORT_FAILED");
        assertThat(gateway.reportedEventIds).hasSize(1);

        gateway.rejectReports = false;
        reporter.scanAndReportNow();
        assertThat(reporter.currentStatus().state()).isEqualTo("REPORTED");
        assertThat(reporter.currentStatus().lastReportedEvents()).isEqualTo(1);
        assertThat(reporter.currentStatus().lastReportedAt()).isNotNull();
        assertThat(gateway.reportedEventIds).hasSize(2);
        assertThat(gateway.reportedEventIds.get(1)).isEqualTo(gateway.reportedEventIds.get(0));
        reporter.scanAndReportNow();
        assertThat(gateway.reportedEventIds).hasSize(2);
    }

    @Test
    void firstRunDoesNotBillAnOldPendingCumulativeFallbackAfterItsFileSettles() throws Exception {
        var gateway = new Gateway();
        var systemSession = new SystemSessionService(gateway);
        systemSession.login("alice@example.com", "password");
        Path officialHome = temporaryDirectory.resolve("official-codex-home");
        Path stateRoot = temporaryDirectory.resolve("quota-guard-state");
        Path sessionFile = officialHome.resolve("sessions/2026/legacy.jsonl");
        Files.createDirectories(sessionFile.getParent());
        Files.writeString(sessionFile, legacySnapshot(10) + legacySnapshot(20), StandardCharsets.UTF_8);
        var reporter = new CodexUsageReportingService(systemSession,
                new ManagedCodexUsageStateStore(new ObjectMapper()), officialHome, stateRoot);

        reporter.scanAndReportNow();
        Files.setLastModifiedTime(sessionFile, java.nio.file.attribute.FileTime.from(
                java.time.Instant.now().minusSeconds(3600)));
        reporter.scanAndReportNow();

        assertThat(gateway.reportedEventIds).isEmpty();
    }

    @Test
    void reportsAContentFreeScannerFailureWhenItsStateCannotBeSaved() throws Exception {
        var gateway = new Gateway();
        var systemSession = new SystemSessionService(gateway);
        systemSession.login("alice@example.com", "password");
        Path blockedRoot = temporaryDirectory.resolve("not-a-directory");
        Files.writeString(blockedRoot, "occupied");
        var reporter = new CodexUsageReportingService(systemSession,
                new ManagedCodexUsageStateStore(new ObjectMapper()),
                temporaryDirectory.resolve("official-home"), blockedRoot);

        assertThatThrownBy(reporter::scanAndReportNow).isInstanceOf(RuntimeException.class);
        assertThat(reporter.currentStatus().state()).isEqualTo("SCAN_FAILED");
    }

    private static String legacySnapshot(int total) {
        return "{\"type\":\"event_msg\",\"timestamp\":\"2026-10-06T00:00:00Z\","
                + "\"payload\":{\"type\":\"token_count\",\"info\":{\"total_token_usage\":{"
                + "\"input_tokens\":" + total + ",\"cached_input_tokens\":0,"
                + "\"cache_write_input_tokens\":0,\"output_tokens\":0,"
                + "\"reasoning_output_tokens\":0,\"total_tokens\":" + total + "}}}}\n";
    }

    private static String record(String responseId, int totalTokens) {
        return "{\"type\":\"token_usage_record\",\"timestamp\":\"2026-10-06T00:00:00Z\","
                + "\"payload\":{\"response_id\":\"" + responseId + "\",\"usage\":{"
                + "\"input_tokens\":3,\"cached_input_tokens\":0,\"cache_write_input_tokens\":0,"
                + "\"output_tokens\":7,\"reasoning_output_tokens\":0,\"total_tokens\":"
                + totalTokens + "}}}\n";
    }

    private static final class Gateway implements ControlPlaneGateway {
        private final SystemUser user = new SystemUser(UUID.randomUUID(), "alice@example.com", "Alice",
                "USER", "ACTIVE", null, null, null, null, null, null, null);
        private final PersonalQuotaOverview overview = new PersonalQuotaOverview(user, null,
                new QuotaPolicy(1_000L, 5_000L, "USER", "USER"),
                new QuotaUsage(QuotaMeasurementStatus.CLIENT_REPORTED, 0L, 0L, 1_000L,
                        5_000L, null, null, "2026-10-06T00:00:00Z"), true, null);
        private final List<String> reportedEventIds = new ArrayList<>();
        private boolean rejectReports;

        @Override public ControlPlaneAuthSession login(String email, String password) {
            return new ControlPlaneAuthSession("access-secret", "refresh-secret", user);
        }
        @Override public ControlPlaneAuthSession refresh(String refreshToken) { return login("", ""); }
        @Override public SystemUser current(String accessToken) { return user; }
        @Override public PersonalQuotaOverview quotaOverview(String accessToken) { return overview; }
        @Override public PersonalQuotaOverview reportUsage(String accessToken, List<CodexUsageEvent> events) {
            assertThat(events).hasSize(1);
            reportedEventIds.add(events.get(0).eventId());
            if (rejectReports) throw ControlPlaneException.unavailable();
            return overview;
        }
        @Override public void logout(String accessToken, String refreshToken) { }
    }
}
