package com.openjiuwen.studio.dsl.bridge;

import com.openjiuwen.studio.dsl.spi.AgentRegistry;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

public final class InMemoryAgentRegistry implements AgentRegistry {
    private final Map<String, Function<Map<String, Object>, Map<String, Object>>> byId = new ConcurrentHashMap<>();

    public void register(String agentId, Function<Map<String, Object>, Map<String, Object>> fn) {
        byId.put(agentId, fn);
    }

    @Override
    public Optional<Function<Map<String, Object>, Map<String, Object>>> find(String agentId) {
        return Optional.ofNullable(byId.get(agentId));
    }
}
