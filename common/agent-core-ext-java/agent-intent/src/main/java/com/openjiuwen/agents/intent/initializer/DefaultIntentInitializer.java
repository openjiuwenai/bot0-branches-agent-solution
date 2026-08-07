/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.intent.initializer;

import com.openjiuwen.agents.intent.api.IntentInitializationException;
import com.openjiuwen.agents.intent.api.IntentInitializer;
import com.openjiuwen.agents.intent.api.IntentResultFunction;
import com.openjiuwen.agents.intent.model.A2ADelegateArguments;
import com.openjiuwen.agents.intent.model.AgentCardInput;
import com.openjiuwen.agents.intent.model.CustomIntentRegistration;
import com.openjiuwen.agents.intent.model.InitializedIntents;
import com.openjiuwen.agents.intent.model.IntentCatalogInput;
import com.openjiuwen.agents.intent.model.IntentDefinition;
import com.openjiuwen.agents.intent.model.IntentSuiteConfig;
import com.openjiuwen.agents.intent.model.NoIntentResultArguments;
import com.openjiuwen.agents.intent.result.A2ADelegateIntentResultFunction;

import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.AgentSkill;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Default complete-catalog initializer for Agent Cards, custom intents and fallback.
 */
public final class DefaultIntentInitializer implements IntentInitializer {
    private static final Logger log = LoggerFactory.getLogger(DefaultIntentInitializer.class);

    private final IntentResultFunction a2aResultFunction;

    /**
     * Creates an initializer with one shared A2A result function.
     */
    public DefaultIntentInitializer() {
        this(new A2ADelegateIntentResultFunction());
    }

    /**
     * Creates an initializer with a supplied shared A2A result function.
     *
     * @param a2aResultFunction shared Agent Card result function
     */
    public DefaultIntentInitializer(IntentResultFunction a2aResultFunction) {
        if (a2aResultFunction == null) {
            throw new IllegalArgumentException("a2aResultFunction must not be null");
        }
        this.a2aResultFunction = a2aResultFunction;
    }

    @Override
    public InitializedIntents initialize(IntentSuiteConfig config, IntentCatalogInput catalog) {
        if (config == null || catalog == null) {
            throw new IntentInitializationException("config and catalog must not be null");
        }
        List<IntentDefinition> definitions = new ArrayList<>();
        catalog.agentCards().forEach(input -> addAgentCardIntents(input, definitions));
        catalog.customIntents().forEach(registration -> definitions.add(customIntent(registration, true)));
        IntentDefinition fallback = catalog.fallback() == null ? null : customIntent(catalog.fallback(), false);
        return new InitializedIntents(definitions, fallback);
    }

    private void addAgentCardIntents(AgentCardInput input, List<IntentDefinition> definitions) {
        AgentCard card = input.agentCard();
        if (card.skills() == null || card.skills().isEmpty()) {
            return;
        }
        A2ADelegateArguments arguments = new A2ADelegateArguments(input.remoteAgentId());
        for (AgentSkill skill : card.skills()) {
            if (skill == null || !supportsText(card, skill)) {
                log.debug("Skipping non-text A2A skill, remoteAgentId={}, skillId={}", input.remoteAgentId(),
                        skill == null ? null : skill.id());
                continue;
            }
            String skillId = requireText(skill.id(), "Agent Skill id");
            definitions.add(new IntentDefinition("a2a:" + input.remoteAgentId() + ":" + skillId,
                    buildDescription(card, skill), a2aResultFunction, arguments));
        }
    }

    private static IntentDefinition customIntent(CustomIntentRegistration registration, boolean requiresDescription) {
        if (registration == null) {
            throw new IntentInitializationException("custom intent registration must not be null");
        }
        String id = requireText(registration.id(), "custom intent id");
        String description = normalize(registration.description());
        if (requiresDescription && description.isEmpty()) {
            throw new IntentInitializationException("custom intent description must not be blank: " + id);
        }
        if (registration.resultFunction() == null) {
            throw new IntentInitializationException("custom intent result function must not be null: " + id);
        }
        return new IntentDefinition(id, description, registration.resultFunction(), NoIntentResultArguments.INSTANCE);
    }

    private static boolean supportsText(AgentCard card, AgentSkill skill) {
        List<String> modes = skill.inputModes();
        if (modes == null || modes.isEmpty()) {
            modes = card.defaultInputModes();
        }
        if (modes == null) {
            return false;
        }
        return modes.stream().filter(value -> value != null).map(value -> value.trim().toLowerCase(Locale.ROOT))
                .anyMatch(value -> value.equals("text") || value.startsWith("text/"));
    }

    private static String buildDescription(AgentCard card, AgentSkill skill) {
        List<String> sections = new ArrayList<>();
        addSection(sections, "Agent", card.name());
        addSection(sections, "Agent scope", card.description());
        addSection(sections, "Skill", skill.name());
        addSection(sections, "Can handle", skill.description());
        addValues(sections, "Tags", skill.tags());
        addValues(sections, "Examples", skill.examples());
        if (sections.isEmpty()) {
            throw new IntentInitializationException("Agent Skill does not contain matching text: " + skill.id());
        }
        return String.join("\n\n", sections);
    }

    private static void addSection(List<String> sections, String label, String value) {
        String normalized = normalize(value);
        if (!normalized.isEmpty()) {
            sections.add("[" + label + "]\n" + normalized);
        }
    }

    private static void addValues(List<String> sections, String label, List<String> values) {
        if (values == null || values.isEmpty()) {
            return;
        }
        Set<String> normalized = new LinkedHashSet<>();
        values.stream().map(DefaultIntentInitializer::normalize).filter(value -> !value.isEmpty())
                .forEach(normalized::add);
        if (!normalized.isEmpty()) {
            sections.add("[" + label + "]\n" + String.join("\n", normalized));
        }
    }

    private static String requireText(String value, String name) {
        String normalized = normalize(value);
        if (normalized.isEmpty()) {
            throw new IntentInitializationException(name + " must not be blank");
        }
        return normalized;
    }

    private static String normalize(String value) {
        return value == null ? "" : Normalizer.normalize(value, Normalizer.Form.NFC).trim();
    }
}
