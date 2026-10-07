package com.codexquotaguard.backend.usage;

import com.codexquotaguard.backend.system.MeterPairingService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/usage")
public final class UsageStatusController {
    private final CodexUsageReportingService reporter;
    private final RustMeterStatusReader rustMeter;
    private final MeterPairingService pairing;

    public UsageStatusController(CodexUsageReportingService reporter,
            RustMeterStatusReader rustMeter, MeterPairingService pairing) {
        this.reporter = reporter;
        this.rustMeter = rustMeter;
        this.pairing = pairing;
    }

    @GetMapping("/status")
    public UsageStatusResponse status() {
        return new UsageStatusResponse(reporter.currentStatus(), rustMeter.read(), pairing.state());
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
