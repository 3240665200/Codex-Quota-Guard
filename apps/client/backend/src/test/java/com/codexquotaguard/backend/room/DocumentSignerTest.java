package com.codexquotaguard.backend.room;

import org.junit.jupiter.api.Test;

import java.security.KeyPairGenerator;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocumentSignerTest {

    @Test
    void signsAndVerifiesDeterministicPayloads() throws Exception {
        var keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var manifest = new RoomManifest(1, "room_design", "Design", "member_alice", "public-key", Instant.parse("2026-09-08T00:00:00Z"));

        var signed = DocumentSigner.sign(manifest, keyPair.getPrivate());

        assertThat(signed.signature()).isNotBlank();
        assertThat(DocumentSigner.verify(signed.payload(), signed.signature(), keyPair.getPublic())).isTrue();
        assertThat(DocumentSigner.verify(
                new RoomManifest(1, "room_design", "Changed", "member_alice", "public-key", manifest.createdAt()),
                signed.signature(),
                keyPair.getPublic()
        )).isFalse();
    }

    @Test
    void rejectsInvalidSignatureEncodingAndKeys() {
        assertThatThrownBy(() -> DocumentSigner.verify(new RoomManifest(1, "room", "Team", "member", "key", Instant.now()), "bad", null))
                .isInstanceOf(DocumentSignatureException.class);
    }
}
