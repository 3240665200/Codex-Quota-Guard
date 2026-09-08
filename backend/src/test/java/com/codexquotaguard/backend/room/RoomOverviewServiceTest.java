package com.codexquotaguard.backend.room;

import com.codexquotaguard.backend.quota.QuotaSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RoomOverviewServiceTest {

    @TempDir
    Path temporaryRoot;

    @Test
    void reportsAnUnconfiguredRoomWithoutThrowing() {
        var service = new RoomOverviewService(new RoomDocumentStore(temporaryRoot));

        var overview = service.readOverview();

        assertThat(overview.configured()).isFalse();
        assertThat(overview.roomId()).isEmpty();
        assertThat(overview.members()).isEmpty();
    }

    @Test
    void returnsOnlyVerifiedMembersAndTheirLatestSnapshots() throws Exception {
        var ownerKeys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var memberKeys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var store = new RoomDocumentStore(temporaryRoot);
        store.ensureLayout();
        var manifest = new RoomManifest(1, "room_design", "Design", "member_alice", DocumentSigner.encodePublicKey(ownerKeys.getPublic()), Instant.now());
        store.writeManifest(manifest);
        var member = new MemberProfile(1, "room_design", "member_alice", "Alice", DocumentSigner.encodePublicKey(memberKeys.getPublic()), Instant.now(), true, true);
        store.writeMember(DocumentSigner.sign(member, memberKeys.getPrivate()));
        var snapshot = new QuotaSnapshot(1, "room_design", "member_alice", "Alice", Instant.now(), Instant.now(), "codex-app-server", true, "plus", List.of(), true);
        store.writeSnapshot(DocumentSigner.sign(snapshot, memberKeys.getPrivate()));

        var overview = new RoomOverviewService(store).readOverview();

        assertThat(overview.configured()).isTrue();
        assertThat(overview.roomId()).isEqualTo("room_design");
        assertThat(overview.members()).singleElement().satisfies(view -> {
            assertThat(view.memberId()).isEqualTo("member_alice");
            assertThat(view.displayName()).isEqualTo("Alice");
            assertThat(view.snapshot()).isNotNull();
        });
        assertThat(overview.warnings()).isEmpty();
    }
}
