package core

import (
	"crypto/ed25519"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"reflect"
)

func Sign[T any](payload T, privateKey ed25519.PrivateKey) (Signed[T], error) {
	if len(privateKey) != ed25519.PrivateKeySize {
		return Signed[T]{}, errors.New("invalid Ed25519 private key")
	}
	message, err := signatureMessage(payload)
	if err != nil {
		return Signed[T]{}, err
	}
	signature := ed25519.Sign(privateKey, message)
	return Signed[T]{Payload: payload, Signature: base64.RawURLEncoding.EncodeToString(signature)}, nil
}

func Verify[T any](document Signed[T], publicKey ed25519.PublicKey) error {
	if len(publicKey) != ed25519.PublicKeySize {
		return errors.New("invalid Ed25519 public key")
	}
	signature, err := base64.RawURLEncoding.DecodeString(document.Signature)
	if err != nil || len(signature) != ed25519.SignatureSize {
		return errors.New("invalid document signature encoding")
	}
	message, err := signatureMessage(document.Payload)
	if err != nil {
		return err
	}
	if !ed25519.Verify(publicKey, message, signature) {
		return errors.New("document signature verification failed")
	}
	return nil
}

func signatureMessage[T any](payload T) ([]byte, error) {
	typeOf := reflect.TypeOf(payload)
	if typeOf == nil {
		return nil, errors.New("cannot sign a nil payload")
	}
	for typeOf.Kind() == reflect.Pointer {
		typeOf = typeOf.Elem()
	}
	body, err := json.Marshal(payload)
	if err != nil {
		return nil, fmt.Errorf("encode signed payload: %w", err)
	}
	prefix := []byte("codex-quota-guard/v1:" + typeOf.PkgPath() + "." + typeOf.Name() + "\n")
	return append(prefix, body...), nil
}

