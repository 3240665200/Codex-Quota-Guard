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
import com.codexquotaguard.backend.system.ControlPlaneRequestRejectedException;
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
    void authoritativeTotalIsNotRejectedBecauseUnsentComponentCountersAreLarge() throws Exception {
        var gateway = new Gateway();
        var session = new SystemSessionService(gateway);
        session.login("alice@example.com", "password");
        Path home = temporaryDirectory.resolve("component-counts");
        Path log = home.resolve("sessions/run.jsonl");
        Files.createDirectories(log.getParent());
        Files.writeString(log, "");
        var reporter = new CodexUsageReportingService(session, new ManagedCodexUsageStateStore(new ObjectMapper()),
                home, temporaryDirectory.resolve("state"));
        reporter.scanAndReportNow();
        Files.writeString(log, record("component-count", 30).replace("\"input_tokens\":3", "\"input_tokens\":100000001")
                .replace("2026-10-06T00:00:00Z", java.time.Instant.now().toString()));
        reporter.scanAndReportNow();
        assertThat(gateway.reportedEventIds).hasSize(1);
        assertThat(reporter.currentStatus().state()).isEqualTo("REPORTED");
    }

    @Test
    void skipsExpiredEventsWithoutBlockingNewUsageAndKeepsTheRetryCursor() throws Exception {
        var gateway = new Gateway();
        var session = new SystemSessionService(gateway);
        session.login("alice@example.com", "password");
        Path home = temporaryDirectory.resolve("official");
        Path state = temporaryDirectory.resolve("state");
        Path log = home.resolve("sessions/run.jsonl");
        Files.createDirectories(log.getParent());
        Files.writeString(log, "");
        var reporter = new CodexUsageReportingService(session, new ManagedCodexUsageStateStore(new ObjectMapper()), home, state);
        reporter.scanAndReportNow();
        Files.writeString(log, record("old", 20).replace("2026-10-06T00:00:00Z", "2025-01-01T00:00:00Z")
                + record("current", 30).replace("2026-10-06T00:00:00Z", java.time.Instant.now().toString()));
        reporter.scanAndReportNow();
        assertThat(gateway.reportedEventIds).hasSize(1);
        assertThat(reporter.currentStatus().issues()).contains("STALE_USAGE_EVENT");
        assertThat(reporter.currentStatus().issueCounts()).containsEntry("STALE_USAGE_EVENT", 1);
        reporter.scanAndReportNow();
        assertThat(gateway.reportedEventIds).hasSize(1);
    }

    @Test
    void skipsFutureAndOverLimitEventsAndCommitsCursorWhenNoEventIsReportable() throws Exception {
        var gateway = new Gateway();
        var session = new SystemSessionService(gateway);
        session.login("alice@example.com", "password");
        Path home = temporaryDirectory.resolve("official");
        Path state = temporaryDirectory.resolve("state");
        Path log = home.resolve("sessions/run.jsonl");
        Files.createDirectories(log.getParent());
        Files.writeString(log, "");
        var reporter = new CodexUsageReportingService(session,
                new ManagedCodexUsageStateStore(new ObjectMapper()), home, state);
        reporter.scanAndReportNow();

        Files.writeString(log,
                record("old", 20).replace("2026-10-06T00:00:00Z", "2025-01-01T00:00:00Z")
                        + record("future", 20).replace("2026-10-06T00:00:00Z", "2100-01-01T00:00:00Z")
                        + record("over-limit", 100_000_001));

        reporter.scanAndReportNow();

        assertThat(gateway.reportedEventIds).isEmpty();
        assertThat(reporter.currentStatus().state()).isEqualTo("UP_TO_DATE");
        assertThat(reporter.currentStatus().issues())
                .containsExactlyInAnyOrder("STALE_USAGE_EVENT", "INVALID_USAGE_RECORD");
        assertThat(reporter.currentStatus().issueCounts())
                .containsEntry("STALE_USAGE_EVENT", 1)
                .containsEntry("INVALID_USAGE_RECORD", 2);

        Files.writeString(log, record("valid-after-invalid", 30), StandardOpenOption.APPEND);
        reporter.scanAndReportNow();

        assertThat(gateway.reportedEventIds).hasSize(1);
        assertThat(reporter.currentStatus().state()).isEqualTo("REPORTED");
        assertThat(reporter.currentStatus().issueCounts())
                .doesNotContainKeys("STALE_USAGE_EVENT", "INVALID_USAGE_RECORD");
    }

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
        Path cursor = store.stateFile(stateRoot.resolve(gateway.user.id().toString()));
        assertThat(cursor).exists();

        Files.writeString(sessionFile, record("new-response", 20), StandardCharsets.UTF_8,
                StandardOpenOption.APPEND);
        gateway.rejectReports = true;
        String cursorBeforeRejectedReport = Files.readString(cursor);
        assertThatThrownBy(reporter::scanAndReportNow).isInstanceOf(RuntimeException.class);
        assertThat(reporter.currentStatus().state()).isEqualTo("REPORT_FAILED");
        assertThat(reporter.currentStatus().errorCode()).isEqualTo("CONTROL_PLANE_UNAVAILABLE");
        assertThat(Files.readString(cursor)).isEqualTo(cursorBeforeRejectedReport);
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
    void classifiesRejectedUsageRequestsAndRetainsTheCursorForRetry() throws Exception {
        var gateway = new Gateway();
        var systemSession = new SystemSessionService(gateway);
        systemSession.login("alice@example.com", "password");
        Path officialHome = temporaryDirectory.resolve("official-codex-home");
        Path stateRoot = temporaryDirectory.resolve("quota-guard-state");
        Path sessionFile = officialHome.resolve("sessions/2026/run.jsonl");
        Files.createDirectories(sessionFile.getParent());
        Files.writeString(sessionFile, "");
        var store = new ManagedCodexUsageStateStore(new ObjectMapper());
        var reporter = new CodexUsageReportingService(systemSession, store, officialHome, stateRoot);
        reporter.scanAndReportNow();
        Files.writeString(sessionFile, record("request-rejected", 20), StandardOpenOption.APPEND);
        Path cursor = store.stateFile(stateRoot.resolve(gateway.user.id().toString()));
        String cursorBeforeRejectedReport = Files.readString(cursor);
        gateway.reportFailure = ControlPlaneException.invalidRequest();

        assertThatThrownBy(reporter::scanAndReportNow)
                .isInstanceOf(ControlPlaneRequestRejectedException.class);

        assertThat(reporter.currentStatus().state()).isEqualTo("REPORT_FAILED");
        assertThat(reporter.currentStatus().errorCode()).isEqualTo("CONTROL_PLANE_REQUEST_REJECTED");
        assertThat(Files.readString(cursor)).isEqualTo(cursorBeforeRejectedReport);

        gateway.reportFailure = null;
        reporter.scanAndReportNow();

        assertThat(gateway.reportedEventIds).hasSize(2);
        assertThat(gateway.reportedEventIds.get(0)).isEqualTo(gateway.reportedEventIds.get(1));
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
        reporter.scheduledScan();
        assertThat(reporter.currentStatus().errorCode()).isEqualTo("STATE_STORAGE_FAILED");
    }

    @Test
    void healthyRustMeterOwnsScanningWithoutDiscardingFallbackCursor() throws Exception {
        var gateway = new Gateway();
        var session = new SystemSessionService(gateway);
        session.login("alice@example.com", "password");
        Path officialHome = temporaryDirectory.resolve("official");
        Path stateRoot = temporaryDirectory.resolve("state");
        Path log = officialHome.resolve("sessions/run.jsonl");
        Files.createDirectories(log.getParent());
        Files.writeString(log, record("old", 10));
        var store = new ManagedCodexUsageStateStore(new ObjectMapper());
        var meter = new java.util.concurrent.atomic.AtomicReference<>(MeterServiceStatus.notInstalled());
        var reporter = new CodexUsageReportingService(session, store, officialHome, stateRoot, meter::get);
        reporter.scanAndReportNow();
        Path cursor = store.stateFile(stateRoot.resolve(gateway.user.id().toString()));
        Files.writeString(log, record("new", 20), StandardOpenOption.APPEND);
        reporter.scanAndReportNow();
        String before = Files.readString(cursor);
        gateway.reportedEventIds.clear();
        Files.writeString(log, record("newer", 30), StandardOpenOption.APPEND);
        meter.set(new MeterServiceStatus("UP_TO_DATE", java.time.Instant.now().getEpochSecond(), null, 0, null, null));
        reporter.scanAndReportNow();
        assertThat(reporter.currentStatus().state()).isEqualTo("SERVICE_ACTIVE");
        assertThat(gateway.reportedEventIds).isEmpty();
        assertThat(Files.readString(cursor)).isEqualTo(before);
        meter.set(MeterServiceStatus.offline());
        reporter.scanAndReportNow();
        assertThat(gateway.reportedEventIds).hasSize(1);
        assertThat(reporter.currentStatus().state()).isEqualTo("REPORTED");
    }

    @Test
    void healthyRustDoesNotSuppressNewLegacyFilesOrPendingCumulativeEvents() throws Exception {
        var gateway = new Gateway();
        var session = new SystemSessionService(gateway);
        session.login("alice@example.com", "password");
        Path officialHome = temporaryDirectory.resolve("official");
        Path stateRoot = temporaryDirectory.resolve("state");
        Path direct = officialHome.resolve("sessions/direct.jsonl");
        Files.createDirectories(direct.getParent());
        Files.writeString(direct, record("old", 10));
        var meter = new java.util.concurrent.atomic.AtomicReference<>(MeterServiceStatus.notInstalled());
        var reporter = new CodexUsageReportingService(session, new ManagedCodexUsageStateStore(new ObjectMapper()),
                officialHome, stateRoot, meter::get);
        reporter.scanAndReportNow();
        Files.writeString(direct, record("new", 20), StandardOpenOption.APPEND);
        reporter.scanAndReportNow();
        gateway.reportedEventIds.clear();
        meter.set(new MeterServiceStatus("UP_TO_DATE", java.time.Instant.now().getEpochSecond(), null, 0, null, null));
        Path legacy = direct.resolveSibling("legacy.jsonl");
        Files.writeString(legacy, legacySnapshot(10) + legacySnapshot(20));
        reporter.scanAndReportNow();
        assertThat(gateway.reportedEventIds).isEmpty();
        Files.setLastModifiedTime(legacy, java.nio.file.attribute.FileTime.from(java.time.Instant.now().minusSeconds(60)));
        reporter.scanAndReportNow();
        assertThat(gateway.reportedEventIds).hasSize(1);
        assertThat(gateway.reportedEventIds.get(0)).startsWith("codex-local-cumulative:v1:");
        assertThat(reporter.currentStatus().state()).isEqualTo("REPORTED");
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
        private RuntimeException reportFailure;

        @Override public ControlPlaneAuthSession login(String email, String password) {
            return new ControlPlaneAuthSession("access-secret", "refresh-secret", user);
        }
        @Override public ControlPlaneAuthSession refresh(String refreshToken) { return login("", ""); }
        @Override public SystemUser current(String accessToken) { return user; }
        @Override public PersonalQuotaOverview quotaOverview(String accessToken) { return overview; }
        @Override public int reportUsage(String accessToken, List<CodexUsageEvent> events) {
            assertThat(events).hasSize(1);
            reportedEventIds.add(events.get(0).eventId());
            if (reportFailure != null) throw reportFailure;
            if (rejectReports) throw ControlPlaneException.unavailable();
            return events.size();
        }
        @Override public void logout(String accessToken, String refreshToken) { }
    }
}
