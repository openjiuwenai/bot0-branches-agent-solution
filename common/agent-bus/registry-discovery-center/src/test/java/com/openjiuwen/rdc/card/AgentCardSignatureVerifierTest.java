/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.rdc.card;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openjiuwen.rdc.security.RdcCardFetchOptions;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;
import java.util.Map;

/**
 * AgentCardSignatureVerifierTest coverage.
 *
 * @since 0.1.0 (2026)
 */
class AgentCardSignatureVerifierTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void disabled_verifier_accepts_unsigned_card() throws Exception {
        AgentCardSignatureVerifier verifier = AgentCardSignatureVerifier.disabled();

        assertThat(verifier.verify(unsignedCardJson()).ok()).isTrue();
    }

    @Test
    void enabled_verifier_rejects_missing_signatures() throws Exception {
        RdcCardFetchOptions props = new RdcCardFetchOptions();
        props.setVerifySignatures(true);
        props.setSignerPemsByKid(Map.of("test-key", publicKeyPem(testKeyPair())));

        AgentCardSignatureVerifier verifier = AgentCardSignatureVerifier.from(props);

        assertThat(verifier.verify(unsignedCardJson()).ok()).isFalse();
    }

    @Test
    void enabled_verifier_accepts_valid_signature() throws Exception {
        KeyPair keyPair = testKeyPair();
        RdcCardFetchOptions props = new RdcCardFetchOptions();
        props.setVerifySignatures(true);
        props.setSignerPemsByKid(Map.of("test-key", publicKeyPem(keyPair)));

        String signedCard = signedCard(keyPair);
        AgentCardSignatureVerifier verifier = AgentCardSignatureVerifier.from(props);

        assertThat(verifier.verify(signedCard).ok()).isTrue();
    }

    @Test
    void enabled_verifier_rejects_tampered_payload() throws Exception {
        KeyPair keyPair = testKeyPair();
        RdcCardFetchOptions props = new RdcCardFetchOptions();
        props.setVerifySignatures(true);
        props.setSignerPemsByKid(Map.of("test-key", publicKeyPem(keyPair)));

        String signedCard = signedCard(keyPair).replace("demo", "evil");
        AgentCardSignatureVerifier verifier = AgentCardSignatureVerifier.from(props);

        assertThat(verifier.verify(signedCard).ok()).isFalse();
    }

    @Test
    void enabled_verifier_rejects_unsupported_algorithm_without_throwing() throws Exception {
        KeyPair keyPair = testKeyPair();
        RdcCardFetchOptions props = new RdcCardFetchOptions();
        props.setVerifySignatures(true);
        props.setSignerPemsByKid(Map.of("test-key", publicKeyPem(keyPair)));

        if (!(MAPPER.readTree(unsignedCardJson()) instanceof ObjectNode root)) {
            throw new IllegalStateException("expected object node");
        }
        ArrayNode signatures = MAPPER.createArrayNode();
        ObjectNode signatureNode = MAPPER.createObjectNode();
        signatureNode.put("protectedHeader", Base64.getUrlEncoder().withoutPadding().encodeToString(
                "{\"alg\":\"ES256\",\"kid\":\"test-key\"}".getBytes(StandardCharsets.UTF_8)));
        signatureNode.put("signature", "dGVzdA");
        signatures.add(signatureNode);
        root.set("signatures", signatures);

        AgentCardSignatureVerifier verifier = AgentCardSignatureVerifier.from(props);
        assertThat(verifier.verify(MAPPER.writeValueAsString(root)).ok()).isFalse();
    }

    private static KeyPair testKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    private static String publicKeyPem(KeyPair keyPair) {
        String encoded = Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());
        return "-----BEGIN PUBLIC KEY-----\n" + encoded + "\n-----END PUBLIC KEY-----";
    }

    private static String signedCard(KeyPair keyPair) throws Exception {
        if (!(MAPPER.readTree(unsignedCardJson()) instanceof ObjectNode root)) {
            throw new IllegalStateException("expected object node");
        }
        String unsignedPayload = MAPPER.writeValueAsString(root);

        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(keyPair.getPrivate());
        signer.update(unsignedPayload.getBytes(StandardCharsets.UTF_8));
        String signatureValue = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(signer.sign());
        String protectedHeader = Base64.getUrlEncoder().withoutPadding().encodeToString(
                "{\"alg\":\"RS256\",\"kid\":\"test-key\"}".getBytes(StandardCharsets.UTF_8));

        ArrayNode signatures = MAPPER.createArrayNode();
        ObjectNode signatureNode = MAPPER.createObjectNode();
        signatureNode.put("protectedHeader", protectedHeader);
        signatureNode.put("signature", signatureValue);
        signatureNode.set("header", MAPPER.createObjectNode());
        signatures.add(signatureNode);
        root.set("signatures", signatures);
        return MAPPER.writeValueAsString(root);
    }

    private static String unsignedCardJson() {
        return """
                {
                  "name": "demo",
                  "description": "demo",
                  "version": "1.0.0",
                  "defaultInputModes": ["text"],
                  "defaultOutputModes": ["text"],
                  "capabilities": {"streaming": true},
                  "skills": [],
                  "supportedInterfaces": [{"protocol": "jsonrpc", "url": "/a2a"}]
                }
                """;
    }
}
