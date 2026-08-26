/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.flowagent;

import com.openjiuwen.core.foundation.tool.Tool;
import com.openjiuwen.core.runner.Runner;
import com.openjiuwen.studio.dsl.contract.ToolRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Load tools from plugin configs — 1:1 with Python {@code FlowAgentToolLoader}.
 *
 * @since 2026-08-26
 */
public final class FlowAgentToolLoader {
    private FlowAgentToolLoader() {}

    /**
     * Resolve tools: Studio {@link ToolRegistry} first, then {@code Runner.resourceMgr()}.
     *
     * @param pluginConfigs IR plugins list
     * @param studioTools optional studio tool registry (may be null)
     * @return tools found (missing plugin_id skipped with soft-fail like Python)
     */
    public static List<Tool> loadToolsFromPlugins(
            List<Map<String, Object>> pluginConfigs, ToolRegistry studioTools) {
        List<Tool> tools = new ArrayList<>();
        if (pluginConfigs == null) {
            return tools;
        }
        for (Map<String, Object> pluginCfg : pluginConfigs) {
            try {
                Tool tool = loadSinglePlugin(pluginCfg, studioTools);
                if (tool != null) {
                    tools.add(tool);
                }
            } catch (RuntimeException ignored) {
                // Python logs and continues
            }
        }
        return tools;
    }

    private static Tool loadSinglePlugin(Map<String, Object> pluginCfg, ToolRegistry studioTools) {
        Object idObj = pluginCfg.get("plugin_id");
        if (idObj == null) {
            idObj = pluginCfg.get("pluginId");
        }
        String pluginId = idObj == null ? "" : String.valueOf(idObj);
        if (pluginId.isBlank()) {
            return null;
        }
        if (studioTools != null) {
            var found = studioTools.find(pluginId);
            if (found.isPresent()) {
                return found.get();
            }
        }
        try {
            Object tool = Runner.resourceMgr().getTool(pluginId);
            if (tool instanceof Tool t) {
                return t;
            }
        } catch (RuntimeException ignored) {
            // resource mgr may be uninitialized in unit tests
        }
        return null;
    }
}
