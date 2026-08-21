package com.openjiuwen.studio.dsl.bridge;

import com.openjiuwen.core.foundation.tool.Tool;
import com.openjiuwen.studio.dsl.spi.ToolRegistry;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemoryToolRegistry implements ToolRegistry {
    private final Map<String, Tool> byId = new ConcurrentHashMap<>();

    public void register(String apiId, Tool tool) {
        byId.put(apiId, tool);
    }

    @Override
    public Optional<Tool> find(String apiId) {
        return Optional.ofNullable(byId.get(apiId));
    }
}
