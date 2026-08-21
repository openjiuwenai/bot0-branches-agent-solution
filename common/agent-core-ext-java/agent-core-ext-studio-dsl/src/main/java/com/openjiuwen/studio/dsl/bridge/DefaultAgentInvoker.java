package com.openjiuwen.studio.dsl.bridge;

import com.openjiuwen.studio.dsl.spi.AgentInvoker;
import com.openjiuwen.studio.dsl.spi.AgentRegistry;
import java.util.LinkedHashMap;
import java.util.Map;

public final class DefaultAgentInvoker implements AgentInvoker {
    private final AgentRegistry registry;

    public DefaultAgentInvoker(AgentRegistry registry) {
        this.registry = registry;
    }

    @Override
    public Map<String, Object> invoke(String agentId, Map<String, Object> inputs) throws Exception {
        return registry
                .find(agentId)
                .orElseThrow(() -> new IllegalStateException("agent not registered: " + agentId))
                .apply(inputs == null ? Map.of() : inputs);
    }
}
