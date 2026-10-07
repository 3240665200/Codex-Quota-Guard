package com.codexquotaguard.backend.usage;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Reads a small public status file; never reads the service's meter token. */
@Component
public final class RustMeterStatusReader {
    private final Path deviceIdFile;
    private final Path statusFile;
    private final ObjectMapper mapper;

    @Autowired
    public RustMeterStatusReader(ObjectMapper mapper,
            @Value("${quota-guard.meter.device-id-file:${user.home}/AppData/Roaming/com.codexquotaguard.desktop/meter-device-id.txt}") String deviceIdFile,
            @Value("${quota-guard.meter.status-file:${user.home}/AppData/Roaming/com.codexquotaguard.desktop/meter-status.json}") String statusFile) {
        this.mapper = mapper;
        this.deviceIdFile = Path.of(deviceIdFile).toAbsolutePath().normalize();
        this.statusFile = Path.of(statusFile).toAbsolutePath().normalize();
    }

    public MeterServiceStatus read() {
        if (!Files.isRegularFile(deviceIdFile, LinkOption.NOFOLLOW_LINKS)) {
            return MeterServiceStatus.notInstalled();
        }
        try {
            if (!Files.isRegularFile(statusFile, LinkOption.NOFOLLOW_LINKS)
                    || Files.size(statusFile) > 4096) return MeterServiceStatus.offline();
            JsonNode node = mapper.readTree(Files.readAllBytes(statusFile));
            if (!node.isObject()) return MeterServiceStatus.offline();
            long lastScan = node.path("lastScanEpochSeconds").asLong(0);
            if (lastScan <= 0 || Instant.now().getEpochSecond() - lastScan > 30) {
                return MeterServiceStatus.offline();
            }
            String state = node.path("state").asString("ERROR");
            if (!java.util.Set.of("BASELINE_READY", "UNPAIRED", "UP_TO_DATE", "REPORTED", "ERROR")
                    .contains(state)) state = "ERROR";
            String error = node.path("errorCode").asString("");
            if (!error.matches("[A-Z_]{1,64}")) error = null;
            long lastReport = node.path("lastReportEpochSeconds").asLong(0);
            int reported = node.path("reportedEvents").asInt(0);
            return new MeterServiceStatus(state, lastScan, lastReport <= 0 ? null : lastReport,
                    Math.max(0, reported), error);
        } catch (Exception ignored) {
            return MeterServiceStatus.offline();
        }
    }
}
