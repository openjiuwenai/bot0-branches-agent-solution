/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.spi;

import com.openjiuwen.core.workflow.ComponentExecutable;
import com.openjiuwen.studio.dsl.model.AssembledNode;

import java.util.Optional;

/**
 * Optional host bridge to agent-core-java LLM/Intent/Questioner/Knowledge executables (L2: 适配优先).
 * When null/returns null, model nodes use Studio-shaped fallbacks or fail with clear surface.
 *
 * @since 2026-08-17
 */
public interface CoreExecutableFactory {
    /**
     * createLlm.
     * @param node node
     */
    default Optional<ComponentExecutable> createLlm(AssembledNode node) {
        return Optional.empty();
    }
    /**
     * createIntentDetection.
     * @param node node
     */
    default Optional<ComponentExecutable> createIntentDetection(AssembledNode node) {
        return Optional.empty();
    }
    /**
     * createQuestioner.
     * @param node node
     */
    default Optional<ComponentExecutable> createQuestioner(AssembledNode node) {
        return Optional.empty();
    }
    /**
     * createKnowledgeRetrieval.
     * @param node node
     */
    default Optional<ComponentExecutable> createKnowledgeRetrieval(AssembledNode node) {
        return Optional.empty();
    }
    /**
     * createExtractor.
     * @param node node
     */
    default Optional<ComponentExecutable> createExtractor(AssembledNode node) {
        return Optional.empty();
    }
}
