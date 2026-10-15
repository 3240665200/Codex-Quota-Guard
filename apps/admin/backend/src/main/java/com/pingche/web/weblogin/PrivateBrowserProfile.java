package com.pingche.web.weblogin;

import java.io.IOException;
import java.net.URI;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** One owner-private newly created directory. Never opens or cleans a normal browser profile. */
final class PrivateBrowserProfile implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(PrivateBrowserProfile.class);
    private static final String PREFIX = "pingche-web-login-";
    private final Path root;
    private final Path parent;
    private final UserPrincipal owner;
    private final Object fileKey;
    private final AtomicBoolean closing = new AtomicBoolean();
    private final AtomicBoolean deleted = new AtomicBoolean();
    private PrivateBrowserProfile(Path root, Path parent, UserPrincipal owner) throws IOException {
        this.root = root; this.parent = parent; this.owner = owner;
        this.fileKey = Files.readAttributes(root, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).fileKey(); verify();
    }
    static PrivateBrowserProfile create(Path parent) {
        try {
            Path base = parent.toRealPath();
            if (!Files.isDirectory(base, LinkOption.NOFOLLOW_LINKS)) throw new IOException();
            UserPrincipal owner = base.getFileSystem().getUserPrincipalLookupService().lookupPrincipalByName(System.getProperty("user.name"));
            Path root;
            if (Files.getFileAttributeView(base, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS) != null) {
                AclEntry onlyOwner = AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(owner)
                        .setPermissions(EnumSet.allOf(AclEntryPermission.class))
                        .setFlags(AclEntryFlag.DIRECTORY_INHERIT, AclEntryFlag.FILE_INHERIT).build();
                FileAttribute<List<AclEntry>> acl = new FileAttribute<>() {
                    @Override public String name() { return "acl:acl"; }
                    @Override public List<AclEntry> value() { return List.of(onlyOwner); }
                };
                root = Files.createTempDirectory(base, PREFIX, acl);
            } else if (Files.getFileAttributeView(base, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS) != null) {
                root = Files.createTempDirectory(base, PREFIX, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            } else throw new IOException();
            return new PrivateBrowserProfile(root, base, owner);
        } catch (IOException | RuntimeException ignored) { throw new IllegalStateException("WEB_LOGIN_PROFILE_UNAVAILABLE"); }
    }
    Path path() { return root; }
    URI debuggerUri() {
        try {
            verify(); Path file = root.resolve("DevToolsActivePort");
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > 512
                    || !Files.getOwner(file, LinkOption.NOFOLLOW_LINKS).equals(owner)) throw new IOException();
            List<String> lines = Files.readAllLines(file);
            if (lines.size() < 2 || !lines.get(0).matches("[1-9][0-9]{0,4}")
                    || !lines.get(1).matches("/devtools/browser/[A-Za-z0-9-]{1,100}")) throw new IOException();
            int port = Integer.parseInt(lines.get(0)); if (port > 65535) throw new IOException();
            return URI.create("ws://127.0.0.1:" + port + lines.get(1));
        } catch (IOException | RuntimeException ignored) { throw new IllegalStateException("WEB_LOGIN_BROWSER_UNAVAILABLE"); }
    }
    private void verify() throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(root, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!root.getParent().equals(parent) || !root.getFileName().toString().startsWith(PREFIX)
                || !attributes.isDirectory() || attributes.isSymbolicLink() || attributes.isOther()
                || !root.toRealPath().equals(root) || !Files.getOwner(root, LinkOption.NOFOLLOW_LINKS).equals(owner)
                || fileKey != null && !fileKey.equals(attributes.fileKey())) throw new IOException();
        AclFileAttributeView acl = Files.getFileAttributeView(root, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (acl != null) {
            if (acl.getAcl().stream().anyMatch(entry -> entry.type() == AclEntryType.ALLOW && !entry.principal().equals(owner))) throw new IOException();
        } else if (Files.getPosixFilePermissions(root, LinkOption.NOFOLLOW_LINKS).stream()
                .anyMatch(value -> value.name().startsWith("GROUP_") || value.name().startsWith("OTHERS_"))) throw new IOException();
    }
    @Override public void close() {
        if (deleted.get() || !closing.compareAndSet(false, true)) return;
        boolean interrupted = Thread.interrupted();
        try {
            for (int attempt = 0; attempt < 20; attempt++) {
                try {
                    // A retry never trusts metadata from an earlier attempt or expands the cleanup scope.
                    removeOwnedTree(); deleted.set(true); return;
                } catch (IOException | RuntimeException ignored) {
                    if (attempt < 19) {
                        try { Thread.sleep(100); }
                        catch (InterruptedException signal) { interrupted = true; }
                    }
                }
            }
            LOG.warn("Managed login temporary profile cleanup incomplete profileId={}", root.getFileName());
        } finally {
            closing.set(false);
            if (interrupted) Thread.currentThread().interrupt();
        }
    }
    private void removeOwnedTree() throws IOException {
            verify();
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attrs) throws IOException {
                    if (attrs.isSymbolicLink() || attrs.isOther() || !directory.toRealPath().startsWith(root)) throw new IOException();
                    return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    if (!file.normalize().startsWith(root)) throw new IOException();
                    Files.delete(file); return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult postVisitDirectory(Path directory, IOException failure) throws IOException {
                    if (failure != null) throw failure;
                    Files.delete(directory); return FileVisitResult.CONTINUE;
                }
            });
    }
}
