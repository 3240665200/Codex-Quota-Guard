package com.pingche.web.officialsession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.pingche.web.auth.DomainException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(OutputCaptureExtension.class)
class OfficialAuthorizationPresetServiceTest {
    private static final String VALID = "{\"requestedAccountId\":\"preset-account\",\"authorization\":\"Bearer synthetic-preset-token\",\"cookie\":\"__Secure-next-auth.session-token=synthetic-cookie\"}";
    @TempDir Path directory;
    private final OfficialConnectionService connections = mock(OfficialConnectionService.class);

    @Test
    void absentConfigurationIsUnavailableAndCannotBeApplied() {
        var service = service("");
        assertThat(service.metadata()).isEqualTo(new OfficialAuthorizationPresetResponse(false, null, null, null));
        assertThatThrownBy(() -> service.apply(UUID.randomUUID(), request("a".repeat(64))))
                .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.getCode()).isEqualTo("OFFICIAL_PRESET_NOT_CONFIGURED"));
        verifyNoInteractions(connections);
    }

    @Test
    void validSnapshotHashesOriginalBytesAndForwardsVersionAndProxyWithoutSecretsInMetadata() throws Exception {
        Path file = Files.writeString(directory.resolve("preset.json"), VALID);
        var service = service(file.toString());
        var metadata = service.metadata();
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(VALID.getBytes(StandardCharsets.UTF_8)));
        assertThat(metadata).isEqualTo(new OfficialAuthorizationPresetResponse(true, "preset-account", hash, null));
        assertThat(new JsonMapper().writeValueAsString(metadata)).doesNotContain("synthetic-preset-token", "synthetic-cookie", file.toString());
        UUID accountId = UUID.randomUUID(), proxyId = UUID.randomUUID(), frontId = UUID.randomUUID();
        service.apply(accountId, new OfficialAuthorizationPresetRequest(7L, proxyId, hash, frontId));
        verify(connections).save(accountId, new OfficialConnectionRequest("Bearer synthetic-preset-token",
                "__Secure-next-auth.session-token=synthetic-cookie", "preset-account", proxyId, 7L, frontId));
    }

    @Test
    void replacementAfterMetadataFailsRevisionCheckWithoutSaving() throws Exception {
        Path file = Files.writeString(directory.resolve("preset.json"), VALID);
        var service = service(file.toString());
        String revision = service.metadata().revision();
        Files.writeString(file, VALID.replace("synthetic-preset-token", "new-synthetic-token"));
        assertThat(service.metadata().revision()).isNotEqualTo(revision);
        assertThatThrownBy(() -> service.apply(UUID.randomUUID(), request(revision)))
                .isInstanceOfSatisfying(DomainException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("OFFICIAL_PRESET_REVISION_CONFLICT");
                    assertThat(e.getStatus().value()).isEqualTo(409);
                });
        verifyNoInteractions(connections);
    }

    @Test
    void malformedOversizedDuplicateAndInvalidCredentialFilesAreSafe(CapturedOutput output) throws Exception {
        Path file = directory.resolve("private-preset.json");
        var service = service(file.toString());
        for (String body : List.of("", "synthetic-secret-body", "[]", "null", "{}", VALID + " {}",
                VALID.replace("\"preset-account\"", "5"), VALID.replace("Bearer synthetic-preset-token", "secret-invalid"),
                VALID.replace("synthetic-cookie", "bad\\r\\nHeader:value"),
                VALID.replace("{", "{\"authorization\":\"Bearer duplicate-secret\","),
                VALID.replace("}", ",\"path\":\"private-path\"}"), " ".repeat(65_537))) {
            Files.writeString(file, body);
            assertThat(service.metadata()).as("invalid preset").isEqualTo(
                    new OfficialAuthorizationPresetResponse(false, null, null, "OFFICIAL_PRESET_INVALID"));
            assertThatThrownBy(() -> service.apply(UUID.randomUUID(), request("a".repeat(64))))
                    .isInstanceOfSatisfying(DomainException.class, e -> {
                        assertThat(e.getCode()).isEqualTo("OFFICIAL_PRESET_INVALID");
                        assertThat(e.getStatus().value()).isEqualTo(503);
                        assertThat(e.getMessage()).doesNotContain("secret", "private-preset", directory.toString());
                        assertThat(e.getCause()).isNull();
                    });
        }
        Files.write(file, new byte[] {(byte) 0xc3, (byte) 0x28});
        assertThat(service.metadata().errorCode()).isEqualTo("OFFICIAL_PRESET_INVALID");
        assertThat(output.getAll()).doesNotContain("synthetic-secret-body", "duplicate-secret", "private-preset");
        verifyNoInteractions(connections);
    }

    @Test
    void unreadableMissingDirectoryAndInvalidPathNeverLeakPaths(CapturedOutput output) {
        for (String path : List.of(directory.resolve("missing-private-file.json").toString(), directory.toString(), "invalid\u0000path")) {
            var service = service(path);
            assertThat(service.metadata()).isEqualTo(new OfficialAuthorizationPresetResponse(false, null, null, "OFFICIAL_PRESET_UNAVAILABLE"));
            assertThatThrownBy(() -> service.apply(UUID.randomUUID(), request("a".repeat(64))))
                    .isInstanceOfSatisfying(DomainException.class, e -> {
                        assertThat(e.getCode()).isEqualTo("OFFICIAL_PRESET_UNAVAILABLE");
                        assertThat(e.getMessage()).doesNotContain("missing-private-file", directory.toString());
                    });
        }
        assertThat(output.getAll()).doesNotContain("missing-private-file", directory.toString());
        verifyNoInteractions(connections);
    }

    @Test
    void fileAtLimitAndOptionalCookieAreAccepted() throws Exception {
        String withoutCookie = "{\"requestedAccountId\":\"preset-account\",\"authorization\":\"Bearer synthetic\"}";
        Path file = Files.writeString(directory.resolve("preset.json"), withoutCookie + " ".repeat(65_536 - withoutCookie.length()));
        var service = service(file.toString());
        assertThat(service.metadata().available()).isTrue();
        service.apply(UUID.randomUUID(), request(service.metadata().revision()));
    }

    private OfficialAuthorizationPresetService service(String path) {
        return new OfficialAuthorizationPresetService(path, new JsonMapper(), connections);
    }
    private OfficialAuthorizationPresetRequest request(String revision) {
        return new OfficialAuthorizationPresetRequest(0L, null, revision);
    }
}
