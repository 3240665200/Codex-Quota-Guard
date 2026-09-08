package config

import (
	"crypto/ed25519"
	"crypto/rand"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strings"
	"time"

	"codex-quota-guard/internal/core"
)

const (
	RoleOwner  = "owner"
	RoleMember = "member"
)

type Config struct {
	SchemaVersion          int    `json:"schemaVersion"`
	Role                   string `json:"role"`
	RoomDirectory          string `json:"roomDirectory"`
	RoomID                 string `json:"roomId"`
	RoomName               string `json:"roomName"`
	OwnerPublicKey         string `json:"ownerPublicKey"`
	OwnerPrivateKey        string `json:"ownerPrivateKey,omitempty"`
	MemberID               string `json:"memberId"`
	DisplayName            string `json:"displayName"`
	MemberPublicKey        string `json:"memberPublicKey"`
	MemberPrivateKey       string `json:"memberPrivateKey"`
	ManagedCodexHome       string `json:"managedCodexHome"`
	ManagedLoginOnly       bool   `json:"managedLoginOnly"`
	EnforcementConsent     bool   `json:"enforcementConsent"`
	RefreshIntervalSeconds int    `json:"refreshIntervalSeconds"`
	StaleAfterSeconds      int    `json:"staleAfterSeconds"`
	ListenAddress          string `json:"listenAddress"`
}

func NewOwner(roomDirectory, dataDirectory, roomName, displayName string, enforcementConsent bool, now time.Time) (Config, core.RoomManifest, error) {
	roomDirectory, dataDirectory, err := validateDirectories(roomDirectory, dataDirectory)
	if err != nil {
		return Config{}, core.RoomManifest{}, err
	}
	if err := validateName("room name", roomName); err != nil {
		return Config{}, core.RoomManifest{}, err
	}
	if err := validateName("display name", displayName); err != nil {
		return Config{}, core.RoomManifest{}, err
	}

	ownerPublic, ownerPrivate, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		return Config{}, core.RoomManifest{}, err
	}
	memberPublic, memberPrivate, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		return Config{}, core.RoomManifest{}, err
	}
	roomID, err := randomID("room_")
	if err != nil {
		return Config{}, core.RoomManifest{}, err
	}
	memberID, err := randomID("member_")
	if err != nil {
		return Config{}, core.RoomManifest{}, err
	}

	config := defaultConfig(roomDirectory, dataDirectory, roomID, strings.TrimSpace(roomName), memberID, strings.TrimSpace(displayName), enforcementConsent)
	config.Role = RoleOwner
	config.OwnerPublicKey = encodeKey(ownerPublic)
	config.OwnerPrivateKey = encodeKey(ownerPrivate)
	config.MemberPublicKey = encodeKey(memberPublic)
	config.MemberPrivateKey = encodeKey(memberPrivate)
	manifest := core.RoomManifest{
		SchemaVersion:  core.SchemaVersion,
		RoomID:         roomID,
		Name:           config.RoomName,
		OwnerMemberID:  memberID,
		OwnerPublicKey: config.OwnerPublicKey,
		CreatedAt:      now.UTC(),
	}
	return config, manifest, nil
}

func NewMember(roomDirectory, dataDirectory string, manifest core.RoomManifest, displayName string, enforcementConsent bool, now time.Time) (Config, error) {
	roomDirectory, dataDirectory, err := validateDirectories(roomDirectory, dataDirectory)
	if err != nil {
		return Config{}, err
	}
	if manifest.SchemaVersion != core.SchemaVersion || strings.TrimSpace(manifest.RoomID) == "" {
		return Config{}, errors.New("unsupported or invalid room manifest")
	}
	if _, err := decodePublicKey(manifest.OwnerPublicKey); err != nil {
		return Config{}, fmt.Errorf("room owner public key: %w", err)
	}
	if err := validateName("display name", displayName); err != nil {
		return Config{}, err
	}
	memberPublic, memberPrivate, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		return Config{}, err
	}
	memberID, err := randomID("member_")
	if err != nil {
		return Config{}, err
	}
	config := defaultConfig(roomDirectory, dataDirectory, manifest.RoomID, manifest.Name, memberID, strings.TrimSpace(displayName), enforcementConsent)
	config.Role = RoleMember
	config.OwnerPublicKey = manifest.OwnerPublicKey
	config.MemberPublicKey = encodeKey(memberPublic)
	config.MemberPrivateKey = encodeKey(memberPrivate)
	_ = now
	return config, nil
}

func defaultConfig(roomDirectory, dataDirectory, roomID, roomName, memberID, displayName string, consent bool) Config {
	return Config{
		SchemaVersion:          core.SchemaVersion,
		RoomDirectory:          roomDirectory,
		RoomID:                 roomID,
		RoomName:               roomName,
		MemberID:               memberID,
		DisplayName:            displayName,
		ManagedCodexHome:       filepath.Join(dataDirectory, "codex-home"),
		ManagedLoginOnly:       true,
		EnforcementConsent:     consent,
		RefreshIntervalSeconds: 60,
		StaleAfterSeconds:      180,
		ListenAddress:          "127.0.0.1:47821",
	}
}

func (c Config) Validate() error {
	if c.SchemaVersion != core.SchemaVersion {
		return errors.New("unsupported config schema version")
	}
	if c.Role != RoleOwner && c.Role != RoleMember {
		return errors.New("role must be owner or member")
	}
	if !filepath.IsAbs(c.RoomDirectory) || !filepath.IsAbs(c.ManagedCodexHome) {
		return errors.New("room directory and managed CODEX_HOME must be absolute")
	}
	if err := validateName("room name", c.RoomName); err != nil {
		return err
	}
	if err := validateName("display name", c.DisplayName); err != nil {
		return err
	}
	if strings.TrimSpace(c.RoomID) == "" || strings.TrimSpace(c.MemberID) == "" {
		return errors.New("room and member identifiers are required")
	}
	ownerPublic, err := decodePublicKey(c.OwnerPublicKey)
	if err != nil {
		return fmt.Errorf("owner public key: %w", err)
	}
	memberPublic, err := decodePublicKey(c.MemberPublicKey)
	if err != nil {
		return fmt.Errorf("member public key: %w", err)
	}
	memberPrivate, err := decodePrivateKey(c.MemberPrivateKey)
	if err != nil {
		return fmt.Errorf("member private key: %w", err)
	}
	if !memberPublic.Equal(memberPrivate.Public()) {
		return errors.New("member key pair does not match")
	}
	if c.Role == RoleOwner {
		ownerPrivate, err := decodePrivateKey(c.OwnerPrivateKey)
		if err != nil {
			return fmt.Errorf("owner private key: %w", err)
		}
		if !ownerPublic.Equal(ownerPrivate.Public()) {
			return errors.New("owner key pair does not match")
		}
	} else if c.OwnerPrivateKey != "" {
		return errors.New("member config must not contain the owner private key")
	}
	if !c.ManagedLoginOnly {
		return errors.New("managedLoginOnly must remain enabled")
	}
	if c.RefreshIntervalSeconds < 15 || c.RefreshIntervalSeconds > 3600 {
		return errors.New("refresh interval must be between 15 and 3600 seconds")
	}
	if c.StaleAfterSeconds < c.RefreshIntervalSeconds || c.StaleAfterSeconds > 86400 {
		return errors.New("stale-after interval must be at least the refresh interval and no more than one day")
	}
	if !strings.HasPrefix(c.ListenAddress, "127.0.0.1:") && !strings.HasPrefix(c.ListenAddress, "localhost:") {
		return errors.New("dashboard must listen on localhost")
	}
	return nil
}

func (c Config) SignedMemberProfile(now time.Time) (core.Signed[core.MemberProfile], error) {
	privateKey, err := c.MemberPrivateKeyBytes()
	if err != nil {
		return core.Signed[core.MemberProfile]{}, err
	}
	profile := core.MemberProfile{
		SchemaVersion:      core.SchemaVersion,
		RoomID:             c.RoomID,
		MemberID:           c.MemberID,
		DisplayName:        c.DisplayName,
		PublicKey:          c.MemberPublicKey,
		JoinedAt:           now.UTC(),
		ManagedLogin:       c.ManagedLoginOnly,
		EnforcementConsent: c.EnforcementConsent,
	}
	return core.Sign(profile, privateKey)
}

func (c Config) OwnerPublicKeyBytes() (ed25519.PublicKey, error) {
	return decodePublicKey(c.OwnerPublicKey)
}

func (c Config) OwnerPrivateKeyBytes() (ed25519.PrivateKey, error) {
	if c.Role != RoleOwner {
		return nil, errors.New("owner private key is not available to members")
	}
	return decodePrivateKey(c.OwnerPrivateKey)
}

func (c Config) MemberPublicKeyBytes() (ed25519.PublicKey, error) {
	return decodePublicKey(c.MemberPublicKey)
}

func (c Config) MemberPrivateKeyBytes() (ed25519.PrivateKey, error) {
	return decodePrivateKey(c.MemberPrivateKey)
}

func DefaultPath() (string, error) {
	directory, err := os.UserConfigDir()
	if err != nil {
		return "", err
	}
	return filepath.Join(directory, "CodexQuotaGuard", "config.json"), nil
}

func Save(path string, config Config) error {
	if err := config.Validate(); err != nil {
		return err
	}
	data, err := json.MarshalIndent(config, "", "  ")
	if err != nil {
		return err
	}
	data = append(data, '\n')
	directory := filepath.Dir(path)
	if err := os.MkdirAll(directory, 0o700); err != nil {
		return err
	}
	temporary, err := os.CreateTemp(directory, ".config-*.tmp")
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

func Load(path string) (Config, error) {
	file, err := os.Open(path)
	if err != nil {
		return Config{}, err
	}
	defer file.Close()
	data, err := io.ReadAll(io.LimitReader(file, 1<<20))
	if err != nil {
		return Config{}, err
	}
	var config Config
	if err := json.Unmarshal(data, &config); err != nil {
		return Config{}, fmt.Errorf("decode config: %w", err)
	}
	if err := config.Validate(); err != nil {
		return Config{}, err
	}
	return config, nil
}

func validateDirectories(roomDirectory, dataDirectory string) (string, string, error) {
	roomDirectory = filepath.Clean(roomDirectory)
	dataDirectory = filepath.Clean(dataDirectory)
	if !filepath.IsAbs(roomDirectory) || !filepath.IsAbs(dataDirectory) {
		return "", "", errors.New("room and local data directories must be absolute")
	}
	return roomDirectory, dataDirectory, nil
}

func validateName(label, value string) error {
	length := len([]rune(strings.TrimSpace(value)))
	if length < 1 || length > 80 {
		return fmt.Errorf("%s must contain 1 to 80 characters", label)
	}
	return nil
}

func randomID(prefix string) (string, error) {
	buffer := make([]byte, 16)
	if _, err := rand.Read(buffer); err != nil {
		return "", err
	}
	return prefix + base64.RawURLEncoding.EncodeToString(buffer), nil
}

func encodeKey(key []byte) string {
	return base64.RawURLEncoding.EncodeToString(key)
}

func decodePublicKey(value string) (ed25519.PublicKey, error) {
	data, err := base64.RawURLEncoding.DecodeString(value)
	if err != nil || len(data) != ed25519.PublicKeySize {
		return nil, errors.New("invalid Ed25519 public key")
	}
	return ed25519.PublicKey(data), nil
}

func decodePrivateKey(value string) (ed25519.PrivateKey, error) {
	data, err := base64.RawURLEncoding.DecodeString(value)
	if err != nil || len(data) != ed25519.PrivateKeySize {
		return nil, errors.New("invalid Ed25519 private key")
	}
	return ed25519.PrivateKey(data), nil
}

