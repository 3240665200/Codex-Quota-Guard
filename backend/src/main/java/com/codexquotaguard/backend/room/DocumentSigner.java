package com.codexquotaguard.backend.room;

import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

public final class DocumentSigner {

    private static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();

    private DocumentSigner() {
    }

    public static <T> SignedDocument<T> sign(T payload, PrivateKey privateKey) {
        if (payload == null || privateKey == null || !isEd25519Key(privateKey.getAlgorithm())) {
            throw new DocumentSignatureException("An Ed25519 private key and payload are required.");
        }
        try {
            var signer = Signature.getInstance("Ed25519");
            signer.initSign(privateKey);
            signer.update(canonicalBytes(payload));
            return new SignedDocument<>(payload, Base64.getUrlEncoder().withoutPadding().encodeToString(signer.sign()));
        } catch (GeneralSecurityException exception) {
            throw new DocumentSignatureException("Could not sign document.", exception);
        }
    }

    public static boolean verify(Object payload, String encodedSignature, PublicKey publicKey) {
        if (payload == null || publicKey == null || !isEd25519Key(publicKey.getAlgorithm())) {
            throw new DocumentSignatureException("An Ed25519 public key and payload are required.");
        }
        byte[] signature;
        try {
            signature = Base64.getUrlDecoder().decode(encodedSignature == null ? "" : encodedSignature);
        } catch (IllegalArgumentException exception) {
            throw new DocumentSignatureException("Document signature encoding is invalid.", exception);
        }
        if (signature.length != 64) {
            throw new DocumentSignatureException("Document signature length is invalid.");
        }
        try {
            var verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(publicKey);
            verifier.update(canonicalBytes(payload));
            return verifier.verify(signature);
        } catch (GeneralSecurityException exception) {
            throw new DocumentSignatureException("Could not verify document.", exception);
        }
    }

    public static String encodePublicKey(PublicKey publicKey) {
        if (publicKey == null || !isEd25519Key(publicKey.getAlgorithm())) {
            throw new DocumentSignatureException("An Ed25519 public key is required.");
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(publicKey.getEncoded());
    }

    public static PublicKey decodePublicKey(String encodedKey) {
        try {
            var bytes = Base64.getUrlDecoder().decode(encodedKey == null ? "" : encodedKey);
            return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(bytes));
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            throw new DocumentSignatureException("Public key encoding is invalid.", exception);
        }
    }

    public static String json(Object payload) {
        try {
            return MAPPER.writeValueAsString(payload);
        } catch (RuntimeException exception) {
            throw new DocumentSignatureException("Could not encode document.", exception);
        }
    }

    private static byte[] canonicalBytes(Object payload) {
        return json(payload).getBytes(StandardCharsets.UTF_8);
    }

    private static boolean isEd25519Key(String algorithm) {
        return "Ed25519".equalsIgnoreCase(algorithm) || "EdDSA".equalsIgnoreCase(algorithm);
    }
}
