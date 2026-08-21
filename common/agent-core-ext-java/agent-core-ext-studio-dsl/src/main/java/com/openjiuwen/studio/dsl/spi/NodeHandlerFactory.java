package com.openjiuwen.studio.dsl.spi;

import com.openjiuwen.core.workflow.ComponentExecutable;
import com.openjiuwen.studio.dsl.exec.NodeBuildContext;
import com.openjiuwen.studio.dsl.exec.NodeExecutionException;
import com.openjiuwen.studio.dsl.model.AssembledNode;
import java.util.Set;

/** Unified node SPI (FEAT-028 MUST). */
public interface NodeHandlerFactory {
    String canonicalType();

    default Set<String> aliases() {
        return Set.of();
    }

    default boolean supports(String irType) {
        if (irType == null) {
            return false;
        }
        return canonicalType().equals(irType) || aliases().contains(irType);
    }

    ComponentExecutable create(AssembledNode node, NodeBuildContext ctx) throws NodeExecutionException;
}
