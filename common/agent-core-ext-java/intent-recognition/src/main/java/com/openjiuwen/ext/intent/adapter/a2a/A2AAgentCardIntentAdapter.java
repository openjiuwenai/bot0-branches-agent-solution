/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.ext.intent.adapter.a2a;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openjiuwen.ext.intent.api.IntentCandidate;
import com.openjiuwen.ext.intent.api.IntentCandidateLimits;
import com.openjiuwen.ext.intent.api.IntentTargetAdapter;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.AgentExtension;
import org.a2aproject.sdk.spec.AgentInterface;
import org.a2aproject.sdk.spec.AgentSkill;
import org.a2aproject.sdk.spec.SecurityRequirement;

/** Official A2A AgentCard target adapter. */
public final class A2AAgentCardIntentAdapter implements IntentTargetAdapter<AgentCard> {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Comparator<String> CODE_POINT_ORDER = A2AAgentCardIntentAdapter::compareCodePoints;

    private final A2AEligibilityPolicy policy;

    public A2AAgentCardIntentAdapter(A2AEligibilityPolicy policy) {
        this.policy = Objects.requireNonNull(policy, "policy must not be null");
    }

    @Override
    public AgentCard snapshot(AgentCard target) {
        return A2AAgentCardSnapshots.snapshot(target);
    }

    @Override
    public String targetKey(AgentCard target) {
        Objects.requireNonNull(target, "target must not be null");
        Map<String, Object> identity = new TreeMap<>();
        identity.put("name", normalizeNullable(target.name()));
        identity.put("version", normalizeNullable(target.version()));
        List<Map<String, String>> interfaces = new ArrayList<>();
        List<AgentInterface> declaredInterfaces = target.supportedInterfaces() == null
                ? List.of()
                : target.supportedInterfaces();
        for (AgentInterface agentInterface : declaredInterfaces) {
            Map<String, String> value = new TreeMap<>();
            value.put("protocolBinding",
                    agentInterface == null ? null : normalizeNullable(agentInterface.protocolBinding()));
            value.put("protocolVersion",
                    agentInterface == null ? null : normalizeNullable(agentInterface.protocolVersion()));
            value.put("tenant", agentInterface == null ? null : normalizeNullable(agentInterface.tenant()));
            value.put("url", agentInterface == null ? null : normalizeNullable(agentInterface.url()));
            interfaces.add(value);
        }
        identity.put("supportedInterfaces", interfaces);
        try {
            byte[] json = OBJECT_MAPPER.writeValueAsBytes(identity);
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json));
        } catch (JsonProcessingException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("unable to compute A2A target key", exception);
        }
    }

    @Override
    public List<IntentCandidate> candidates(int targetIndex, AgentCard target) {
        return candidates(targetIndex, target, new IntentCandidateLimits(4096, 16384, 32, 16));
    }

    @Override
    public List<IntentCandidate> candidates(int targetIndex, AgentCard target, IntentCandidateLimits limits) {
        Objects.requireNonNull(target, "target must not be null");
        Objects.requireNonNull(limits, "limits must not be null");
        if (!cardEligible(target, limits)) {
            return List.of();
        }
        String targetKey = targetKey(target);
        List<IntentCandidate> candidates = new ArrayList<>();
        for (AgentSkill skill : target.skills()) {
            IntentCandidate candidate = candidate(targetIndex, targetKey, target, skill, limits);
            if (candidate != null) {
                candidates.add(candidate);
            }
        }
        return List.copyOf(candidates);
    }

    private boolean cardEligible(AgentCard card, IntentCandidateLimits limits) {
        if (!withinRequired(card.name(), limits.maxFieldLength())
                || !withinRequired(card.description(), limits.maxFieldLength())
                || !withinRequired(card.version(), limits.maxFieldLength()) || card.capabilities() == null
                || card.defaultInputModes() == null || card.defaultInputModes().isEmpty()
                || card.defaultOutputModes() == null || card.defaultOutputModes().isEmpty()
                || !allWithin(card.defaultInputModes(), limits.maxFieldLength())
                || !allWithin(card.defaultOutputModes(), limits.maxFieldLength()) || card.skills() == null
                || card.supportedInterfaces() == null || card.supportedInterfaces().isEmpty()
                || !interfacesWithin(card.supportedInterfaces(), limits.maxFieldLength())) {
            return false;
        }
        if (!trusted(card) || !compatibleInterface(card.supportedInterfaces())) {
            return false;
        }
        if (!requiredExtensionsSupported(card)) {
            return false;
        }
        Set<String> skillIds = new HashSet<>();
        for (AgentSkill skill : card.skills()) {
            String id = normalizeNullable(skill.id());
            if (id == null || !skillIds.add(id)) {
                return false;
            }
        }
        return true;
    }

    private static boolean interfacesWithin(List<AgentInterface> interfaces, int limit) {
        for (AgentInterface agentInterface : interfaces) {
            if (agentInterface == null || !withinRequired(agentInterface.protocolBinding(), limit)
                    || !withinRequired(agentInterface.url(), limit)
                    || !withinRequired(agentInterface.protocolVersion(), limit)) {
                return false;
            }
            String tenant = normalizeNullable(agentInterface.tenant());
            if (tenant != null && codePointLength(tenant) > limit) {
                return false;
            }
        }
        return true;
    }

    private boolean compatibleInterface(List<AgentInterface> interfaces) {
        for (AgentInterface agentInterface : interfaces) {
            if (agentInterface == null) {
                continue;
            }
            String binding = normalizeNullable(agentInterface.protocolBinding());
            String version = normalizeNullable(agentInterface.protocolVersion());
            if (binding != null && version != null
                    && policy.supportedProtocolBindings().contains(binding.toUpperCase(Locale.ROOT))
                    && policy.supportedProtocolVersions().contains(version)) {
                return true;
            }
        }
        return false;
    }

    private boolean requiredExtensionsSupported(AgentCard card) {
        if (card.capabilities() == null || card.capabilities().extensions() == null) {
            return true;
        }
        for (AgentExtension extension : card.capabilities().extensions()) {
            if (extension.required()) {
                String uri = normalizeNullable(extension.uri());
                if (uri == null || !policy.supportedRequiredExtensionUris().contains(uri)) {
                    return false;
                }
            }
        }
        return true;
    }

    private IntentCandidate candidate(int targetIndex, String targetKey, AgentCard card, AgentSkill skill,
            IntentCandidateLimits limits) {
        if (skill == null || !withinRequired(skill.id(), limits.maxFieldLength())
                || !withinRequired(skill.name(), limits.maxFieldLength())
                || !withinRequired(skill.description(), limits.maxFieldLength()) || skill.tags() == null
                || skill.tags().size() > limits.maxTagsPerCandidate()
                || (skill.examples() != null && skill.examples().size() > limits.maxExamplesPerCandidate())) {
            return null;
        }
        if (!allWithin(skill.tags(), limits.maxFieldLength())
                || !allWithin(skill.examples(), limits.maxFieldLength())) {
            return null;
        }
        List<String> modes = skill.inputModes() == null || skill.inputModes().isEmpty()
                ? card.defaultInputModes()
                : skill.inputModes();
        if (!acceptedMode(modes)) {
            return null;
        }
        List<SecurityRequirement> requirements = skill.securityRequirements() == null
                || skill.securityRequirements().isEmpty() ? card.securityRequirements() : skill.securityRequirements();
        List<SecurityRequirement> effectiveRequirements = requirements == null ? List.of() : requirements;
        if (!securitySatisfied(card, effectiveRequirements)) {
            return null;
        }

        String document = document(card, skill);
        if (codePointLength(document) > limits.maxCandidateLength()) {
            return null;
        }
        return new IntentCandidate(targetIndex, targetKey + ":" + normalizeRequired(skill.id()), document);
    }

    private boolean acceptedMode(List<String> modes) {
        if (modes == null || modes.isEmpty()) {
            return false;
        }
        for (String mode : modes) {
            String normalized = normalizeNullable(mode);
            if (normalized != null && policy.acceptedInputModes().contains(normalized.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private boolean trusted(AgentCard card) {
        try {
            return policy.contentTrustEvaluator().isTrusted(card);
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private boolean securitySatisfied(AgentCard card, List<SecurityRequirement> effectiveRequirements) {
        try {
            return policy.securityEvaluator().canSatisfy(card, effectiveRequirements);
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static String document(AgentCard card, AgentSkill skill) {
        Set<String> uniqueTags = new HashSet<>();
        for (String tag : skill.tags()) {
            uniqueTags.add(normalizeRequired(tag));
        }
        List<String> tags = new ArrayList<>(uniqueTags);
        tags.sort(CODE_POINT_ORDER);

        Set<String> examples = new LinkedHashSet<>();
        if (skill.examples() != null) {
            for (String example : skill.examples()) {
                examples.add(normalizeRequired(example));
            }
        }
        String tagText = tags.isEmpty() ? "<none>" : String.join(", ", tags);
        String exampleText = examples.isEmpty()
                ? "<none>"
                : examples.stream().map(value -> "- " + value).collect(java.util.stream.Collectors.joining("\n"));
        return "[Target]\n" + normalizeRequired(card.name()) + "\n\n[Target scope]\n"
                + normalizeRequired(card.description()) + "\n\n[Intent]\n" + normalizeRequired(skill.name())
                + "\n\n[Can handle]\n" + normalizeRequired(skill.description()) + "\n\n[Keywords]\n" + tagText
                + "\n\n[Examples]\n" + exampleText;
    }

    private static boolean allWithin(List<String> values, int limit) {
        if (values == null) {
            return true;
        }
        for (String value : values) {
            if (!withinRequired(value, limit)) {
                return false;
            }
        }
        return true;
    }

    private static boolean withinRequired(String value, int limit) {
        String normalized = normalizeNullable(value);
        return normalized != null && codePointLength(normalized) <= limit;
    }

    private static int codePointLength(String value) {
        return value.codePointCount(0, value.length());
    }

    private static String normalizeRequired(String value) {
        String normalized = normalizeNullable(value);
        if (normalized == null) {
            throw new IllegalArgumentException("A2A identity and semantic fields must not be blank");
        }
        return normalized;
    }

    private static String normalizeNullable(String value) {
        if (value == null) {
            return null;
        }
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFC).trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private static int compareCodePoints(String left, String right) {
        int leftOffset = 0;
        int rightOffset = 0;
        while (leftOffset < left.length() && rightOffset < right.length()) {
            int leftCodePoint = left.codePointAt(leftOffset);
            int rightCodePoint = right.codePointAt(rightOffset);
            int compared = Integer.compare(leftCodePoint, rightCodePoint);
            if (compared != 0) {
                return compared;
            }
            leftOffset += Character.charCount(leftCodePoint);
            rightOffset += Character.charCount(rightCodePoint);
        }
        return Integer.compare(left.length() - leftOffset, right.length() - rightOffset);
    }
}
