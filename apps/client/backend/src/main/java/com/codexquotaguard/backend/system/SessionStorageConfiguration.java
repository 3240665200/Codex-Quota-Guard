package com.codexquotaguard.backend.system;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Desktop configuration fails on use, keeping unrelated local diagnostics available. */
@Configuration(proxyBeanMethods = false)
public class SessionStorageConfiguration {

    @Bean(destroyMethod = "close")
    SessionStorage sessionStorage(
            @Value("${quota-guard.session-storage.directory:}") String directory,
            @Value("${quota-guard.session-storage.key:}") String keyHex,
            @Value("${quota-guard.session-storage.required:false}") boolean required,
            @Value("${quota-guard.control-plane.base-url:http://127.0.0.1:8080}") String origin) {
        return create(directory, keyHex, required, origin);
    }

    public static SessionStorage create(String directory, String keyHex, boolean required, String origin) {
        boolean hasDirectory = directory != null && !directory.isBlank();
        boolean hasKey = keyHex != null && !keyHex.isBlank();
        if (!hasDirectory && !hasKey && !required) {
            return NoopStorage.INSTANCE;
        }
        if (!hasDirectory || !hasKey || !keyHex.matches("[0-9a-fA-F]{64}")) {
            return UnavailableStorage.INSTANCE;
        }
        byte[] decodedKey = null;
        try {
            decodedKey = HexFormat.of().parseHex(keyHex);
            return new EncryptedSessionStorage(Path.of(directory), decodedKey, origin);
        } catch (RuntimeException invalidConfiguration) {
            return UnavailableStorage.INSTANCE;
        } finally {
            if (decodedKey != null) Arrays.fill(decodedKey, (byte) 0);
        }
    }

    private enum NoopStorage implements SessionStorage {
        INSTANCE;

        @Override public void ensureAvailable() { }
        @Override public Optional<SavedSession> load() { return Optional.empty(); }
        @Override public void save(SavedSession session) { }
        @Override public void clear() { }
        @Override public void close() { }
    }

    private enum UnavailableStorage implements SessionStorage {
        INSTANCE;

        @Override public void ensureAvailable() { throw new SessionStorageException(); }
        @Override public Optional<SavedSession> load() { throw new SessionStorageException(); }
        @Override public void save(SavedSession session) { throw new SessionStorageException(); }
        @Override public void clear() { throw new SessionStorageException(); }
        @Override public void close() { }
    }
}
