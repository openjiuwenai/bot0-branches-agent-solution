/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.ext.intent.adapter.a2a;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
import org.a2aproject.sdk.spec.Legacy_0_3_AgentInterface;
import org.a2aproject.sdk.spec.MutualTLSSecurityScheme;
import org.a2aproject.sdk.spec.OAuth2SecurityScheme;
import org.a2aproject.sdk.spec.OAuthFlows;
import org.a2aproject.sdk.spec.OpenIdConnectSecurityScheme;
import org.a2aproject.sdk.spec.SecurityRequirement;
import org.a2aproject.sdk.spec.SecurityScheme;

/** Deep defensive snapshot utilities that always return official A2A SDK types. */
public final class A2AAgentCardSnapshots {
    private A2AAgentCardSnapshots() {
    }

    public static AgentCard snapshot(AgentCard card) {
        Objects.requireNonNull(card, "card must not be null");
        return new AgentCard(card.name(), card.description(), copyProvider(card.provider()), card.version(),
                card.documentationUrl(), copyCapabilities(card.capabilities()), copyStrings(card.defaultInputModes()),
                copyStrings(card.defaultOutputModes()), copySkills(card.skills()),
                copySecuritySchemes(card.securitySchemes()), copyRequirements(card.securityRequirements()),
                card.iconUrl(), copyInterfaces(card.supportedInterfaces()), copySignatures(card.signatures()),
                card.url(), card.preferredTransport(), copyLegacyInterfaces(card.additionalInterfaces()));
    }

    private static AgentProvider copyProvider(AgentProvider provider) {
        return provider == null ? null : new AgentProvider(provider.organization(), provider.url());
    }

    private static AgentCapabilities copyCapabilities(AgentCapabilities capabilities) {
        Objects.requireNonNull(capabilities, "card capabilities must not be null");
        List<AgentExtension> extensions = null;
        if (capabilities.extensions() != null) {
            extensions = capabilities.extensions().stream().map(extension -> new AgentExtension(extension.description(),
                    copyJsonMap(extension.params()), extension.required(), extension.uri())).toList();
        }
        return new AgentCapabilities(capabilities.streaming(), capabilities.pushNotifications(),
                capabilities.extendedAgentCard(), extensions == null ? null : List.copyOf(extensions));
    }

    private static List<AgentSkill> copySkills(List<AgentSkill> skills) {
        Objects.requireNonNull(skills, "card skills must not be null");
        return skills.stream()
                .map(skill -> new AgentSkill(skill.id(), skill.name(), skill.description(), copyStrings(skill.tags()),
                        copyStrings(skill.examples()), copyStrings(skill.inputModes()),
                        copyStrings(skill.outputModes()), copyRequirements(skill.securityRequirements())))
                .toList();
    }

    private static Map<String, SecurityScheme> copySecuritySchemes(Map<String, SecurityScheme> schemes) {
        if (schemes == null) {
            return null;
        }
        Map<String, SecurityScheme> copied = new LinkedHashMap<>();
        schemes.forEach((name, scheme) -> copied.put(name, copySecurityScheme(scheme)));
        return Collections.unmodifiableMap(copied);
    }

    private static SecurityScheme copySecurityScheme(SecurityScheme scheme) {
        Objects.requireNonNull(scheme, "security scheme must not be null");
        if (scheme instanceof APIKeySecurityScheme apiKey) {
            return new APIKeySecurityScheme(apiKey.location(), apiKey.name(), apiKey.description());
        }
        if (scheme instanceof HTTPAuthSecurityScheme http) {
            return new HTTPAuthSecurityScheme(http.bearerFormat(), http.scheme(), http.description());
        }
        if (scheme instanceof OAuth2SecurityScheme oauth) {
            return new OAuth2SecurityScheme(copyOAuthFlows(oauth.flows()), oauth.description(),
                    oauth.oauth2MetadataUrl());
        }
        if (scheme instanceof OpenIdConnectSecurityScheme openId) {
            return new OpenIdConnectSecurityScheme(openId.openIdConnectUrl(), openId.description());
        }
        if (scheme instanceof MutualTLSSecurityScheme mutualTls) {
            return new MutualTLSSecurityScheme(mutualTls.description());
        }
        throw new IllegalArgumentException("unsupported A2A security scheme: " + scheme.getClass().getName());
    }

    private static OAuthFlows copyOAuthFlows(OAuthFlows flows) {
        Objects.requireNonNull(flows, "OAuth flows must not be null");
        AuthorizationCodeOAuthFlow authorization = flows.authorizationCode();
        ClientCredentialsOAuthFlow credentials = flows.clientCredentials();
        DeviceCodeOAuthFlow device = flows.deviceCode();
        return new OAuthFlows(authorization == null
                ? null
                : new AuthorizationCodeOAuthFlow(authorization.authorizationUrl(), authorization.refreshUrl(),
                        copyStringMap(authorization.scopes()), authorization.tokenUrl(), authorization.pkceRequired()),
                credentials == null
                        ? null
                        : new ClientCredentialsOAuthFlow(credentials.refreshUrl(), copyStringMap(credentials.scopes()),
                                credentials.tokenUrl()),
                device == null
                        ? null
                        : new DeviceCodeOAuthFlow(device.deviceAuthorizationUrl(), device.tokenUrl(),
                                device.refreshUrl(), copyStringMap(device.scopes())));
    }

    private static List<SecurityRequirement> copyRequirements(List<SecurityRequirement> requirements) {
        if (requirements == null) {
            return null;
        }
        return requirements.stream().map(requirement -> {
            Map<String, List<String>> schemes = new LinkedHashMap<>();
            requirement.schemes().forEach((name, scopes) -> schemes.put(name, copyStrings(scopes)));
            return new SecurityRequirement(Collections.unmodifiableMap(schemes));
        }).toList();
    }

    private static List<AgentInterface> copyInterfaces(List<AgentInterface> interfaces) {
        Objects.requireNonNull(interfaces, "supported interfaces must not be null");
        return interfaces.stream().map(value -> new AgentInterface(value.protocolBinding(), value.url(), value.tenant(),
                value.protocolVersion())).toList();
    }

    private static List<AgentCardSignature> copySignatures(List<AgentCardSignature> signatures) {
        if (signatures == null) {
            return null;
        }
        return signatures.stream().map(value -> new AgentCardSignature(copyJsonMap(value.header()),
                value.protectedHeader(), value.signature())).toList();
    }

    private static List<Legacy_0_3_AgentInterface> copyLegacyInterfaces(List<Legacy_0_3_AgentInterface> interfaces) {
        if (interfaces == null) {
            return null;
        }
        return interfaces.stream().map(value -> new Legacy_0_3_AgentInterface(value.transport(), value.url())).toList();
    }

    private static List<String> copyStrings(List<String> values) {
        return values == null ? null : List.copyOf(values);
    }

    private static Map<String, String> copyStringMap(Map<String, String> values) {
        return values == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    private static Map<String, Object> copyJsonMap(Map<String, Object> values) {
        if (values == null) {
            return null;
        }
        Object copied = copyJsonValue(values, new IdentityHashMap<>());
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) copied;
        return result;
    }

    private static Object copyJsonValue(Object value, IdentityHashMap<Object, Boolean> activeContainers) {
        if (value == null || value instanceof String || value instanceof Boolean || value instanceof Byte
                || value instanceof Short || value instanceof Integer || value instanceof Long
                || value instanceof BigInteger || value instanceof BigDecimal) {
            return value;
        }
        if (value instanceof Float number) {
            if (!Float.isFinite(number)) {
                throw new IllegalArgumentException("JSON-compatible numbers must be finite");
            }
            return number;
        }
        if (value instanceof Double number) {
            if (!Double.isFinite(number)) {
                throw new IllegalArgumentException("JSON-compatible numbers must be finite");
            }
            return number;
        }
        if (value instanceof List<?> list) {
            enterContainer(value, activeContainers);
            try {
                List<Object> copied = new ArrayList<>(list.size());
                list.forEach(item -> copied.add(copyJsonValue(item, activeContainers)));
                return Collections.unmodifiableList(copied);
            } finally {
                activeContainers.remove(value);
            }
        }
        if (value instanceof Map<?, ?> map) {
            enterContainer(value, activeContainers);
            try {
                Map<String, Object> copied = new LinkedHashMap<>();
                map.forEach((key, item) -> {
                    if (!(key instanceof String stringKey)) {
                        throw new IllegalArgumentException("JSON-compatible maps require string keys");
                    }
                    copied.put(stringKey, copyJsonValue(item, activeContainers));
                });
                return Collections.unmodifiableMap(copied);
            } finally {
                activeContainers.remove(value);
            }
        }
        throw new IllegalArgumentException("value is not JSON-compatible: " + value.getClass().getName());
    }

    private static void enterContainer(Object value, IdentityHashMap<Object, Boolean> activeContainers) {
        if (activeContainers.put(value, Boolean.TRUE) != null) {
            throw new IllegalArgumentException("cyclic JSON-compatible value is not allowed");
        }
    }
}
