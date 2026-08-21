package com.openjiuwen.studio.dsl.spi;

import com.openjiuwen.core.foundation.tool.Tool;
import java.util.Optional;

/** Host tool registry for jiuwen.plugin apiId path (Studio FlowApi). */
public interface ToolRegistry {
    Optional<Tool> find(String apiId);
}
