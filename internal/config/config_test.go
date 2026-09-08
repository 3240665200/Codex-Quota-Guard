package config

import (
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"codex-quota-guard/internal/core"
)

func TestNewOwnerCreatesPinnedRoomAndIsolatedManagedCodexHome(t *testing.T) {
	base := t.TempDir()
	roomDir := filepath.Join(base, "shared-room")
	dataDir := filepath.Join(base, "local-owner")
	now := time.Date(2026, 9, 7, 14, 0, 0, 0, time.UTC)

	cfg, manifest, err := NewOwner(roomDir, dataDir, "Design Team", "Alice", true, now)
	if err != nil {
		t.Fatalf("NewOwner() error = %v", err)
	}
	if cfg.Role != RoleOwner || cfg.RoomID != manifest.RoomID || cfg.OwnerPublicKey != manifest.OwnerPublicKey {
		t.Fatalf("owner config and manifest are inconsistent: cfg=%#v manifest=%#v", cfg, manifest)
	}
	if cfg.OwnerPrivateKey == "" || cfg.MemberPrivateKey == "" || cfg.OwnerPrivateKey == cfg.MemberPrivateKey {
		t.Fatal("owner and member signing keys must both exist and differ")
	}
	if cfg.ManagedCodexHome != filepath.Join(dataDir, "codex-home") || !cfg.ManagedLoginOnly {
		t.Fatalf("managed login config = %#v", cfg)
	}
	if !cfg.EnforcementConsent {
		t.Fatal("enforcement consent was not recorded")
	}
	if err := cfg.Validate(); err != nil {
		t.Fatalf("Validate() error = %v", err)
	}
}

func TestNewMemberPinsOwnerKeyAndNeverCopiesCredentials(t *testing.T) {
	base := t.TempDir()
	roomDir := filepath.Join(base, "shared-room")
	ownerData := filepath.Join(base, "owner")
	memberData := filepath.Join(base, "member")
	now := time.Now().UTC()
	_, manifest, err := NewOwner(roomDir, ownerData, "Team", "Owner", true, now)
	if err != nil {
		t.Fatal(err)
	}

	cfg, err := NewMember(roomDir, memberData, manifest, "Bob", true, now)
	if err != nil {
		t.Fatalf("NewMember() error = %v", err)
	}
	if cfg.Role != RoleMember || cfg.OwnerPrivateKey != "" || cfg.OwnerPublicKey != manifest.OwnerPublicKey {
		t.Fatalf("member config = %#v", cfg)
	}
	if cfg.ManagedCodexHome != filepath.Join(memberData, "codex-home") {
		t.Fatalf("ManagedCodexHome = %q", cfg.ManagedCodexHome)
	}

	configPath := filepath.Join(memberData, "config.json")
	if err := Save(configPath, cfg); err != nil {
		t.Fatal(err)
	}
	data, err := os.ReadFile(configPath)
	if err != nil {
		t.Fatal(err)
	}
	text := strings.ToLower(string(data))
	for _, forbidden := range []string{"access_token", "refresh_token", "openai_api_key", "alice@example.com"} {
		if strings.Contains(text, forbidden) {
			t.Fatalf("config leaked forbidden credential material %q: %s", forbidden, data)
		}
	}
	loaded, err := Load(configPath)
	if err != nil {
		t.Fatal(err)
	}
	if loaded.MemberID != cfg.MemberID || loaded.OwnerPublicKey != cfg.OwnerPublicKey {
		t.Fatalf("loaded config mismatch: %#v", loaded)
	}
}

func TestMemberProfileIsSelfSignedAndDeclaresEnforcementCapability(t *testing.T) {
	base := t.TempDir()
	cfg, manifest, err := NewOwner(filepath.Join(base, "room"), filepath.Join(base, "data"), "Team", "Alice", true, time.Now().UTC())
	if err != nil {
		t.Fatal(err)
	}
	document, err := cfg.SignedMemberProfile(time.Now().UTC())
	if err != nil {
		t.Fatal(err)
	}
	if document.Payload.RoomID != manifest.RoomID || !document.Payload.ManagedLogin || !document.Payload.EnforcementConsent {
		t.Fatalf("profile = %#v", document.Payload)
	}
	key, err := cfg.MemberPublicKeyBytes()
	if err != nil {
		t.Fatal(err)
	}
	if err := core.Verify(document, key); err != nil {
		t.Fatalf("profile signature error = %v", err)
	}
	encoded, _ := json.Marshal(document)
	if strings.Contains(string(encoded), cfg.MemberPrivateKey) {
		t.Fatal("shared member profile contains private key")
	}
}

