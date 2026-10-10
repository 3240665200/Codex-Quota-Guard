package com.codexquotaguard.backend.usage;

import com.codexquotaguard.backend.system.SystemSessionService;
import com.codexquotaguard.backend.system.ControlPlaneUnavailableException;
import com.codexquotaguard.backend.system.SystemSessionRequiredException;
import com.codexquotaguard.backend.system.CodexAccessDeniedException;
import com.codexquotaguard.backend.system.ControlPlaneRequestRejectedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Observes only official local Codex session logs. The user signs into the
 * official application/CLI directly; this service never reads auth.json.
 */
@Service
public final class CodexUsageReportingService {

    private static final int MAX_EVENTS_PER_REPORT = 100;
    private static final long MAX_EVENT_TOKENS = 100_000_000L;
    private static final Duration MAX_EVENT_AGE = Duration.ofDays(29);
    private static final Duration MAX_FUTURE_SKEW = Duration.ofMinutes(4);

    private final SystemSessionService systemSessionService;
    private final ManagedCodexUsageStateStore stateStore;
    private final Path officialHome;
    private final Path stateRoot;
    private final java.util.function.Supplier<MeterServiceStatus> meterStatus;
    private volatile UsageScannerStatus status = UsageScannerStatus.signedOut();

    @Autowired
    public CodexUsageReportingService(
            SystemSessionService systemSessionService,
            ManagedCodexUsageStateStore stateStore,
            RustMeterStatusReader meter,
            @Value("${quota-guard.codex.official-home:${user.home}/.codex}") String officialHome,
            @Value("${quota-guard.codex.home-root:${user.home}/.codex-quota-guard}") String stateRoot
    ) {
        this(systemSessionService, stateStore, Path.of(officialHome), Path.of(stateRoot), meter::read);
    }

    CodexUsageReportingService(SystemSessionService systemSessionService,
            ManagedCodexUsageStateStore stateStore, Path officialHome, Path stateRoot) {
        this(systemSessionService, stateStore, officialHome, stateRoot, MeterServiceStatus::notInstalled);
    }

    CodexUsageReportingService(SystemSessionService systemSessionService,
            ManagedCodexUsageStateStore stateStore, Path officialHome, Path stateRoot,
            java.util.function.Supplier<MeterServiceStatus> meterStatus) {
        this.systemSessionService = systemSessionService;
        this.stateStore = stateStore;
        this.officialHome = officialHome.toAbsolutePath().normalize();
        this.stateRoot = stateRoot.toAbsolutePath().normalize();
        this.meterStatus = meterStatus;
    }

    @Scheduled(
            fixedDelayString = "${quota-guard.codex.usage-scan-delay-ms:3000}",
            initialDelayString = "${quota-guard.codex.usage-scan-initial-delay-ms:3000}"
    )
    void scheduledScan() {
        try {
            scanAndReportNow();
        } catch (RuntimeException ignored) {
            // scanAndReportNow already set the safe status/error code. Preserve
            // it and the retry cursor; never log session lines or exception text.
        }
    }

    public UsageScannerStatus currentStatus() {
        return status;
    }

    public synchronized void scanAndReportNow() {
        UsageScannerStatus previousStatus = status;
        try {
            scanAndReportInternal();
        } catch (RuntimeException exception) {
            if (status == previousStatus || !"REPORT_FAILED".equals(status.state())) {
                status = new UsageScannerStatus("SCAN_FAILED", Instant.now(),
                        status.lastReportedAt(), status.lastReportedEvents(), List.of(), errorCode(exception));
            }
            throw exception;
        }
    }

    private void scanAndReportInternal() {
        UUID userId = systemSessionService.localUserId().orElse(null);
        if (userId == null) {
            status = UsageScannerStatus.signedOut();
            return;
        }
        Instant scanAt = Instant.now();
        MeterServiceStatus service = meterStatus.get();
        Path stateHome = stateRoot.resolve(userId.toString());
        Path stateFile = stateStore.stateFile(stateHome);
        boolean sessionsAvailable = Files.isDirectory(officialHome.resolve("sessions"));
        CodexSessionUsageScanner scanner = new CodexSessionUsageScanner(officialHome);

        // On first use, old official sessions have unknown ownership. Establish
        // a baseline instead of billing all historical traffic to this user.
        if (!Files.isRegularFile(stateFile)) {
            stateStore.save(stateHome, scanner.baseline());
            status = new UsageScannerStatus(sessionsAvailable ? "BASELINE_READY" : "WAITING_FOR_SESSIONS",
                    scanAt, status.lastReportedAt(), 0, List.of());
            return;
        }
        CodexUsageScanState previousState = stateStore.load(stateHome);
        if (("UP_TO_DATE".equals(service.state()) || "REPORTED".equals(service.state()))
                && scanner.canDelegateToDirectMeter(previousState)) {
            // Keep the cursor intact. New/replaced files and pending legacy
            // cumulative records still require the compatibility scan.
            status = new UsageScannerStatus("SERVICE_ACTIVE", scanAt, status.lastReportedAt(), 0, List.of());
            return;
        }
        CodexUsageScanResult scan = scanner.scan(previousState);
        Map<String, Integer> issueCounts = new LinkedHashMap<>();
        scan.issues().forEach(issue -> addIssue(issueCounts, issue.code().name()));
        List<CodexUsageEvent> reportableEvents = new ArrayList<>();
        Instant oldestAllowedEventAt = scanAt.minus(MAX_EVENT_AGE);
        Instant newestAllowedEventAt = scanAt.plus(MAX_FUTURE_SKEW);
        for (CodexUsageEvent event : scan.events()) {
            if (event.occurredAt().isBefore(oldestAllowedEventAt)) {
                addIssue(issueCounts, "STALE_USAGE_EVENT");
            } else if (event.occurredAt().isAfter(newestAllowedEventAt) || exceedsTokenLimit(event)) {
                addIssue(issueCounts, "INVALID_USAGE_RECORD");
            } else {
                reportableEvents.add(event);
            }
        }
        List<String> issues = List.copyOf(issueCounts.keySet());
        Map<String, Integer> counts = Map.copyOf(issueCounts);
        if (reportableEvents.isEmpty()) {
            stateStore.save(stateHome, scan.nextState());
            status = new UsageScannerStatus(sessionsAvailable ? "UP_TO_DATE" : "WAITING_FOR_SESSIONS",
                    scanAt, status.lastReportedAt(), 0, issues, null, counts);
            return;
        }
        for (int offset = 0; offset < reportableEvents.size(); offset += MAX_EVENTS_PER_REPORT) {
            if (!systemSessionService.localUserId().filter(userId::equals).isPresent()) {
                return;
            }
            int end = Math.min(offset + MAX_EVENTS_PER_REPORT, reportableEvents.size());
            List<CodexUsageEvent> batch = List.copyOf(reportableEvents.subList(offset, end));
            try {
                systemSessionService.reportUsage(userId, batch);
            } catch (RuntimeException exception) {
                status = new UsageScannerStatus("REPORT_FAILED", scanAt,
                        status.lastReportedAt(), 0, issues, errorCode(exception), counts);
                throw exception;
            }
        }
        if (systemSessionService.localUserId().filter(userId::equals).isPresent()) {
            stateStore.save(stateHome, scan.nextState());
            status = new UsageScannerStatus("REPORTED", scanAt, Instant.now(),
                    reportableEvents.size(), issues, null, counts);
        }
    }

    private static boolean exceedsTokenLimit(CodexUsageEvent event) {
        // The central contract validates only the authoritative total. Unsent
        // overlapping component counters must not change billing eligibility.
        return event.totalTokens() > MAX_EVENT_TOKENS;
    }

    private static void addIssue(Map<String, Integer> counts, String code) {
        counts.merge(code, 1, Integer::sum);
    }

    private static String errorCode(RuntimeException exception) {
        if (exception instanceof ControlPlaneUnavailableException) return "CONTROL_PLANE_UNAVAILABLE";
        if (exception instanceof SystemSessionRequiredException) return "SYSTEM_SESSION_REQUIRED";
        if (exception instanceof CodexAccessDeniedException) return "QUOTA_REPORT_REJECTED";
        if (exception instanceof ControlPlaneRequestRejectedException) return "CONTROL_PLANE_REQUEST_REJECTED";
        if (exception instanceof UsageStateStorageException) return "STATE_STORAGE_FAILED";
        return "SCAN_FAILED";
    }

}
