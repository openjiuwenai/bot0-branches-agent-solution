package com.openjiuwen.studio.dsl.spi;

import java.util.Map;

/** Host-provided Agent invoker; remote A2A semantics FEAT-004. */
public interface AgentInvoker {
    Map<String, Object> invoke(String agentId, Map<String, Object> inputs) throws Exception;
}
