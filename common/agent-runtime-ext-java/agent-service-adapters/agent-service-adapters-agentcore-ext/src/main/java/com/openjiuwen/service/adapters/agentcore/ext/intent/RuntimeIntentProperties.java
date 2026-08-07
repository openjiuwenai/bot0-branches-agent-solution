/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.ext.intent;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Runtime configuration for DeepAgent intent routing.
 *
 * @since 0.1.0
 */
@ConfigurationProperties(prefix = "openjiuwen.service.intent")
public class RuntimeIntentProperties {
    private boolean enabled;
    private MatchProperties match = new MatchProperties();
    private IntentPromptProperties prompt = new IntentPromptProperties();
    private boolean exposeAgentCardTools = true;
    private Map<String, CustomIntentProperties> customIntents = new LinkedHashMap<>();
    private FallbackProperties fallback;
    private Map<String, Object> extensionOptions = new LinkedHashMap<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public MatchProperties getMatch() {
        return match;
    }

    public void setMatch(MatchProperties match) {
        this.match = match;
    }

    public IntentPromptProperties getPrompt() {
        return prompt;
    }

    public void setPrompt(IntentPromptProperties prompt) {
        this.prompt = prompt;
    }

    public boolean isExposeAgentCardTools() {
        return exposeAgentCardTools;
    }

    public void setExposeAgentCardTools(boolean exposeAgentCardTools) {
        this.exposeAgentCardTools = exposeAgentCardTools;
    }

    public Map<String, CustomIntentProperties> getCustomIntents() {
        return customIntents;
    }

    public void setCustomIntents(Map<String, CustomIntentProperties> customIntents) {
        this.customIntents = customIntents;
    }

    public FallbackProperties getFallback() {
        return fallback;
    }

    public void setFallback(FallbackProperties fallback) {
        this.fallback = fallback;
    }

    public Map<String, Object> getExtensionOptions() {
        return extensionOptions;
    }

    public void setExtensionOptions(Map<String, Object> extensionOptions) {
        this.extensionOptions = extensionOptions;
    }

    /**
     * Match configuration.
     *
     * @since 0.1.0
     */
    public static class MatchProperties {
        private double threshold = 0.65D;

        public double getThreshold() {
            return threshold;
        }

        public void setThreshold(double threshold) {
            this.threshold = threshold;
        }
    }

    /**
     * Model-visible prompt configuration.
     *
     * @since 0.1.0
     */
    public static class IntentPromptProperties {
        private String toolDescription = "";
        private String routingInstructions = "";

        public String getToolDescription() {
            return toolDescription;
        }

        public void setToolDescription(String toolDescription) {
            this.toolDescription = toolDescription;
        }

        public String getRoutingInstructions() {
            return routingInstructions;
        }

        public void setRoutingInstructions(String routingInstructions) {
            this.routingInstructions = routingInstructions;
        }
    }

    /**
     * One configured custom intent.
     *
     * @since 0.1.0
     */
    public static class CustomIntentProperties {
        private String description;
        private String resultFunctionBean;

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        public String getResultFunctionBean() {
            return resultFunctionBean;
        }

        public void setResultFunctionBean(String resultFunctionBean) {
            this.resultFunctionBean = resultFunctionBean;
        }
    }

    /**
     * Optional fallback intent.
     *
     * @since 0.1.0
     */
    public static class FallbackProperties {
        private String id = "default-fallback";
        private String resultFunctionBean;

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getResultFunctionBean() {
            return resultFunctionBean;
        }

        public void setResultFunctionBean(String resultFunctionBean) {
            this.resultFunctionBean = resultFunctionBean;
        }
    }
}
