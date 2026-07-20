/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.rdc.card;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openjiuwen.rdc.security.RdcCardFetchOptions;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.Signature;
import java.security.SignatureException;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Optional Agent Card JWS-style signature verification (0711 {@code AGENT_CARD_SIGNATURE_INVALID}).
 *
 * @since 0.1.0 (2026)
 */
public final class AgentCardSignatureVerifier {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final boolean enabled;
    private final Map<String, PublicKey> trustedKeysByKid;

    private AgentCardSignatureVerifier(boolean enabled, Map<String, PublicKey> trustedKeysByKid) {
        this.enabled = enabled;
        this.trustedKeysByKid = Map.copyOf(trustedKeysByKid);
    }

    /**
     * disabled.
     *
     * @return result
     * @since 0.1.0
     */
    public static AgentCardSignatureVerifier disabled() {
        return new AgentCardSignatureVerifier(false, Map.of());
    }
    /**
     * from.
     *
     * @param options options
     * @return result
     * @since 0.1.0
     */
    public static AgentCardSignatureVerifier from(RdcCardFetchOptions options) {
        Objects.requireNonNull(options, "options");
        if (!options.isVerifySignatures()) {
            return disabled();
        }
        Map<String, PublicKey> keys = new HashMap<>();
        for (Map.Entry<String, String> entry : options.getSignerPemsByKid().entrySet()) {
            keys.put(entry.getKey(), parsePublicKeyPem(entry.getValue()));
        }
        return new AgentCardSignatureVerifier(true, keys);
    }

    /**
     * verify.
     *
     * @param cardJson cardJson
     * @return result
     * @since 0.1.0
     */
    public VerificationResult verify(String cardJson) {
        if (!enabled) {
            return VerificationResult.success();
        }
        if (cardJson == null || cardJson.isBlank()) {
            return VerificationResult.invalid("empty card body");
        }
        if (trustedKeysByKid.isEmpty()) {
            return VerificationResult.invalid("signature verification enabled but no trusted signer keys configured");
        }
        try {
            JsonNode root = MAPPER.readTree(cardJson);
            JsonNode signatures = root.get("signatures");
            if (signatures == null || !signatures.isArray() || signatures.isEmpty()) {
                return VerificationResult.invalid("signatures required when verification is enabled");
            }
            String unsignedPayload = unsignedPayload(root);
            byte[] payloadBytes = unsignedPayload.getBytes(StandardCharsets.UTF_8);
            for (JsonNode signatureNode : signatures) {
                if (verifyOne(signatureNode, payloadBytes)) {
                    return VerificationResult.success();
                }
            }
            return VerificationResult.invalid("no trusted signature matched");
        } catch (JsonProcessingException | InvalidKeyException | NoSuchAlgorithmException | SignatureException ex) {
            return VerificationResult.invalid(ex.getMessage());
        }
    }

    private boolean verifyOne(JsonNode signatureNode, byte[] payloadBytes)
            throws JsonProcessingException, InvalidKeyException, NoSuchAlgorithmException, SignatureException {
        Optional<String> protectedHeader = textOrNull(signatureNode, "protectedHeader");
        Optional<String> signatureValue = textOrNull(signatureNode, "signature");
        if (protectedHeader.isEmpty() || signatureValue.isEmpty()) {
            return false;
        }
        JsonNode header = MAPPER.readTree(base64UrlDecodeToString(protectedHeader.get()));
        Optional<String> alg = textOrNull(header, "alg");
        Optional<String> kid = textOrNull(header, "kid");
        if (alg.isEmpty() || kid.isEmpty()) {
            return false;
        }
        PublicKey publicKey = trustedKeysByKid.get(kid.get());
        if (publicKey == null) {
            return false;
        }
        Signature verifier = Signature.getInstance(mapAlgorithm(alg.get()));
        verifier.initVerify(publicKey);
        verifier.update(payloadBytes);
        return verifier.verify(base64UrlDecode(signatureValue.get()));
    }

    private static String unsignedPayload(JsonNode root) throws JsonProcessingException {
        ObjectNode copy = root.deepCopy();
        copy.remove("signatures");
        return MAPPER.writeValueAsString(copy);
    }

    private static String mapAlgorithm(String alg) {
        return switch (alg) {
            case "RS256" -> "SHA256withRSA";
            case "RS384" -> "SHA384withRSA";
            case "RS512" -> "SHA512withRSA";
            default -> throw new IllegalArgumentException("unsupported signature algorithm: " + alg);
        };
    }

    private static PublicKey parsePublicKeyPem(String pem) {
        try {
            String normalized = pem
                    .replace("-----BEGIN PUBLIC KEY-----", "")
                    .replace("-----END PUBLIC KEY-----", "")
                    .replaceAll("\\s", "");
            byte[] decoded = Base64.getDecoder().decode(normalized);
            KeyFactory keyFactory = KeyFactory.getInstance("RSA");
            return keyFactory.generatePublic(new X509EncodedKeySpec(decoded));
        } catch (NoSuchAlgorithmException | InvalidKeySpecException ex) {
            throw new IllegalStateException("failed to parse trusted signer public key", ex);
        }
    }

    private static Optional<String> textOrNull(JsonNode node, String field) {
        JsonNode child = node.get(field);
        return child != null && child.isTextual() ? Optional.of(child.asText()) : Optional.empty();
    }

    private static byte[] base64UrlDecode(String value) {
        return Base64.getUrlDecoder().decode(value);
    }
    private static String base64UrlDecodeToString(String value) {
        return new String(base64UrlDecode(value), StandardCharsets.UTF_8);
    }
    /**
     * VerificationResult.
     *
     * @param ok ok
     * @param message message
     * @return result
     * @since 0.1.0
     */
    public record VerificationResult(boolean ok, String message) {
        static VerificationResult success() {
            return new VerificationResult(true, null);
        }
        static VerificationResult invalid(String message) {
            return new VerificationResult(false, message);
        }
    }
}
