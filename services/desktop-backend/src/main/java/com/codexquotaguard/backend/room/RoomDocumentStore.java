package com.codexquotaguard.backend.room;

import com.codexquotaguard.backend.quota.QuotaSnapshot;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

public final class RoomDocumentStore {

    private static final int SCHEMA_VERSION = 1;
    private static final int MAX_DOCUMENT_BYTES = 1 << 20;
    private static final Pattern SAFE_ID = Pattern.compile("^[A-Za-z0-9_-]{1,96}$");
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final Path root;

    public RoomDocumentStore(Path root) {
        if (root == null) {
            throw new IllegalArgumentException("Room directory is required.");
        }
        this.root = root.toAbsolutePath().normalize();
    }

    public Path root() {
        return root;
    }

    public void ensureLayout() {
        try {
            Files.createDirectories(root);
            Files.createDirectories(root.resolve("members"));
            Files.createDirectories(root.resolve("snapshots"));
        } catch (IOException exception) {
            throw new RoomStorageException("Could not create the shared room directory.", exception);
        }
    }

    public void writeManifest(RoomManifest manifest) {
        requireManifest(manifest);
        writeJson(root.resolve("room.json"), manifest);
    }

    public RoomManifest readManifest() {
        var manifest = readJson(root.resolve("room.json"), RoomManifest.class);
        requireManifest(manifest);
        return manifest;
    }

    public void writeMember(SignedDocument<MemberProfile> document) {
        requireMember(document.payload());
        PublicKey publicKey = DocumentSigner.decodePublicKey(document.payload().publicKey());
        if (!DocumentSigner.verify(document.payload(), document.signature(), publicKey)) {
            throw new DocumentSignatureException("Member document signature is invalid.");
        }
        writeJson(root.resolve("members").resolve(document.payload().memberId() + ".json"), document);
    }

    public MembersReadResult readMembers(String roomId) {
        requireId("room", roomId);
        List<String> warnings = new ArrayList<>();
        Map<String, VerifiedMember> members = new LinkedHashMap<>();
        for (Path path : jsonFiles(root.resolve("members"))) {
            try {
                SignedDocument<MemberProfile> document = readSignedDocument(path, MemberProfile.class);
                MemberProfile profile = document.payload();
                requireMember(profile);
                if (!roomId.equals(profile.roomId()) || !path.getFileName().toString().equals(profile.memberId() + ".json")) {
                    throw new RoomStorageException("Member document identity does not match its path.");
                }
                PublicKey publicKey = DocumentSigner.decodePublicKey(profile.publicKey());
                if (!DocumentSigner.verify(profile, document.signature(), publicKey)) {
                    throw new DocumentSignatureException("Member document signature is invalid.");
                }
                members.put(profile.memberId(), new VerifiedMember(profile, publicKey));
            } catch (RuntimeException exception) {
                warnings.add(warning(path, exception));
            }
        }
        return new MembersReadResult(members, warnings);
    }

    public void writeSnapshot(SignedDocument<QuotaSnapshot> document) {
        requireSnapshot(document.payload());
        writeJson(root.resolve("snapshots").resolve(document.payload().memberId() + ".json"), document);
    }

    public SnapshotsReadResult readSnapshots(String roomId, Map<String, VerifiedMember> members) {
        requireId("room", roomId);
        Map<String, VerifiedMember> knownMembers = members == null ? Map.of() : members;
        List<String> warnings = new ArrayList<>();
        List<QuotaSnapshot> snapshots = new ArrayList<>();
        for (Path path : jsonFiles(root.resolve("snapshots"))) {
            try {
                SignedDocument<QuotaSnapshot> document = readSignedDocument(path, QuotaSnapshot.class);
                QuotaSnapshot snapshot = document.payload();
                requireSnapshot(snapshot);
                VerifiedMember member = knownMembers.get(snapshot.memberId());
                if (member == null || !roomId.equals(snapshot.roomId()) || !path.getFileName().toString().equals(snapshot.memberId() + ".json")) {
                    throw new RoomStorageException("Snapshot identity does not match the room or a known member.");
                }
                if (!DocumentSigner.verify(snapshot, document.signature(), member.publicKey())) {
                    throw new DocumentSignatureException("Snapshot signature is invalid.");
                }
                snapshots.add(snapshot);
            } catch (RuntimeException exception) {
                warnings.add(warning(path, exception));
            }
        }
        snapshots.sort(Comparator.comparing(QuotaSnapshot::memberId));
        return new SnapshotsReadResult(snapshots, warnings);
    }

    public void writePolicy(SignedDocument<RoomPolicy> document) {
        requirePolicy(document.payload());
        writeJson(root.resolve("policy.json"), document);
    }

    public SignedDocument<RoomPolicy> readPolicy(String roomId, PublicKey ownerPublicKey) {
        SignedDocument<RoomPolicy> document = readSignedDocument(root.resolve("policy.json"), RoomPolicy.class);
        requirePolicy(document.payload());
        if (!roomId.equals(document.payload().roomId())) {
            throw new RoomStorageException("Policy room does not match the pinned room.");
        }
        if (!DocumentSigner.verify(document.payload(), document.signature(), ownerPublicKey)) {
            throw new DocumentSignatureException("Policy signature is invalid.");
        }
        return document;
    }

    private static List<Path> jsonFiles(Path directory) {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        List<Path> paths = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, "*.json")) {
            for (Path path : stream) {
                paths.add(path);
            }
        } catch (IOException exception) {
            throw new RoomStorageException("Could not read room documents.", exception);
        }
        paths.sort(Comparator.comparing(path -> path.getFileName().toString()));
        return paths;
    }

    private static <T> SignedDocument<T> readSignedDocument(Path path, Class<T> payloadType) {
        JsonNode root = readTree(path);
        JsonNode payloadNode = root.path("payload");
        String signature = root.path("signature").isTextual() ? root.path("signature").asString() : "";
        if (payloadNode.isMissingNode() || signature.isBlank()) {
            throw new RoomStorageException("Signed document is incomplete.");
        }
        try {
            T payload = MAPPER.readValue(MAPPER.writeValueAsString(payloadNode), payloadType);
            return new SignedDocument<>(payload, signature);
        } catch (RuntimeException exception) {
            throw new RoomStorageException("Could not decode signed room document.", exception);
        }
    }

    private static <T> T readJson(Path path, Class<T> type) {
        try {
            return MAPPER.readValue(readBytes(path), type);
        } catch (IOException | RuntimeException exception) {
            throw new RoomStorageException("Could not read " + path.getFileName() + ".", exception);
        }
    }

    private static JsonNode readTree(Path path) {
        try {
            return MAPPER.readTree(readBytes(path));
        } catch (IOException | RuntimeException exception) {
            throw new RoomStorageException("Could not decode " + path.getFileName() + ".", exception);
        }
    }

    private static byte[] readBytes(Path path) throws IOException {
        if (!Files.isRegularFile(path)) {
            throw new IOException("file does not exist");
        }
        long size = Files.size(path);
        if (size > MAX_DOCUMENT_BYTES) {
            throw new IOException("document exceeds the size limit");
        }
        return Files.readAllBytes(path);
    }

    private static void writeJson(Path path, Object value) {
        try {
            Files.createDirectories(path.getParent());
            Path temporary = Files.createTempFile(path.getParent(), ".quota-guard-", ".tmp");
            try {
                Files.writeString(temporary, MAPPER.writeValueAsString(value) + System.lineSeparator(), StandardCharsets.UTF_8);
                try {
                    Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException exception) {
                    Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (IOException | RuntimeException exception) {
            throw new RoomStorageException("Could not write " + path.getFileName() + ".", exception);
        }
    }

    private static void requireManifest(RoomManifest manifest) {
        if (manifest == null || manifest.schemaVersion() != SCHEMA_VERSION) {
            throw new RoomStorageException("Room manifest schema is unsupported.");
        }
        requireId("room", manifest.roomId());
        requireName("room name", manifest.name());
        requireId("owner member", manifest.ownerMemberId());
        DocumentSigner.decodePublicKey(manifest.ownerPublicKey());
        if (manifest.createdAt() == null) {
            throw new RoomStorageException("Room creation time is required.");
        }
    }

    private static void requireMember(MemberProfile profile) {
        if (profile == null || profile.schemaVersion() != SCHEMA_VERSION) {
            throw new RoomStorageException("Member profile schema is unsupported.");
        }
        requireId("room", profile.roomId());
        requireId("member", profile.memberId());
        requireName("display name", profile.displayName());
        DocumentSigner.decodePublicKey(profile.publicKey());
        if (profile.joinedAt() == null) {
            throw new RoomStorageException("Member join time is required.");
        }
    }

    private static void requireSnapshot(QuotaSnapshot snapshot) {
        if (snapshot == null || snapshot.schemaVersion() != SCHEMA_VERSION) {
            throw new RoomStorageException("Snapshot schema is unsupported.");
        }
        requireId("room", snapshot.roomId());
        requireId("member", snapshot.memberId());
        requireName("display name", snapshot.displayName());
        if (snapshot.capturedAt() == null || snapshot.publishedAt() == null || snapshot.buckets() == null) {
            throw new RoomStorageException("Snapshot timing and buckets are required.");
        }
    }

    private static void requirePolicy(RoomPolicy policy) {
        if (policy == null || policy.schemaVersion() != SCHEMA_VERSION) {
            throw new RoomStorageException("Policy schema is unsupported.");
        }
        requireId("room", policy.roomId());
        if (policy.revision() < 1 || policy.updatedAt() == null || policy.members() == null) {
            throw new RoomStorageException("Policy revision, update time, and members are required.");
        }
        policy.members().forEach((memberId, memberPolicy) -> {
            requireId("member", memberId);
            if (memberPolicy == null || !memberId.equals(memberPolicy.memberId())) {
                throw new RoomStorageException("Policy member identity does not match its map key.");
            }
            if (memberPolicy.rules() == null) {
                throw new RoomStorageException("Policy rules are required.");
            }
            memberPolicy.rules().forEach(RoomDocumentStore::requireRule);
        });
    }

    private static void requireRule(QuotaRule rule) {
        if (rule == null || rule.limitId() == null || rule.limitId().isBlank() || rule.window() == null || rule.window().isBlank()
                || !Double.isFinite(rule.maxUsedPercent()) || rule.maxUsedPercent() < 0 || rule.maxUsedPercent() > 100
                || !"logout".equals(rule.action())) {
            throw new RoomStorageException("Policy rule is invalid.");
        }
    }

    private static void requireId(String label, String value) {
        if (value == null || !SAFE_ID.matcher(value).matches()) {
            throw new RoomStorageException(label + " identifier is invalid.");
        }
    }

    private static void requireName(String label, String value) {
        if (value == null || value.trim().isEmpty() || value.trim().codePointCount(0, value.trim().length()) > 80) {
            throw new RoomStorageException(label + " must contain 1 to 80 characters.");
        }
    }

    private static String warning(Path path, RuntimeException exception) {
        return path.getFileName() + ": " + Objects.requireNonNullElse(exception.getMessage(), "document rejected");
    }
}
