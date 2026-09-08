package com.codexquotaguard.backend.room;

import com.codexquotaguard.backend.quota.QuotaSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RoomDocumentStoreTest {

    @TempDir
    Path temporaryRoot;

    @Test
    void roundTripsSignedMembersSnapshotsAndOwnerPolicy() throws Exception {
        var ownerKeys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var memberKeys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var store = new RoomDocumentStore(temporaryRoot);
        store.ensureLayout();

        var manifest = new RoomManifest(1, "room_design", "Design", "member_alice", DocumentSigner.encodePublicKey(ownerKeys.getPublic()), Instant.now());
        store.writeManifest(manifest);
        var member = new MemberProfile(1, manifest.roomId(), "member_alice", "Alice", DocumentSigner.encodePublicKey(memberKeys.getPublic()), Instant.now(), true, true);
        store.writeMember(DocumentSigner.sign(member, memberKeys.getPrivate()));
        var snapshot = new QuotaSnapshot(1, manifest.roomId(), member.memberId(), member.displayName(), Instant.now(), Instant.now(), "codex-app-server", true, "plus", List.of(), true);
        store.writeSnapshot(DocumentSigner.sign(snapshot, memberKeys.getPrivate()));
        var policy = new RoomPolicy(1, manifest.roomId(), 1L, Instant.now(), java.util.Map.of(member.memberId(), new MemberPolicy(member.memberId(), true, List.of(new QuotaRule("codex", "primary", 80.0, "logout")))));
        store.writePolicy(DocumentSigner.sign(policy, ownerKeys.getPrivate()));

        var members = store.readMembers(manifest.roomId());
        var snapshots = store.readSnapshots(manifest.roomId(), members.members());
        var loadedPolicy = store.readPolicy(manifest.roomId(), ownerKeys.getPublic());

        assertThat(members.warnings()).isEmpty();
        assertThat(members.members()).containsKey(member.memberId());
        assertThat(snapshots.snapshots()).singleElement().extracting(QuotaSnapshot::memberId).isEqualTo(member.memberId());
        assertThat(snapshots.warnings()).isEmpty();
        assertThat(loadedPolicy.payload().revision()).isEqualTo(1L);
        assertThat(Files.exists(temporaryRoot.resolve("members/member_alice.json"))).isTrue();
    }

    @Test
    void skipsTamperedAndUnknownSnapshotsWithWarnings() throws Exception {
        var memberKeys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var store = new RoomDocumentStore(temporaryRoot);
        store.ensureLayout();
        var member = new MemberProfile(1, "room_design", "member_alice", "Alice", DocumentSigner.encodePublicKey(memberKeys.getPublic()), Instant.now(), true, true);
        store.writeMember(DocumentSigner.sign(member, memberKeys.getPrivate()));
        var other = new QuotaSnapshot(1, "room_design", "member_bob", "Bob", Instant.now(), Instant.now(), "codex-app-server", true, "plus", List.of(), true);
        Files.writeString(store.root().resolve("snapshots/member_bob.json"), "{\"payload\":" + DocumentSigner.json(other) + ",\"signature\":\"bad\"}");

        var members = store.readMembers("room_design");
        var snapshots = store.readSnapshots("room_design", members.members());

        assertThat(snapshots.snapshots()).isEmpty();
        assertThat(snapshots.warnings()).hasSize(1);
    }
}
