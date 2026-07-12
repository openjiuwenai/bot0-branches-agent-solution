/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.ext.intent.adapter.a2a;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.a2aproject.sdk.spec.APIKeySecurityScheme;
import org.a2aproject.sdk.spec.AgentCapabilities;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.AgentCardSignature;
import org.a2aproject.sdk.spec.AgentExtension;
import org.a2aproject.sdk.spec.AgentInterface;
import org.a2aproject.sdk.spec.AgentProvider;
import org.a2aproject.sdk.spec.AgentSkill;
import org.a2aproject.sdk.spec.AuthorizationCodeOAuthFlow;
import org.a2aproject.sdk.spec.ClientCredentialsOAuthFlow;
import org.a2aproject.sdk.spec.DeviceCodeOAuthFlow;
import org.a2aproject.sdk.spec.HTTPAuthSecurityScheme;
import org.a2aproject.sdk.spec.MutualTLSSecurityScheme;
import org.a2aproject.sdk.spec.OAuth2SecurityScheme;
import org.a2aproject.sdk.spec.OAuthFlows;
import org.a2aproject.sdk.spec.OpenIdConnectSecurityScheme;
import org.a2aproject.sdk.spec.SecurityRequirement;
import org.a2aproject.sdk.spec.SecurityScheme;
import org.junit.jupiter.api.Test;

class A2AAgentCardSnapshotsTest {
    @Test
    void rebuildsOfficialTypesAndDeeplyIsolatesEveryNestedCollection() {
        List<Object> nestedValues = new ArrayList<>(List.of("before"));
        Map<String, Object> extensionParams = new LinkedHashMap<>();
        extensionParams.put("nested", nestedValues);
        List<AgentExtension> extensions = new ArrayList<>(
                List.of(new AgentExtension("extension", extensionParams, true, "urn:required")));
        List<String> tags = new ArrayList<>(List.of("orders"));
        List<String> examples = new ArrayList<>(List.of("track order"));
        List<String> skillModes = new ArrayList<>(List.of("text/plain"));
        List<String> scopes = new ArrayList<>(List.of("read"));
        Map<String, List<String>> schemes = new LinkedHashMap<>();
        schemes.put("oauth", scopes);
        List<SecurityRequirement> requirements = new ArrayList<>(List.of(new SecurityRequirement(schemes)));
        AgentSkill skill = new AgentSkill("track", "Track", "Track an order", tags, examples, skillModes, null,
                requirements);
        List<AgentSkill> skills = new ArrayList<>(List.of(skill));
        Map<String, String> oauthScopes = new LinkedHashMap<>(Map.of("read", "Read orders"));
        OAuthFlows flows = new OAuthFlows(new AuthorizationCodeOAuthFlow("https://auth.example/authorize", null,
                oauthScopes, "https://auth.example/token", true), null, null);
        Map<String, SecurityScheme> securitySchemes = new LinkedHashMap<>();
        securitySchemes.put("oauth", new OAuth2SecurityScheme(flows, "OAuth", null));
        List<String> signatureKeys = new ArrayList<>(List.of("key-1"));
        Map<String, Object> signatureHeader = new LinkedHashMap<>();
        signatureHeader.put("keys", signatureKeys);
        List<AgentCardSignature> signatures = new ArrayList<>(
                List.of(new AgentCardSignature(signatureHeader, "protected-value", "signature-value")));
        List<String> defaultInputModes = new ArrayList<>(List.of("text/plain"));
        List<String> defaultOutputModes = new ArrayList<>(List.of("application/json"));
        List<AgentInterface> interfaces = new ArrayList<>(
                List.of(new AgentInterface("JSONRPC", "https://agent.example/a2a", "tenant", "1.0")));
        AgentCard source = new AgentCard("Order Agent", "Order operations",
                new AgentProvider("Example", "https://provider.example"), "1.0.0", "https://docs.example",
                new AgentCapabilities(true, false, false, extensions), defaultInputModes, defaultOutputModes, skills,
                securitySchemes, requirements, "https://icon.example", interfaces, signatures, "https://legacy.example",
                "JSONRPC", new ArrayList<>());

        AgentCard snapshot = A2AAgentCardSnapshots.snapshot(source);
        nestedValues.set(0, "after");
        tags.set(0, "changed");
        examples.clear();
        skillModes.clear();
        scopes.set(0, "write");
        oauthScopes.put("write", "Write orders");
        signatureKeys.set(0, "key-2");
        extensions.clear();
        skills.clear();
        requirements.clear();
        defaultInputModes.clear();
        defaultOutputModes.clear();
        interfaces.clear();
        signatures.clear();
        securitySchemes.clear();

        assertThat(snapshot).isInstanceOf(AgentCard.class).isNotSameAs(source);
        assertThat(snapshot.skills()).singleElement().satisfies(copiedSkill -> {
            assertThat(copiedSkill).isInstanceOf(AgentSkill.class).isNotSameAs(skill);
            assertThat(copiedSkill.tags()).containsExactly("orders");
            assertThat(copiedSkill.examples()).containsExactly("track order");
            assertThat(copiedSkill.inputModes()).containsExactly("text/plain");
            assertThat(copiedSkill.securityRequirements().get(0).schemes().get("oauth")).containsExactly("read");
        });
        assertThat(snapshot.capabilities().extensions().get(0).params().get("nested")).isEqualTo(List.of("before"));
        OAuth2SecurityScheme oauth = (OAuth2SecurityScheme) snapshot.securitySchemes().get("oauth");
        assertThat(oauth.flows().authorizationCode().scopes()).containsOnlyKeys("read");
        assertThat(snapshot.signatures().get(0).header().get("keys")).isEqualTo(List.of("key-1"));
        assertThatThrownBy(() -> snapshot.defaultInputModes().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> ((List<?>) snapshot.capabilities().extensions().get(0).params().get("nested")).clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsCyclicAndNonJsonValuesInOpenMaps() {
        Map<String, Object> cyclic = new LinkedHashMap<>();
        cyclic.put("self", cyclic);
        AgentCard cyclicCard = cardWithParams(cyclic);
        AgentCard nonJsonCard = cardWithParams(Map.of("value", new StringBuilder("mutable")));

        assertThatThrownBy(() -> A2AAgentCardSnapshots.snapshot(cyclicCard))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("cyclic");
        assertThatThrownBy(() -> A2AAgentCardSnapshots.snapshot(nonJsonCard))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("JSON-compatible");
    }

    @Test
    void rebuildsEveryOfficialSecuritySchemeSubtype() {
        OAuthFlows flows = new OAuthFlows(
                new AuthorizationCodeOAuthFlow("https://auth.example/authorize", "https://auth.example/refresh",
                        Map.of("read", "Read"), "https://auth.example/token", true),
                new ClientCredentialsOAuthFlow("https://auth.example/refresh", Map.of("write", "Write"),
                        "https://auth.example/token"),
                new DeviceCodeOAuthFlow("https://auth.example/device", "https://auth.example/token",
                        "https://auth.example/refresh", Map.of("device", "Device")));
        Map<String, SecurityScheme> schemes = Map.of("api",
                new APIKeySecurityScheme(APIKeySecurityScheme.Location.HEADER, "X-API-Key", "API key"), "http",
                new HTTPAuthSecurityScheme("JWT", "bearer", "Bearer"), "oauth",
                new OAuth2SecurityScheme(flows, "OAuth", "https://auth.example/metadata"), "oidc",
                new OpenIdConnectSecurityScheme("https://auth.example/openid", "OpenID"), "mtls",
                new MutualTLSSecurityScheme("Mutual TLS"));
        AgentCard source = AgentCard.builder(cardWithParams(Map.of())).securitySchemes(schemes).build();

        AgentCard snapshot = A2AAgentCardSnapshots.snapshot(source);

        assertThat(snapshot.securitySchemes()).isEqualTo(schemes);
        schemes.forEach((name, scheme) -> assertThat(snapshot.securitySchemes().get(name)).isNotSameAs(scheme));
    }

    private static AgentCard cardWithParams(Map<String, Object> params) {
        return AgentCard.builder().name("Agent").description("Description").version("1")
                .capabilities(new AgentCapabilities(false, false, false,
                        List.of(new AgentExtension(null, params, false, "urn:test"))))
                .defaultInputModes(List.of("text/plain")).defaultOutputModes(List.of("text/plain"))
                .skills(List.of(new AgentSkill("skill", "Skill", "Description", List.of(), null, null, null, null)))
                .supportedInterfaces(List.of(new AgentInterface("JSONRPC", "https://agent.example", null, "1.0")))
                .build();
    }
}
