package com.codexquotaguard.backend.usage;

import com.codexquotaguard.backend.system.MeterPairingService;
import com.codexquotaguard.backend.system.SystemSessionService;
import com.codexquotaguard.backend.controlplane.UserUsageEventPage;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping("/api/v1/usage")
public class UsageStatusController {
    private final CodexUsageReportingService reporter;
    private final RustMeterStatusReader rustMeter;
    private final MeterPairingService pairing;
    private final SystemSessionService session;
    private final RustMeterLogReader logs;

    public UsageStatusController(CodexUsageReportingService reporter,
            RustMeterStatusReader rustMeter, MeterPairingService pairing,
            SystemSessionService session, RustMeterLogReader logs) {
        this.reporter = reporter;
        this.rustMeter = rustMeter;
        this.pairing = pairing;
        this.session = session;
        this.logs = logs;
    }

    @GetMapping("/status")
    public UsageStatusResponse status() {
        return new UsageStatusResponse(reporter.currentStatus(), rustMeter.read(), pairing.state());
    }

    @GetMapping("/events")
    public UserUsageEventPage events(@RequestParam(defaultValue = "0") @Min(0) @Max(100000) int page,
            @RequestParam(defaultValue = "10") @Min(1) @Max(100) int size) {
        return session.usageEvents(page, size);
    }

    @GetMapping("/logs")
    public RustMeterLogReader.LogPage logs() {
        session.requireLocalUserId();
        return logs.read();
    }

    @PostMapping("/scan")
    public UsageStatusResponse scan() {
        try { reporter.scanAndReportNow(); } catch (RuntimeException ignored) { }
        return status();
    }

    @PostMapping("/pair")
    public UsageStatusResponse pair() {
        try { pairing.pairIfInstalled(); } catch (RuntimeException ignored) { }
        return status();
    }

    public record UsageStatusResponse(UsageScannerStatus fallback, MeterServiceStatus service,
            String pairingState) { }
}
