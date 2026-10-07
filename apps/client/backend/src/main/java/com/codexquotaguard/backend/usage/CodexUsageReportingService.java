package com.codexquotaguard.backend.usage;

import com.codexquotaguard.backend.system.SystemSessionService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
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

    private final SystemSessionService systemSessionService;
    private final ManagedCodexUsageStateStore stateStore;
    private final Path officialHome;
    private final Path stateRoot;
    private volatile UsageScannerStatus status = UsageScannerStatus.signedOut();

    @Autowired
    public CodexUsageReportingService(
            SystemSessionService systemSessionService,
            ManagedCodexUsageStateStore stateStore,
            @Value("${quota-guard.codex.official-home:${user.home}/.codex}") String officialHome,
            @Value("${quota-guard.codex.home-root:${user.home}/.codex-quota-guard}") String stateRoot
    ) {
        this(systemSessionService, stateStore, Path.of(officialHome), Path.of(stateRoot));
    }

    CodexUsageReportingService(SystemSessionService systemSessionService,
            ManagedCodexUsageStateStore stateStore, Path officialHome, Path stateRoot) {
        this.systemSessionService = systemSessionService;
        this.stateStore = stateStore;
        this.officialHome = officialHome.toAbsolutePath().normalize();
        this.stateRoot = stateRoot.toAbsolutePath().normalize();
    }

    @Scheduled(
            fixedDelayString = "${quota-guard.codex.usage-scan-delay-ms:3000}",
            initialDelayString = "${quota-guard.codex.usage-scan-initial-delay-ms:3000}"
    )
    void scheduledScan() {
        try {
            scanAndReportNow();
        } catch (RuntimeException ignored) {
            // Preserve the cursor for idempotent retry; never log session lines.
            if (!"REPORT_FAILED".equals(status.state())) {
                status = new UsageScannerStatus("SCAN_FAILED", Instant.now(), status.lastReportedAt(),
                        status.lastReportedEvents(), List.of());
            }
        }
    }

    public UsageScannerStatus currentStatus() {
        return status;
    }

    public synchronized void scanAndReportNow() {
        try {
            scanAndReportInternal();
        } catch (RuntimeException exception) {
            if (!"REPORT_FAILED".equals(status.state())) {
                status = new UsageScannerStatus("SCAN_FAILED", Instant.now(),
                        status.lastReportedAt(), status.lastReportedEvents(), List.of());
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
        CodexUsageScanResult scan = scanner.scan(previousState);
        List<String> issues = scan.issues().stream().map(issue -> issue.code().name()).toList();
        if (scan.events().isEmpty()) {
            stateStore.save(stateHome, scan.nextState());
            status = new UsageScannerStatus(sessionsAvailable ? "UP_TO_DATE" : "WAITING_FOR_SESSIONS",
                    scanAt, status.lastReportedAt(), 0, issues);
            return;
        }
        for (int offset = 0; offset < scan.events().size(); offset += MAX_EVENTS_PER_REPORT) {
            if (!systemSessionService.localUserId().filter(userId::equals).isPresent()) {
                return;
            }
            int end = Math.min(offset + MAX_EVENTS_PER_REPORT, scan.events().size());
            List<CodexUsageEvent> batch = List.copyOf(scan.events().subList(offset, end));
            try {
                systemSessionService.reportUsage(userId, batch);
            } catch (RuntimeException exception) {
                status = new UsageScannerStatus("REPORT_FAILED", scanAt,
                        status.lastReportedAt(), 0, issues);
                throw exception;
            }
        }
        if (systemSessionService.localUserId().filter(userId::equals).isPresent()) {
            stateStore.save(stateHome, scan.nextState());
            status = new UsageScannerStatus("REPORTED", scanAt, Instant.now(),
                    scan.events().size(), issues);
        }
    }

}
