package store

import (
	"crypto/ed25519"
	"os"
	"path/filepath"
	"testing"
	"time"

	"codex-quota-guard/internal/core"
)

func TestStoreRoundTripVerifiesMembersSnapshotsAndPolicy(t *testing.T) {
	root := t.TempDir()
	roomStore := New(root)
	if err := roomStore.EnsureLayout(); err != nil {
		t.Fatal(err)
	}

	ownerPublic, ownerPrivate, _ := ed25519.GenerateKey(nil)
	memberPublic, memberPrivate, _ := ed25519.GenerateKey(nil)
	manifest := core.RoomManifest{SchemaVersion: core.SchemaVersion, RoomID: "room_1", Name: "Team", OwnerPublicKey: EncodePublicKey(ownerPublic), CreatedAt: time.Now().UTC()}
	if err := roomStore.WriteManifest(manifest); err != nil {
		t.Fatal(err)
	}

	memberDoc, err := core.Sign(core.MemberProfile{SchemaVersion: core.SchemaVersion, RoomID: "room_1", MemberID: "member_1", DisplayName: "Alice", PublicKey: EncodePublicKey(memberPublic)}, memberPrivate)
	if err != nil {
		t.Fatal(err)
	}
	if err := roomStore.WriteMember(memberDoc); err != nil {
		t.Fatal(err)
	}

	snapshotDoc, err := core.Sign(core.Snapshot{SchemaVersion: core.SchemaVersion, RoomID: "room_1", MemberID: "member_1", DisplayName: "Alice", CapturedAt: time.Now().UTC()}, memberPrivate)
	if err != nil {
		t.Fatal(err)
	}
	if err := roomStore.WriteSnapshot(snapshotDoc); err != nil {
		t.Fatal(err)
	}

	policyDoc, err := core.Sign(core.Policy{SchemaVersion: core.SchemaVersion, RoomID: "room_1", Revision: 1, UpdatedAt: time.Now().UTC(), Members: map[string]core.MemberPolicy{}}, ownerPrivate)
	if err != nil {
		t.Fatal(err)
	}
	if err := roomStore.WritePolicy(policyDoc); err != nil {
		t.Fatal(err)
	}

	members, warnings, err := roomStore.ReadMembers("room_1")
	if err != nil || len(warnings) != 0 || len(members) != 1 {
		t.Fatalf("ReadMembers() members=%#v warnings=%#v err=%v", members, warnings, err)
	}
	snapshots, warnings, err := roomStore.ReadSnapshots("room_1", members)
	if err != nil || len(warnings) != 0 || len(snapshots) != 1 {
		t.Fatalf("ReadSnapshots() snapshots=%#v warnings=%#v err=%v", snapshots, warnings, err)
	}
	policy, err := roomStore.ReadPolicy("room_1", ownerPublic)
	if err != nil || policy.Payload.Revision != 1 {
		t.Fatalf("ReadPolicy() policy=%#v err=%v", policy, err)
	}
}

func TestStoreIgnoresCorruptAndUnsignedSnapshots(t *testing.T) {
	root := t.TempDir()
	roomStore := New(root)
	if err := roomStore.EnsureLayout(); err != nil {
		t.Fatal(err)
	}
	publicKey, privateKey, _ := ed25519.GenerateKey(nil)
	member := core.MemberProfile{SchemaVersion: core.SchemaVersion, RoomID: "room_1", MemberID: "member_1", DisplayName: "Alice", PublicKey: EncodePublicKey(publicKey)}
	signedMember, _ := core.Sign(member, privateKey)
	if err := roomStore.WriteMember(signedMember); err != nil {
		t.Fatal(err)
	}

	badPath := filepath.Join(root, "snapshots", "member_1.json")
	if err := os.WriteFile(badPath, []byte(`{"payload":{"roomId":"room_1","memberId":"member_1"},"signature":"not-valid"}`), 0o600); err != nil {
		t.Fatal(err)
	}
	members, _, err := roomStore.ReadMembers("room_1")
	if err != nil {
		t.Fatal(err)
	}
	snapshots, warnings, err := roomStore.ReadSnapshots("room_1", members)
	if err != nil {
		t.Fatal(err)
	}
	if len(snapshots) != 0 || len(warnings) != 1 {
		t.Fatalf("snapshots=%#v warnings=%#v", snapshots, warnings)
	}
}

