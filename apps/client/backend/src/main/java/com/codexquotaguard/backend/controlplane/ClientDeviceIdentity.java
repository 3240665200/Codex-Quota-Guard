package com.codexquotaguard.backend.controlplane;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.UUID;

/** Reads only the fixed installation file; no renderer-supplied device identity is accepted. */
public final class ClientDeviceIdentity {
    private final Path file;
    public ClientDeviceIdentity(Path file) { this.file = file.toAbsolutePath().normalize(); }
    UUID current() {
        try {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > 100) return null;
            return UUID.fromString(Files.readString(file).trim());
        } catch (Exception unavailable) { return null; }
    }
}
