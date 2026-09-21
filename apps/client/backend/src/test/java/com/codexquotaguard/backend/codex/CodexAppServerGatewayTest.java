package com.codexquotaguard.backend.codex;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class CodexAppServerGatewayTest {

    @Test
    void prefersTheNativeExecutableOverANpmShimOnWindows(@TempDir Path directory) throws IOException {
        var executable = Files.createFile(directory.resolve("codex.exe"));

        var resolved = CodexAppServerGateway.resolveCommand("codex", "Windows 11", directory.toString());

        assertThat(resolved).isEqualTo(executable.toAbsolutePath().toString());
    }

    @Test
    void preservesConfiguredCommandsAndNonWindowsLookup(@TempDir Path directory) {
        assertThat(CodexAppServerGateway.resolveCommand("C:\\tools\\codex.exe", "Windows 11", directory.toString()))
                .isEqualTo("C:\\tools\\codex.exe");
        assertThat(CodexAppServerGateway.resolveCommand("codex", "Linux", directory.toString()))
                .isEqualTo("codex");
    }
}
