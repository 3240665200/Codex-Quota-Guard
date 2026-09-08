package store

import (
	"crypto/ed25519"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strings"

	"codex-quota-guard/internal/core"
)

const maxDocumentBytes = 1 << 20

var safeID = regexp.MustCompile(`^[A-Za-z0-9_-]{1,96}$`)

type Store struct {
	root string
}

type VerifiedMember struct {
	Profile   core.MemberProfile
	PublicKey ed25519.PublicKey
}

func New(root string) *Store {
	return &Store{root: filepath.Clean(root)}
}

func (s *Store) EnsureLayout() error {
	if !filepath.IsAbs(s.root) {
		return errors.New("room directory must be absolute")
	}
	for _, path := range []string{s.root, filepath.Join(s.root, "members"), filepath.Join(s.root, "snapshots")} {
		if err := os.MkdirAll(path, 0o700); err != nil {
			return fmt.Errorf("create room directory %q: %w", path, err)
		}
	}
	return nil
}

func EncodePublicKey(key ed25519.PublicKey) string {
	return base64.RawURLEncoding.EncodeToString(key)
}

func DecodePublicKey(value string) (ed25519.PublicKey, error) {
	decoded, err := base64.RawURLEncoding.DecodeString(value)
	if err != nil || len(decoded) != ed25519.PublicKeySize {
		return nil, errors.New("invalid Ed25519 public key")
	}
	return ed25519.PublicKey(decoded), nil
}

func (s *Store) WriteManifest(manifest core.RoomManifest) error {
	if err := validateRoomID(manifest.RoomID); err != nil {
		return err
	}
	if _, err := DecodePublicKey(manifest.OwnerPublicKey); err != nil {
		return fmt.Errorf("owner public key: %w", err)
	}
	return writeJSONAtomic(filepath.Join(s.root, "room.json"), manifest)
}

func (s *Store) ReadManifest() (core.RoomManifest, error) {
	var manifest core.RoomManifest
	if err := readJSON(filepath.Join(s.root, "room.json"), &manifest); err != nil {
		return manifest, err
	}
	if err := validateRoomID(manifest.RoomID); err != nil {
		return manifest, err
	}
	if _, err := DecodePublicKey(manifest.OwnerPublicKey); err != nil {
		return manifest, err
	}
	return manifest, nil
}

func (s *Store) WriteMember(document core.Signed[core.MemberProfile]) error {
	if err := validateMember(document.Payload); err != nil {
		return err
	}
	publicKey, err := DecodePublicKey(document.Payload.PublicKey)
	if err != nil {
		return err
	}
	if err := core.Verify(document, publicKey); err != nil {
		return err
	}
	return writeJSONAtomic(filepath.Join(s.root, "members", document.Payload.MemberID+".json"), document)
}

func (s *Store) ReadMembers(roomID string) (map[string]VerifiedMember, []string, error) {
	if err := validateRoomID(roomID); err != nil {
		return nil, nil, err
	}
	paths, err := filepath.Glob(filepath.Join(s.root, "members", "*.json"))
	if err != nil {
		return nil, nil, err
	}
	sort.Strings(paths)
	members := make(map[string]VerifiedMember, len(paths))
	warnings := make([]string, 0)
	for _, path := range paths {
		var document core.Signed[core.MemberProfile]
		if err := readJSON(path, &document); err != nil {
			warnings = append(warnings, warning(path, err))
			continue
		}
		profile := document.Payload
		if err := validateMember(profile); err != nil || profile.RoomID != roomID || filepath.Base(path) != profile.MemberID+".json" {
			if err == nil {
				err = errors.New("member document identity mismatch")
			}
			warnings = append(warnings, warning(path, err))
			continue
		}
		publicKey, err := DecodePublicKey(profile.PublicKey)
		if err == nil {
			err = core.Verify(document, publicKey)
		}
		if err != nil {
			warnings = append(warnings, warning(path, err))
			continue
		}
		members[profile.MemberID] = VerifiedMember{Profile: profile, PublicKey: publicKey}
	}
	return members, warnings, nil
}

func (s *Store) WriteSnapshot(document core.Signed[core.Snapshot]) error {
	if err := validateSnapshot(document.Payload); err != nil {
		return err
	}
	return writeJSONAtomic(filepath.Join(s.root, "snapshots", document.Payload.MemberID+".json"), document)
}

func (s *Store) ReadSnapshots(roomID string, members map[string]VerifiedMember) ([]core.Snapshot, []string, error) {
	if err := validateRoomID(roomID); err != nil {
		return nil, nil, err
	}
	paths, err := filepath.Glob(filepath.Join(s.root, "snapshots", "*.json"))
	if err != nil {
		return nil, nil, err
	}
	sort.Strings(paths)
	snapshots := make([]core.Snapshot, 0, len(paths))
	warnings := make([]string, 0)
	for _, path := range paths {
		var document core.Signed[core.Snapshot]
		if err := readJSON(path, &document); err != nil {
			warnings = append(warnings, warning(path, err))
			continue
		}
		snapshot := document.Payload
		member, found := members[snapshot.MemberID]
		if err := validateSnapshot(snapshot); err != nil || !found || snapshot.RoomID != roomID || filepath.Base(path) != snapshot.MemberID+".json" {
			if err == nil {
				err = errors.New("snapshot identity mismatch or unknown member")
			}
			warnings = append(warnings, warning(path, err))
			continue
		}
		if err := core.Verify(document, member.PublicKey); err != nil {
			warnings = append(warnings, warning(path, err))
			continue
		}
		snapshots = append(snapshots, snapshot)
	}
	return snapshots, warnings, nil
}

func (s *Store) WritePolicy(document core.Signed[core.Policy]) error {
	if err := validateRoomID(document.Payload.RoomID); err != nil {
		return err
	}
	return writeJSONAtomic(filepath.Join(s.root, "policy.json"), document)
}

func (s *Store) ReadPolicy(roomID string, ownerPublicKey ed25519.PublicKey) (core.Signed[core.Policy], error) {
	var document core.Signed[core.Policy]
	if err := readJSON(filepath.Join(s.root, "policy.json"), &document); err != nil {
		return document, err
	}
	if document.Payload.RoomID != roomID {
		return document, errors.New("policy room does not match pinned room")
	}
	if err := core.Verify(document, ownerPublicKey); err != nil {
		return document, err
	}
	return document, nil
}

func validateRoomID(roomID string) error {
	if !safeID.MatchString(roomID) {
		return errors.New("invalid room identifier")
	}
	return nil
}

func validateMember(profile core.MemberProfile) error {
	if err := validateRoomID(profile.RoomID); err != nil {
		return err
	}
	if !safeID.MatchString(profile.MemberID) {
		return errors.New("invalid member identifier")
	}
	name := strings.TrimSpace(profile.DisplayName)
	if len(name) == 0 || len([]rune(name)) > 80 {
		return errors.New("display name must contain 1 to 80 characters")
	}
	return nil
}

func validateSnapshot(snapshot core.Snapshot) error {
	if err := validateRoomID(snapshot.RoomID); err != nil {
		return err
	}
	if !safeID.MatchString(snapshot.MemberID) {
		return errors.New("invalid member identifier")
	}
	return nil
}

func warning(path string, err error) string {
	return filepath.Base(path) + ": " + err.Error()
}

func readJSON(path string, target any) error {
	file, err := os.Open(path)
	if err != nil {
		return err
	}
	defer file.Close()
	limited := io.LimitReader(file, maxDocumentBytes+1)
	data, err := io.ReadAll(limited)
	if err != nil {
		return err
	}
	if len(data) > maxDocumentBytes {
		return errors.New("document exceeds size limit")
	}
	decoder := json.NewDecoder(strings.NewReader(string(data)))
	if err := decoder.Decode(target); err != nil {
		return err
	}
	return nil
}

func writeJSONAtomic(path string, value any) error {
	data, err := json.MarshalIndent(value, "", "  ")
	if err != nil {
		return err
	}
	data = append(data, '\n')
	directory := filepath.Dir(path)
	if err := os.MkdirAll(directory, 0o700); err != nil {
		return err
	}
	temporary, err := os.CreateTemp(directory, ".quota-guard-*.tmp")
	if err != nil {
		return err
	}
	temporaryPath := temporary.Name()
	defer os.Remove(temporaryPath)
	if err := temporary.Chmod(0o600); err != nil {
		temporary.Close()
		return err
	}
	if _, err := temporary.Write(data); err != nil {
		temporary.Close()
		return err
	}
	if err := temporary.Sync(); err != nil {
		temporary.Close()
		return err
	}
	if err := temporary.Close(); err != nil {
		return err
	}
	if err := os.Rename(temporaryPath, path); err == nil {
		return nil
	}
	if err := os.Remove(path); err != nil && !errors.Is(err, os.ErrNotExist) {
		return err
	}
	return os.Rename(temporaryPath, path)
}

