package com.openjiuwen.studio.dsl.support;

import com.openjiuwen.core.workflow.ComponentExecutable;
import com.openjiuwen.studio.dsl.adapter.PassthroughStudioNode;
import com.openjiuwen.studio.dsl.exec.NodeBuildContext;
import com.openjiuwen.studio.dsl.model.AssembledNode;
import com.openjiuwen.studio.dsl.schema.DslNodeShellValidator;
import com.openjiuwen.studio.dsl.spi.NodeHandlerFactory;
import java.util.Set;

/** Test-only custom Factory loaded via META-INF/services (L2 T4). */
public final class AcmeEnrichNodeFactory implements NodeHandlerFactory {
    @Override
    public String canonicalType() {
        return "acme.enrich";
    }

    @Override
    public Set<String> aliases() {
        return Set.of();
    }

    @Override
    public ComponentExecutable create(AssembledNode node, NodeBuildContext ctx) {
        DslNodeShellValidator.validateShell(node);
        return new PassthroughStudioNode(node);
    }
}
