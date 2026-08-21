package com.openjiuwen.studio.dsl.spi;

import com.openjiuwen.core.workflow.ComponentExecutable;
import com.openjiuwen.studio.dsl.model.AssembledNode;

/**
 * Optional host bridge to agent-core-java LLM/Intent/Questioner/Knowledge executables (L2: 适配优先).
 * When null/returns null, model nodes use Studio-shaped fallbacks or fail with clear surface.
 */
public interface CoreExecutableFactory {
    default ComponentExecutable createLlm(AssembledNode node) {
        return null;
    }

    default ComponentExecutable createIntentDetection(AssembledNode node) {
        return null;
    }

    default ComponentExecutable createQuestioner(AssembledNode node) {
        return null;
    }

    default ComponentExecutable createKnowledgeRetrieval(AssembledNode node) {
        return null;
    }

    default ComponentExecutable createExtractor(AssembledNode node) {
        return null;
    }
}
