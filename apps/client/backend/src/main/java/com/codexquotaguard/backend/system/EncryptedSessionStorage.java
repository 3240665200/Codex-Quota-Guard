package com.codexquotaguard.backend.system;

import com.codexquotaguard.backend.controlplane.ControlPlaneOrigin;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Origin-bound authenticated ciphertext protected by a stable process-lifetime sidecar lock. */
public final class EncryptedSessionStorage implements SessionStorage {

    private static final int MAGIC = 0x43514753;
    private static final byte VERSION = 1;
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int TAG_BYTES = TAG_BITS / Byte.SIZE;
    private static final int HEADER_BYTES = Integer.BYTES + Byte.BYTES + NONCE_BYTES;
    private static final int FIXED_PLAINTEXT_BYTES = Integer.BYTES + 2 * Long.BYTES
            + 2 * (Long.BYTES + Integer.BYTES);
    private static final int MAX_CIPHERTEXT_BYTES = 8192;
    // Avoid opening a competing channel: on some systems closing it can release another JVM-owned lock.
    private static final ConcurrentMap<Path, EncryptedSessionStorage> PROCESS_OWNERS = new ConcurrentHashMap<>();

    private final Path directory;
    private final Path sessionFile;
    private final Path lockFile;
    private final String fileStem;
    private final byte[] encryptionKey;
    private final byte[] originAad;
    private final SecureRandom random = new SecureRandom();
    private FileChannel lockChannel;
    private FileLock fileLock;
    private Object lockFileKey;
    private boolean closed;

    public EncryptedSessionStorage(Path directory, byte[] encryptionKey, String origin) {
        try {
            if (directory == null || !directory.isAbsolute() || encryptionKey == null
                    || encryptionKey.length != 32) {
                throw new SessionStorageException();
            }
            for (Path component : directory) {
                if (".".equals(component.toString()) || "..".equals(component.toString())) {
                    throw new SessionStorageException();
                }
            }
            this.directory = directory.normalize();
            this.encryptionKey = encryptionKey.clone();
            this.originAad = ControlPlaneOrigin.normalize(origin).toASCIIString()
                    .getBytes(StandardCharsets.UTF_8);
            this.fileStem = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(originAad));
            this.sessionFile = this.directory.resolve(fileStem + ".session");
            this.lockFile = this.directory.resolve(fileStem + ".lock");
        } catch (GeneralSecurityException | RuntimeException invalidConfiguration) {
            throw new SessionStorageException();
        }
    }

    @Override
    public synchronized void ensureAvailable() {
        if (closed) throw new SessionStorageException();
        try {
            if (fileLock != null) {
                verifyHeldLock();
                return;
            }
            verifyDirectory(true);
            if (PROCESS_OWNERS.putIfAbsent(lockFile, this) != null) throw new IOException();
            FileChannel candidate = null;
            FileLock acquired = null;
            boolean accepted = false;
            try {
                BasicFileAttributes before = regularFileAttributes(lockFile, true);
                candidate = FileChannel.open(lockFile, StandardOpenOption.CREATE,
                        StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
                acquired = candidate.tryLock();
                if (acquired == null) throw new IOException();
                verifyDirectory(false);
                BasicFileAttributes after = regularFileAttributes(lockFile, false);
                if (before != null) verifyFileKeyIfAvailable(before.fileKey(), after.fileKey());
                lockChannel = candidate;
                fileLock = acquired;
                lockFileKey = after.fileKey();
                accepted = true;
            } finally {
                if (!accepted) {
                    releaseQuietly(acquired);
                    closeQuietly(candidate);
                    PROCESS_OWNERS.remove(lockFile, this);
                }
            }
        } catch (IOException | RuntimeException unavailable) {
            throw new SessionStorageException();
        }
    }

    @Override
    public synchronized Optional<SavedSession> load() {
        ensureAvailable();
        byte[] ciphertext = null;
        try {
            ciphertext = readBoundedCiphertext();
            if (ciphertext == null) return Optional.empty();
            SavedSession saved = decrypt(ciphertext);
            if (saved != null) return Optional.of(saved);
            clear();
            return Optional.empty();
        } catch (IOException | GeneralSecurityException | RuntimeException unavailable) {
            throw new SessionStorageException();
        } finally {
            if (ciphertext != null) Arrays.fill(ciphertext, (byte) 0);
        }
    }

    @Override
    public synchronized void save(SavedSession session) {
        ensureAvailable();
        if (session == null) throw new SessionStorageException();
        byte[] plaintext = null;
        byte[] ciphertext = null;
        try {
            plaintext = encode(session);
            byte[] nonce = new byte[NONCE_BYTES];
            random.nextBytes(nonce);
            byte[] encrypted = cipher(Cipher.ENCRYPT_MODE, nonce).doFinal(plaintext);
            ciphertext = ByteBuffer.allocate(HEADER_BYTES + encrypted.length)
                    .putInt(MAGIC).put(VERSION).put(nonce).put(encrypted).array();
            Arrays.fill(encrypted, (byte) 0);
            if (ciphertext.length > MAX_CIPHERTEXT_BYTES) throw new IOException();
            writeAtomically(ciphertext);
        } catch (IOException | GeneralSecurityException | RuntimeException unavailable) {
            throw new SessionStorageException();
        } finally {
            if (plaintext != null) Arrays.fill(plaintext, (byte) 0);
            if (ciphertext != null) Arrays.fill(ciphertext, (byte) 0);
        }
    }

    @Override
    public synchronized void clear() {
        ensureAvailable();
        try {
            if (regularFileAttributes(sessionFile, true) != null) {
                try {
                    Files.delete(sessionFile);
                } catch (NoSuchFileException alreadyMissing) {
                    return;
                }
                syncDirectoryIfSupported();
            }
        } catch (IOException | RuntimeException unavailable) {
            throw new SessionStorageException();
        }
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        releaseQuietly(fileLock);
        closeQuietly(lockChannel);
        PROCESS_OWNERS.remove(lockFile, this);
        fileLock = null;
        lockChannel = null;
        lockFileKey = null;
        Arrays.fill(encryptionKey, (byte) 0);
    }

    private void verifyHeldLock() throws IOException {
        if (!fileLock.isValid() || lockChannel == null || !lockChannel.isOpen()) throw new IOException();
        verifyDirectory(false);
        BasicFileAttributes attributes = regularFileAttributes(lockFile, false);
        verifyFileKeyIfAvailable(lockFileKey, attributes.fileKey());
    }

    private static void verifyFileKeyIfAvailable(Object expected, Object actual) throws IOException {
        // Windows exposes no BasicFileAttributes.fileKey; keep the held lock and validated paths there.
        if (expected != null && !Objects.equals(expected, actual)) throw new IOException();
    }

    private void verifyDirectory(boolean createMissing) throws IOException {
        Path current = directory.getRoot();
        if (current == null) throw new IOException();
        verifyDirectoryComponent(current, false);
        for (Path component : directory) {
            current = current.resolve(component);
            verifyDirectoryComponent(current, createMissing);
        }
    }

    private static void verifyDirectoryComponent(Path component, boolean createMissing) throws IOException {
        BasicFileAttributes attributes;
        try {
            attributes = Files.readAttributes(component, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException missing) {
            if (!createMissing) throw missing;
            try {
                if (component.getFileSystem().supportedFileAttributeViews().contains("posix")) {
                    Files.createDirectory(component,
                            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
                } else {
                    Files.createDirectory(component);
                }
            } catch (FileAlreadyExistsException concurrentlyCreated) {
                // Inspect the actual object before traversing or opening anything below it.
            }
            attributes = Files.readAttributes(component, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        }
        if (!attributes.isDirectory() || attributes.isSymbolicLink() || attributes.isOther()
                || !component.toRealPath().equals(component)) {
            throw new IOException();
        }
    }

    private static BasicFileAttributes regularFileAttributes(Path file, boolean allowMissing) throws IOException {
        final BasicFileAttributes attributes;
        try {
            attributes = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException missing) {
            if (allowMissing) return null;
            throw missing;
        }
        if (!attributes.isRegularFile() || attributes.isSymbolicLink() || attributes.isOther()
                || !file.toRealPath().equals(file)) {
            throw new IOException();
        }
        return attributes;
    }

    private byte[] readBoundedCiphertext() throws IOException {
        if (regularFileAttributes(sessionFile, true) == null) return null;
        ByteBuffer bounded = ByteBuffer.allocate(MAX_CIPHERTEXT_BYTES + 1);
        final FileChannel opened;
        try {
            opened = FileChannel.open(sessionFile, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException alreadyMissing) {
            return null;
        }
        try (FileChannel channel = opened) {
            verifyHeldLock();
            regularFileAttributes(sessionFile, false);
            while (bounded.hasRemaining() && channel.read(bounded) != -1) {
                // Never allocate or consume more than the fixed ciphertext limit plus one byte.
            }
        }
        return Arrays.copyOf(bounded.array(), bounded.position());
    }

    private SavedSession decrypt(byte[] ciphertext) throws GeneralSecurityException {
        if (ciphertext.length > MAX_CIPHERTEXT_BYTES
                || ciphertext.length < HEADER_BYTES + TAG_BYTES + FIXED_PLAINTEXT_BYTES + 1) {
            return null;
        }
        ByteBuffer envelope = ByteBuffer.wrap(ciphertext);
        if (envelope.getInt() != MAGIC || envelope.get() != VERSION) return null;
        byte[] nonce = new byte[NONCE_BYTES];
        envelope.get(nonce);
        Cipher decryptor = cipher(Cipher.DECRYPT_MODE, nonce);
        byte[] plaintext = null;
        try {
            plaintext = decryptor.doFinal(ciphertext, HEADER_BYTES, ciphertext.length - HEADER_BYTES);
            return decode(plaintext);
        } catch (BadPaddingException | IllegalBlockSizeException corrupted) {
            return null;
        } finally {
            if (plaintext != null) Arrays.fill(plaintext, (byte) 0);
        }
    }

    private Cipher cipher(int mode, byte[] nonce) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, new SecretKeySpec(encryptionKey, "AES"), new GCMParameterSpec(TAG_BITS, nonce));
        cipher.updateAAD(originAad);
        return cipher;
    }

    private static byte[] encode(SavedSession session) {
        byte[] token = session.refreshToken().getBytes(StandardCharsets.UTF_8);
        try {
            return ByteBuffer.allocate(FIXED_PLAINTEXT_BYTES + token.length)
                    .putInt(token.length).put(token)
                    .putLong(session.userId().getMostSignificantBits())
                    .putLong(session.userId().getLeastSignificantBits())
                    .putLong(session.loginAt().getEpochSecond()).putInt(session.loginAt().getNano())
                    .putLong(session.expiresAt().getEpochSecond()).putInt(session.expiresAt().getNano())
                    .array();
        } finally {
            Arrays.fill(token, (byte) 0);
        }
    }

    private static SavedSession decode(byte[] plaintext) {
        try {
            if (plaintext.length < FIXED_PLAINTEXT_BYTES + 1) return null;
            ByteBuffer content = ByteBuffer.wrap(plaintext);
            int tokenLength = content.getInt();
            if (tokenLength < 1 || tokenLength > SavedSession.MAX_REFRESH_TOKEN_BYTES
                    || content.remaining() != tokenLength + FIXED_PLAINTEXT_BYTES - Integer.BYTES) {
                return null;
            }
            ByteBuffer tokenBytes = content.slice();
            tokenBytes.limit(tokenLength);
            String token = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(tokenBytes).toString();
            content.position(content.position() + tokenLength);
            UUID userId = new UUID(content.getLong(), content.getLong());
            Instant loginAt = decodeInstant(content);
            Instant expiresAt = decodeInstant(content);
            return new SavedSession(token, userId, loginAt, expiresAt);
        } catch (CharacterCodingException | RuntimeException invalidSavedSession) {
            return null;
        }
    }

    private static Instant decodeInstant(ByteBuffer content) {
        long seconds = content.getLong();
        int nanos = content.getInt();
        if (nanos < 0 || nanos >= 1_000_000_000) throw new IllegalArgumentException();
        return Instant.ofEpochSecond(seconds, nanos);
    }

    private void writeAtomically(byte[] ciphertext) throws IOException {
        Path temporary = null;
        Object temporaryFileKey = null;
        try {
            verifyHeldLock();
            regularFileAttributes(sessionFile, true);
            temporary = Files.createTempFile(directory, "." + fileStem + "-", ".tmp");
            temporaryFileKey = regularFileAttributes(temporary, false).fileKey();
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS)) {
                ByteBuffer content = ByteBuffer.wrap(ciphertext);
                while (content.hasRemaining()) channel.write(content);
                channel.force(true);
            }
            verifyHeldLock();
            BasicFileAttributes pending = regularFileAttributes(temporary, false);
            verifyFileKeyIfAvailable(temporaryFileKey, pending.fileKey());
            regularFileAttributes(sessionFile, true);
            Files.move(temporary, sessionFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            temporary = null;
            verifyHeldLock();
            BasicFileAttributes moved = regularFileAttributes(sessionFile, false);
            verifyFileKeyIfAvailable(temporaryFileKey, moved.fileKey());
            try (FileChannel target = FileChannel.open(sessionFile, StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS)) {
                target.force(true);
            }
            syncDirectoryIfSupported();
        } finally {
            if (temporary != null) deleteOwnTemporaryQuietly(temporary, temporaryFileKey);
        }
    }

    private void syncDirectoryIfSupported() throws IOException {
        verifyHeldLock();
        if (Files.getFileAttributeView(directory, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS) != null) {
            try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ,
                    LinkOption.NOFOLLOW_LINKS)) {
                channel.force(true);
            }
        }
    }

    private void deleteOwnTemporaryQuietly(Path temporary, Object expectedFileKey) {
        try {
            verifyHeldLock();
            BasicFileAttributes attributes = regularFileAttributes(temporary, true);
            if (attributes != null) {
                verifyFileKeyIfAvailable(expectedFileKey, attributes.fileKey());
                Files.deleteIfExists(temporary);
            }
        } catch (IOException | RuntimeException ignored) {
            // Never follow or delete a replacement object while cleaning up this write's own temporary file.
        }
    }

    private static void releaseQuietly(FileLock lock) {
        if (lock == null) return;
        try { lock.release(); } catch (IOException ignored) { }
    }

    private static void closeQuietly(FileChannel channel) {
        if (channel == null) return;
        try { channel.close(); } catch (IOException ignored) { }
    }
}
