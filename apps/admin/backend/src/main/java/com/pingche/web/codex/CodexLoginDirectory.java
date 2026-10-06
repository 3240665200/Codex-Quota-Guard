package com.pingche.web.codex;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.FileSystemException;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclEntryFlag;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.DosFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;

final class CodexLoginDirectory {

    private CodexLoginDirectory() { }

    static Path create(Path configuredRoot) throws IOException {
        Path root = configuredRoot.toAbsolutePath().normalize();
        Files.createDirectories(root);
        if (Files.isSymbolicLink(root)) throw new IOException("Authorization root cannot be a symlink");
        restrict(root);
        Path directory = Files.createTempDirectory(root, "login-");
        try {
            restrict(directory);
            return directory;
        } catch (IOException exception) {
            Files.deleteIfExists(directory);
            throw exception;
        }
    }

    private static void restrict(Path directory) throws IOException {
        if (Files.getFileAttributeView(directory, PosixFileAttributeView.class) != null) {
            Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwx------"));
            return;
        }
        AclFileAttributeView acl = Files.getFileAttributeView(directory, AclFileAttributeView.class);
        if (acl == null) throw new IOException("Private authorization directory permissions unavailable");
        acl.setAcl(List.of(AclEntry.newBuilder()
                .setType(AclEntryType.ALLOW)
                .setPrincipal(Files.getOwner(directory))
                .setPermissions(EnumSet.allOf(AclEntryPermission.class))
                .setFlags(AclEntryFlag.DIRECTORY_INHERIT, AclEntryFlag.FILE_INHERIT)
                .build()));
    }

    static void remove(Path root, Path directory) throws IOException {
        Path expectedRoot = root.toAbsolutePath().normalize();
        Path target = directory.toAbsolutePath().normalize();
        if (!expectedRoot.equals(target.getParent()) || !target.getFileName().toString().startsWith("login-")) {
            throw new IOException("Refusing to remove a directory outside the authorization workspace");
        }
        for (int attempt = 0; ; attempt++) {
            if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return;
            try {
                // Close the traversal before deletion; never follow links beyond the workspace.
                List<Path> entries;
                try (var paths = Files.walk(target)) {
                    entries = paths.sorted(Comparator.reverseOrder()).toList();
                }
                for (Path path : entries) {
                    // Git/cache files may have the DOS read-only flag; do not change link targets.
                    if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                        var dos = Files.getFileAttributeView(path, DosFileAttributeView.class,
                                LinkOption.NOFOLLOW_LINKS);
                        if (dos != null && dos.readAttributes().isReadOnly()) dos.setReadOnly(false);
                    }
                    Files.deleteIfExists(path);
                }
                return;
            } catch (FileSystemException exception) {
                // Windows may briefly retain file handles after the child process has exited.
                // Keep failure closed, but allow at most one second for those handles to release.
                if (attempt >= 4) throw exception;
                try {
                    Thread.sleep(100L * (attempt + 1));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new InterruptedIOException("Authorization cleanup interrupted");
                }
            }
        }
    }
}
