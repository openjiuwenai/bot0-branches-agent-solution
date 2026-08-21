package com.openjiuwen.studio.dsl.spi;

import com.openjiuwen.studio.dsl.model.AssembledWorkflow;
import java.util.Map;

/** Resolves nested workflow refs (provided by FEAT-027 impl or tests). */
@FunctionalInterface
public interface SubWorkflowResolver {
    AssembledWorkflow resolve(Map<String, Object> configs);
}
