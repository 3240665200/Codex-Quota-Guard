package com.codexquotaguard.backend.system;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EncryptedSessionStorageTest {

    private static final String ORIGIN = "https://control.example.invalid";
    private static final String OTHER_ORIGIN = "https://other.example.invalid";
    private static final String REFRESH_ONE = "synthetic-refresh-token-one-11111111111111111111";
    private static final String REFRESH_TWO = "synthetic-refresh-token-two-22222222222222222222";
    private static final UUID USER_ID = UUID.fromString("11111111-2222-4333-8444-555555555555");
    private static final int MAX_CIPHERTEXT_BYTES = 8192;

    @TempDir Path temporaryDirectory;

    private final Instant loginAt = Instant.now().minusSeconds(30).truncatedTo(ChronoUnit.SECONDS);

    @Test
    void roundTripsRefreshTokenIdentityAndOriginalTimesWithoutExposingTheToken() {
        SavedSession original = saved(REFRESH_ONE);
        try (SessionStorage storage = storage(temporaryDirectory, key(), ORIGIN)) {
            storage.ensureAvailable();
            assertThat(storage.load()).isEmpty();

            storage.save(original);

            assertSession(storage.load().orElseThrow(), original);
            assertThat(original.toString()).doesNotContain(REFRESH_ONE);
        }
    }

    @Test
    void resavesTheSameValueUsingFreshCiphertextAndLeavesNoPlaintext() throws Exception {
        SavedSession original = saved(REFRESH_ONE);
        try (SessionStorage storage = storage(temporaryDirectory, key(), ORIGIN)) {
            storage.save(original);
            Path sessionFile = onlySessionFile(temporaryDirectory);
            byte[] previousCiphertext = Files.readAllBytes(sessionFile);

            for (int save = 0; save < 3; save++) {
                storage.save(original);
                byte[] ciphertext = Files.readAllBytes(sessionFile);
                assertThat(Arrays.equals(ciphertext, previousCiphertext)).isFalse();
                assertThat(ciphertext.length).isBetween(1, MAX_CIPHERTEXT_BYTES);
                assertThat(new String(ciphertext, StandardCharsets.ISO_8859_1))
                        .doesNotContain(REFRESH_ONE, USER_ID.toString(), loginAt.toString());
                assertSession(storage.load().orElseThrow(), original);
                previousCiphertext = ciphertext;
            }
        }
    }

    @Test
    void replacesPreviousCiphertextAndRetainsTheLatestSessionAfterClose() throws Exception {
        SavedSession replacement = saved(REFRESH_TWO);
        Path sessionFile;
        try (SessionStorage storage = storage(temporaryDirectory, key(), ORIGIN)) {
            storage.save(saved(REFRESH_ONE));
            sessionFile = onlySessionFile(temporaryDirectory);

            storage.save(replacement);

            assertThat(sessionFiles(temporaryDirectory)).containsExactly(sessionFile);
            assertThat(directoryFiles(temporaryDirectory)).allSatisfy(path ->
                    assertThat(path.getFileName().toString()).doesNotEndWith(".tmp"));
            assertSession(storage.load().orElseThrow(), replacement);
        }

        assertThat(sessionFile).exists();
        try (SessionStorage reopened = storage(temporaryDirectory, key(), ORIGIN)) {
            assertSession(reopened.load().orElseThrow(), replacement);
        }
    }

    @Test
    void keepsOriginsIndependentAndReusesTheSameFileForCanonicalEquivalentOrigins() throws Exception {
        SavedSession first = saved(REFRESH_ONE);
        SavedSession other = saved(REFRESH_TWO);
        try (SessionStorage firstStorage = storage(temporaryDirectory, key(), ORIGIN);
                SessionStorage otherStorage = storage(temporaryDirectory, key(), OTHER_ORIGIN)) {
            firstStorage.save(first);
            otherStorage.save(other);

            assertSession(firstStorage.load().orElseThrow(), first);
            assertSession(otherStorage.load().orElseThrow(), other);
            assertThat(sessionFiles(temporaryDirectory)).hasSize(2);
        }

        try (SessionStorage equivalent = storage(temporaryDirectory, key(),
                "HTTPS://CONTROL.EXAMPLE.INVALID:443/")) {
            assertSession(equivalent.load().orElseThrow(), first);
            assertThat(sessionFiles(temporaryDirectory)).hasSize(2);
        }
    }

    @Test
    void rejectsAndRemovesCiphertextCopiedFromAnotherOrigin() throws Exception {
        Path sourceDirectory = temporaryDirectory.resolve("source");
        Path targetDirectory = temporaryDirectory.resolve("target");
        try (SessionStorage source = storage(sourceDirectory, key(), ORIGIN);
                SessionStorage target = storage(targetDirectory, key(), OTHER_ORIGIN)) {
            source.save(saved(REFRESH_ONE));
            target.save(saved(REFRESH_TWO));
            Path sourceFile = onlySessionFile(sourceDirectory);
            Path targetFile = onlySessionFile(targetDirectory);
            Files.copy(sourceFile, targetFile, StandardCopyOption.REPLACE_EXISTING);

            assertThat(target.load()).isEmpty();
            assertThat(targetFile).doesNotExist();
            assertSession(source.load().orElseThrow(), saved(REFRESH_ONE));
        }
    }

    @Test
    void removesCiphertextAfterAuthenticationTagTampering() throws Exception {
        try (SessionStorage storage = storage(temporaryDirectory, key(), ORIGIN)) {
            storage.save(saved(REFRESH_ONE));
            Path sessionFile = onlySessionFile(temporaryDirectory);
            byte[] ciphertext = Files.readAllBytes(sessionFile);
            ciphertext[ciphertext.length - 1] ^= 1;
            Files.write(sessionFile, ciphertext);

            assertThat(storage.load()).isEmpty();
            assertThat(sessionFile).doesNotExist();
        }
    }

    @Test
    void removesTruncatedCiphertext() throws Exception {
        try (SessionStorage storage = storage(temporaryDirectory, key(), ORIGIN)) {
            storage.save(saved(REFRESH_ONE));
            Path sessionFile = onlySessionFile(temporaryDirectory);
            byte[] ciphertext = Files.readAllBytes(sessionFile);
            Files.write(sessionFile, Arrays.copyOf(ciphertext, ciphertext.length - 1));

            assertThat(storage.load()).isEmpty();
            assertThat(sessionFile).doesNotExist();
        }
    }

    @Test
    void removesCiphertextLargerThan8192Bytes() throws Exception {
        try (SessionStorage storage = storage(temporaryDirectory, key(), ORIGIN)) {
            storage.save(saved(REFRESH_ONE));
            Path sessionFile = onlySessionFile(temporaryDirectory);
            Files.write(sessionFile, new byte[MAX_CIPHERTEXT_BYTES + 1]);

            assertThat(storage.load()).isEmpty();
            assertThat(sessionFile).doesNotExist();
        }
    }

    @Test
    void removesCiphertextEncryptedWithAnotherKey() throws Exception {
        Path sessionFile;
        try (SessionStorage original = storage(temporaryDirectory, key(), ORIGIN)) {
            original.save(saved(REFRESH_ONE));
            sessionFile = onlySessionFile(temporaryDirectory);
        }
        byte[] incorrectKey = key();
        incorrectKey[0] ^= 1;

        try (SessionStorage reopened = storage(temporaryDirectory, incorrectKey, ORIGIN)) {
            assertThat(reopened.load()).isEmpty();
            assertThat(sessionFile).doesNotExist();
        }
    }

    @Test
    void holdsTheExclusiveOriginLockThroughReplacementAndClearUntilClose() throws Exception {
        try (SessionStorage owner = storage(temporaryDirectory, key(), ORIGIN)) {
            owner.ensureAvailable();
            assertThat(lockFiles(temporaryDirectory)).hasSize(1);
            assertAnotherInstanceIsLockedOut();

            owner.save(saved(REFRESH_ONE));
            owner.save(saved(REFRESH_TWO));
            assertAnotherInstanceIsLockedOut();

            owner.clear();
            assertThat(owner.load()).isEmpty();
            assertThat(sessionFiles(temporaryDirectory)).isEmpty();
            assertAnotherInstanceIsLockedOut();
        }

        try (SessionStorage nextOwner = storage(temporaryDirectory, key(), ORIGIN)) {
            nextOwner.ensureAvailable();
            assertThat(nextOwner.load()).isEmpty();
            nextOwner.save(saved(REFRESH_TWO));
            assertSession(nextOwner.load().orElseThrow(), saved(REFRESH_TWO));
        }
    }

    @Test
    void rejectsRelativeDirectoriesRegularFileDirectoriesAndNonRegularSessionLeaves() throws Exception {
        assertUnavailable(() -> {
            try (SessionStorage relative = storage(Path.of("relative-session-storage"), key(), ORIGIN)) {
                relative.ensureAvailable();
            }
        }, "relative-session-storage");

        Path ordinaryFile = temporaryDirectory.resolve("ordinary-file");
        Files.writeString(ordinaryFile, "synthetic-existing-file");
        assertUnavailable(() -> {
            try (SessionStorage invalid = storage(ordinaryFile, key(), ORIGIN)) {
                invalid.ensureAvailable();
            }
        }, ordinaryFile.toString());
        assertThat(Files.readString(ordinaryFile)).isEqualTo("synthetic-existing-file");

        Path directory = temporaryDirectory.resolve("non-regular-session");
        try (SessionStorage storage = storage(directory, key(), ORIGIN)) {
            storage.save(saved(REFRESH_ONE));
            Path sessionFile = onlySessionFile(directory);
            Files.delete(sessionFile);
            Files.createDirectory(sessionFile);

            assertUnavailable(storage::load, sessionFile.toString());
            assertThat(sessionFile).isDirectory();
        }
    }

    @Test
    void rejectsSymbolicLinkDirectoriesAndSessionLeavesWithoutTouchingTheirTargets() throws Exception {
        Path realDirectory = temporaryDirectory.resolve("real-directory");
        Files.createDirectories(realDirectory);
        Path linkedDirectory = temporaryDirectory.resolve("linked-directory");
        createSymbolicLinkOrSkip(linkedDirectory, realDirectory);

        assertUnavailable(() -> {
            try (SessionStorage invalid = storage(linkedDirectory.resolve("nested"), key(), ORIGIN)) {
                invalid.ensureAvailable();
            }
        }, linkedDirectory.toString());
        assertThat(directoryFiles(realDirectory)).isEmpty();

        Path outsideFile = temporaryDirectory.resolve("synthetic-outside-target");
        Files.writeString(outsideFile, "synthetic-target-must-stay-intact");
        Path directory = temporaryDirectory.resolve("leaf-link");
        try (SessionStorage storage = storage(directory, key(), ORIGIN)) {
            storage.save(saved(REFRESH_ONE));
            Path sessionFile = onlySessionFile(directory);
            Files.delete(sessionFile);
            createSymbolicLinkOrSkip(sessionFile, outsideFile);

            assertUnavailable(storage::load, sessionFile.toString());
            assertUnavailable(storage::clear, sessionFile.toString());
            assertThat(Files.isSymbolicLink(sessionFile)).isTrue();
            assertThat(Files.readString(outsideFile)).isEqualTo("synthetic-target-must-stay-intact");
        }
    }

    @Test
    void requiresCompleteValidConfigurationAndUsesFixedSanitizedFailures() throws Exception {
        String directory = temporaryDirectory.resolve("configured-storage").toString();
        String keyHex = HexFormat.of().formatHex(key());
        List<Configuration> invalid = List.of(
                new Configuration("", "", true, ORIGIN),
                new Configuration(directory, "", true, ORIGIN),
                new Configuration("", keyHex, true, ORIGIN),
                new Configuration(directory, "", false, ORIGIN),
                new Configuration("", keyHex, false, ORIGIN),
                new Configuration(directory, "not-a-hex-key", false, ORIGIN),
                new Configuration(directory, "11".repeat(31), true, ORIGIN),
                new Configuration(directory, "11".repeat(33), true, ORIGIN),
                new Configuration("relative-session-storage", keyHex, true, ORIGIN),
                new Configuration(directory, keyHex, true, "https://user:secret@example.invalid"),
                new Configuration(directory, keyHex, true, "https://example.invalid/api"));
        String fixedMessage = null;
        for (Configuration configuration : invalid) {
            try (SessionStorage storage = SessionStorageConfiguration.create(configuration.directory(),
                    configuration.keyHex(), configuration.required(), configuration.origin())) {
                SessionStorageException failure = assertUnavailable(storage::ensureAvailable,
                        directory, keyHex, "not-a-hex-key", "relative-session-storage", "user:secret");
                if (fixedMessage == null) fixedMessage = failure.getMessage();
                assertThat(failure.getMessage()).isEqualTo(fixedMessage);
            }
        }
        assertThat(temporaryDirectory.resolve("configured-storage")).doesNotExist();

        try (SessionStorage configured = SessionStorageConfiguration.create(directory, keyHex, true, ORIGIN)) {
            configured.ensureAvailable();
            configured.save(saved(REFRESH_ONE));
            assertSession(configured.load().orElseThrow(), saved(REFRESH_ONE));
        }
    }

    @Test
    void unconfiguredOptionalStorageIsNoopAndNeverRetainsTokens() throws Exception {
        try (SessionStorage storage = SessionStorageConfiguration.create("", "", false, ORIGIN)) {
            storage.ensureAvailable();
            assertThat(storage.load()).isEmpty();
            storage.save(saved(REFRESH_ONE));
            assertThat(storage.load()).isEmpty();
            storage.clear();
        }
        assertThat(directoryFiles(temporaryDirectory)).isEmpty();
    }

    private SessionStorage storage(Path directory, byte[] encryptionKey, String origin) {
        return new EncryptedSessionStorage(directory, encryptionKey, origin);
    }

    private SavedSession saved(String refreshToken) {
        return new SavedSession(refreshToken, USER_ID, loginAt, loginAt.plus(Duration.ofDays(7)));
    }

    private static byte[] key() {
        byte[] syntheticKey = new byte[32];
        Arrays.fill(syntheticKey, (byte) 0x5a);
        return syntheticKey;
    }

    private static void assertSession(SavedSession actual, SavedSession expected) {
        assertThat(actual.refreshToken()).isEqualTo(expected.refreshToken());
        assertThat(actual.userId()).isEqualTo(expected.userId());
        assertThat(actual.loginAt()).isEqualTo(expected.loginAt());
        assertThat(actual.expiresAt()).isEqualTo(expected.expiresAt());
        assertThat(actual.toString()).doesNotContain(expected.refreshToken());
    }

    private void assertAnotherInstanceIsLockedOut() {
        try (SessionStorage competitor = storage(temporaryDirectory, key(), ORIGIN)) {
            assertUnavailable(competitor::ensureAvailable, temporaryDirectory.toString());
        }
    }

    private static SessionStorageException assertUnavailable(CheckedOperation operation, String... rawValues) {
        SessionStorageException failure = assertThrows(SessionStorageException.class, operation::run);
        assertThat(failure.getCause()).isNull();
        assertThat(failure.getMessage()).isNotBlank().doesNotContain(REFRESH_ONE, REFRESH_TWO);
        for (String rawValue : rawValues) {
            assertThat(failure.getMessage()).doesNotContain(rawValue);
        }
        return failure;
    }

    private static List<Path> directoryFiles(Path directory) throws IOException {
        try (Stream<Path> files = Files.list(directory)) {
            return files.toList();
        }
    }

    private static List<Path> sessionFiles(Path directory) throws IOException {
        return directoryFiles(directory).stream()
                .filter(path -> path.getFileName().toString().endsWith(".session"))
                .toList();
    }

    private static List<Path> lockFiles(Path directory) throws IOException {
        return directoryFiles(directory).stream()
                .filter(path -> path.getFileName().toString().endsWith(".lock"))
                .toList();
    }

    private static Path onlySessionFile(Path directory) throws IOException {
        List<Path> files = sessionFiles(directory);
        assertThat(files).hasSize(1);
        return files.get(0);
    }

    private static void createSymbolicLinkOrSkip(Path link, Path target) throws IOException {
        try {
            Files.createSymbolicLink(link, target);
        } catch (IOException | UnsupportedOperationException | SecurityException unsupported) {
            assumeTrue(false, "This environment cannot create fixture symbolic links.");
        }
    }

    @FunctionalInterface
    private interface CheckedOperation {
        void run() throws Exception;
    }

    private record Configuration(String directory, String keyHex, boolean required, String origin) {
        @Override
        public String toString() {
            return "Configuration[redacted]";
        }
    }
}
