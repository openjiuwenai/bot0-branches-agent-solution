package com.openjiuwen.studio.dsl.spi;

import java.util.Map;

/** Host-provided MCP tool invoker (Studio FlowMcp). */
public interface McpToolInvoker {
    Map<String, Object> invoke(String server, String tool, Map<String, Object> arguments) throws Exception;
}
