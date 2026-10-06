package com.pingche.web.codex;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.sun.nio.file.ExtendedOpenOption;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.DosFileAttributeView;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CodexLoginDirectoryTest {

    @TempDir Path temporary;

    @Test
    void cleanupRemovesReadOnlyWindowsCacheFiles() throws Exception {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"));
        Path directory = CodexLoginDirectory.create(temporary);
        Path file = Files.writeString(directory.resolve("cache.pack"), "test-only");
        Files.getFileAttributeView(file, DosFileAttributeView.class).setReadOnly(true);
        CodexLoginDirectory.remove(temporary, directory);
        assertThat(directory).doesNotExist();
    }

    @Test
    void cleanupRetriesBriefWindowsFileLocks() throws Exception {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"));
        Path directory = CodexLoginDirectory.create(temporary);
        Path file = Files.writeString(directory.resolve("pending.tmp"), "test-only");
        var executor = Executors.newSingleThreadScheduledExecutor();
        try (SeekableByteChannel locked = Files.newByteChannel(file,
                Set.of(StandardOpenOption.READ, ExtendedOpenOption.NOSHARE_DELETE))) {
            var released = executor.schedule(() -> { locked.close(); return true; }, 150, TimeUnit.MILLISECONDS);
            CodexLoginDirectory.remove(temporary, directory);
            assertThat(released.get(1, TimeUnit.SECONDS)).isTrue();
            assertThat(directory).doesNotExist();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void persistentWindowsLocksStillFailClosed() throws Exception {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"));
        Path directory = CodexLoginDirectory.create(temporary);
        Path file = Files.writeString(directory.resolve("pending.tmp"), "test-only");
        try (SeekableByteChannel locked = Files.newByteChannel(file,
                Set.of(StandardOpenOption.READ, ExtendedOpenOption.NOSHARE_DELETE))) {
            assertThatThrownBy(() -> CodexLoginDirectory.remove(temporary, directory))
                    .isInstanceOf(FileSystemException.class);
            assertThat(file).exists();
        }
        CodexLoginDirectory.remove(temporary, directory);
        assertThat(directory).doesNotExist();
    }
}
