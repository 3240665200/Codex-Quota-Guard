package com.codexquotaguard.backend.usage;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

class RustMeterStatusReaderTest {
    @TempDir Path directory;

    @Test
    void readsVersionReportedByCurrentService() throws Exception {
        RustMeterStatusReader reader = pairedReader();
        Files.writeString(statusFile(), statusWithVersion("\"0.1.2\"", Instant.now().getEpochSecond()));

        MeterServiceStatus status = reader.read();

        assertThat(status.state()).isEqualTo("UP_TO_DATE");
        assertThat(status.version()).isEqualTo("0.1.2");
    }

    @Test
    void oldStatusWithoutVersionRemainsCompatible() throws Exception {
        RustMeterStatusReader reader = pairedReader();
        Files.writeString(statusFile(), "{\"state\":\"UP_TO_DATE\",\"lastScanEpochSeconds\":"
                + Instant.now().getEpochSecond() + "}");

        assertThat(reader.read().version()).isNull();
    }

    @Test
    void rejectsUntrustedVersionText() throws Exception {
        RustMeterStatusReader reader = pairedReader();
        Files.writeString(statusFile(), statusWithVersion("\"0.1.2/../../private\"", Instant.now().getEpochSecond()));

        MeterServiceStatus status = reader.read();

        assertThat(status.state()).isEqualTo("UP_TO_DATE");
        assertThat(status.version()).isNull();
    }

    @Test
    void staleStatusDoesNotPresentVersionAsRunning() throws Exception {
        RustMeterStatusReader reader = pairedReader();
        Files.writeString(statusFile(), statusWithVersion("\"0.1.2\"", Instant.now().minusSeconds(31).getEpochSecond()));

        assertThat(reader.read()).isEqualTo(MeterServiceStatus.offline());
    }

    private RustMeterStatusReader pairedReader() throws Exception {
        Path deviceId = directory.resolve("device-id.txt");
        Files.writeString(deviceId, "test-device-id");
        return new RustMeterStatusReader(new ObjectMapper(), deviceId.toString(), statusFile().toString());
    }

    private Path statusFile() {
        return directory.resolve("meter-status.json");
    }

    private static String statusWithVersion(String version, long lastScan) {
        return "{\"state\":\"UP_TO_DATE\",\"lastScanEpochSeconds\":" + lastScan
                + ",\"version\":" + version + "}";
    }
}
