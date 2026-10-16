package com.pingche.web.officialsession;

import com.pingche.web.auth.DomainException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectReader;

@Service
public class OfficialAuthorizationPresetService {
    private static final int MAX_BYTES = 65_536;
    private static final Set<String> FIELDS = Set.of("requestedAccountId", "authorization", "cookie");
    private final String configuredPath;
    private final ObjectReader reader;
    private final OfficialConnectionService connections;

    public OfficialAuthorizationPresetService(@Value("${OFFICIAL_AUTHORIZATION_PRESET_FILE:}") String configuredPath,
            ObjectMapper mapper, OfficialConnectionService connections) {
        this.configuredPath = configuredPath;
        this.reader = mapper.reader().with(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY,
                DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        this.connections = connections;
    }

    public OfficialAuthorizationPresetResponse metadata() {
        try {
            Snapshot snapshot = readSnapshot();
            return new OfficialAuthorizationPresetResponse(true, snapshot.credentials().requestedAccountId(), snapshot.revision(), null);
        } catch (DomainException failure) {
            return new OfficialAuthorizationPresetResponse(false, null, null,
                    "OFFICIAL_PRESET_NOT_CONFIGURED".equals(failure.getCode()) ? null : failure.getCode());
        }
    }

    public OfficialConnectionResponse apply(UUID accountId, OfficialAuthorizationPresetRequest request) {
        Snapshot snapshot = readSnapshot();
        if (!snapshot.revision().equals(request.presetRevision()))
            throw new DomainException("OFFICIAL_PRESET_REVISION_CONFLICT", "Official authorization preset changed; reload before applying", HttpStatus.CONFLICT);
        OfficialSessionCredentials credentials = snapshot.credentials();
        return connections.save(accountId, new OfficialConnectionRequest(credentials.authorization(), credentials.cookie(),
                credentials.requestedAccountId(), request.proxyNodeId(), request.version(), request.frontProxyNodeId()));
    }

    private Snapshot readSnapshot() {
        if (configuredPath.isBlank()) throw new DomainException("OFFICIAL_PRESET_NOT_CONFIGURED",
                "Official authorization preset is not configured", HttpStatus.CONFLICT);
        byte[] contents;
        try {
            Path path = Path.of(configuredPath);
            if (!Files.isRegularFile(path)) throw unavailable();
            // A bounded read also enforces the limit if the deployment file grows during the read.
            try (var input = Files.newInputStream(path)) { contents = input.readNBytes(MAX_BYTES + 1); }
        } catch (IOException | InvalidPathException | SecurityException ignored) {
            throw unavailable();
        }
        if (contents.length > MAX_BYTES) throw invalid();
        try {
            String json = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(contents)).toString();
            JsonNode node = reader.readTree(json);
            if (node == null || !node.isObject() || node.properties().stream().anyMatch(entry -> !FIELDS.contains(entry.getKey())))
                throw invalid();
            OfficialSessionCredentials credentials = new OfficialSessionCredentials(text(node, "authorization"),
                    text(node, "cookie"), text(node, "requestedAccountId"));
            credentials.validate();
            return new Snapshot(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(contents)), credentials);
        } catch (CharacterCodingException | JacksonException | DomainException ignored) {
            // Parsing exceptions can contain file contents. Do not retain, log, or expose them.
            throw invalid();
        } catch (NoSuchAlgorithmException ignored) {
            throw unavailable();
        }
    }

    private String text(JsonNode object, String name) {
        JsonNode value = object.get(name);
        if (value == null || value.isNull()) return null;
        if (!value.isString()) throw invalid();
        return value.asString();
    }
    private DomainException invalid() { return new DomainException("OFFICIAL_PRESET_INVALID", "Official authorization preset is invalid", HttpStatus.SERVICE_UNAVAILABLE); }
    private DomainException unavailable() { return new DomainException("OFFICIAL_PRESET_UNAVAILABLE", "Official authorization preset is unavailable", HttpStatus.SERVICE_UNAVAILABLE); }
    private record Snapshot(String revision, OfficialSessionCredentials credentials) {
        @Override public String toString() { return "OfficialAuthorizationPresetSnapshot[redacted]"; }
    }
}
