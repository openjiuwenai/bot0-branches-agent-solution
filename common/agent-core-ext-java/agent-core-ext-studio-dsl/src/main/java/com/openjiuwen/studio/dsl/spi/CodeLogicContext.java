package com.openjiuwen.studio.dsl.spi;

import java.util.Map;

public final class CodeLogicContext {
    private final String nodeId;
    private final Map<String, Object> configs;

    public CodeLogicContext(String nodeId, Map<String, Object> configs) {
        this.nodeId = nodeId;
        this.configs = configs;
    }

    public String nodeId() {
        return nodeId;
    }

    public Map<String, Object> configs() {
        return configs;
    }
}
