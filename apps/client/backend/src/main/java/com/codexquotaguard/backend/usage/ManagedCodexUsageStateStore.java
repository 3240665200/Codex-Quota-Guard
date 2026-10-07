package com.codexquotaguard.backend.usage;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Stores only hashed scanner cursors and event IDs inside an application-owned
 * state directory. Callers deliberately invoke {@link #save(Path, CodexUsageScanState)}
 * only after central idempotent acceptance.
 */
@Component
public final class ManagedCodexUsageStateStore {

    private static final String STATE_FILE = "usage-scan-state.json";
    private static final int MAX_STATE_BYTES = 16 * 1024 * 1024;

    private final ObjectMapper objectMapper;

    public ManagedCodexUsageStateStore(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public CodexUsageScanState load(Path managedCodexHome) {
        Path stateFile = stateFile(managedCodexHome);
        try {
            if (!Files.isRegularFile(stateFile) || Files.size(stateFile) > MAX_STATE_BYTES) {
                return CodexUsageScanState.empty();
            }
            return objectMapper.readValue(Files.readAllBytes(stateFile), CodexUsageScanState.class);
        } catch (IOException | RuntimeException exception) {
            // Replaying opaque event IDs is safe: the central endpoint is idempotent.
            return CodexUsageScanState.empty();
        }
    }

    public void save(Path managedCodexHome, CodexUsageScanState state) {
        if (state == null) {
            throw new IllegalArgumentException("Usage scan state is required.");
        }
        Path stateFile = stateFile(managedCodexHome);
        Path temporaryFile = null;
        try {
            Files.createDirectories(stateFile.getParent());
            byte[] serialized = objectMapper.writeValueAsBytes(state);
            if (serialized.length > MAX_STATE_BYTES) {
                throw new UsageStateStorageException();
            }
            temporaryFile = Files.createTempFile(stateFile.getParent(), ".usage-state-", ".tmp");
            Files.write(temporaryFile, serialized);
            moveAtomically(temporaryFile, stateFile);
        } catch (IOException | RuntimeException exception) {
            if (exception instanceof UsageStateStorageException stateException) {
                throw stateException;
            }
            throw new UsageStateStorageException();
        } finally {
            if (temporaryFile != null) {
                try {
                    Files.deleteIfExists(temporaryFile);
                } catch (IOException ignored) {
                    // A stale random temporary file cannot advance scanner state.
                }
            }
        }
    }

    Path stateFile(Path managedCodexHome) {
        if (managedCodexHome == null) {
            throw new IllegalArgumentException("Managed Codex home is required.");
        }
        return managedCodexHome.toAbsolutePath().normalize().resolve(STATE_FILE);
    }

    private static void moveAtomically(Path temporaryFile, Path stateFile) throws IOException {
        try {
            Files.move(
                    temporaryFile,
                    stateFile,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
            );
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(temporaryFile, stateFile, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
