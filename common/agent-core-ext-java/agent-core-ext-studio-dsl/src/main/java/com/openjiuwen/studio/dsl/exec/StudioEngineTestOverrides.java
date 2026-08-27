/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.exec;

import com.openjiuwen.core.foundation.tool.mcp.McpClient;
import com.openjiuwen.studio.dsl.complexintent.ComplexIntentDetectionEngine;
import com.openjiuwen.studio.dsl.extractor.ExtractorLlmExtractor;
import com.openjiuwen.studio.dsl.flowagent.FlowAgentEngine;
import com.openjiuwen.studio.dsl.flowapi.FlowApiEngine;
import com.openjiuwen.studio.dsl.intentdetection.IntentDetectionLlmDetector;
import com.openjiuwen.studio.dsl.llmchain.LlmChainEngine;

/**
 * Optional test stubs wired through {@link NodeBuildContext} (production: always {@code null}).
 *
 * <p>Tests install via {@code com.openjiuwen.studio.dsl.testsupport.StudioEngineTestSupport}.
 *
 * @since 2026-08-27
 */

public final class StudioEngineTestOverrides {
    private final LlmChainEngine.ModelBridge llmBridge;
    private final IntentDetectionLlmDetector.ModelInvoker intentInvoker;
    private final ExtractorLlmExtractor.ModelInvoker extractorInvoker;
    private final FlowAgentEngine.ReactBridge flowAgentBridge;
    private final ComplexIntentDetectionEngine.TestBridge complexIntentBridge;
    private final FlowApiEngine.TestBridge flowApiBridge;
    private final McpClient mcpClient;

    private StudioEngineTestOverrides(Builder builder) {
        this.llmBridge = builder.llmBridge;
        this.intentInvoker = builder.intentInvoker;
        this.extractorInvoker = builder.extractorInvoker;
        this.flowAgentBridge = builder.flowAgentBridge;
        this.complexIntentBridge = builder.complexIntentBridge;
        this.flowApiBridge = builder.flowApiBridge;
        this.mcpClient = builder.mcpClient;
    }

    /**
     * llmBridge.
     *
     * @return result
     * @since 0.1.0
     */

    public LlmChainEngine.ModelBridge llmBridge() {
        return llmBridge;
    }

    /**
     * intentInvoker.
     *
     * @return result
     * @since 0.1.0
     */

    public IntentDetectionLlmDetector.ModelInvoker intentInvoker() {
        return intentInvoker;
    }

    /**
     * extractorInvoker.
     *
     * @return result
     * @since 0.1.0
     */

    public ExtractorLlmExtractor.ModelInvoker extractorInvoker() {
        return extractorInvoker;
    }

    /**
     * flowAgentBridge.
     *
     * @return result
     * @since 0.1.0
     */

    public FlowAgentEngine.ReactBridge flowAgentBridge() {
        return flowAgentBridge;
    }

    /**
     * complexIntentBridge.
     *
     * @return result
     * @since 0.1.0
     */

    public ComplexIntentDetectionEngine.TestBridge complexIntentBridge() {
        return complexIntentBridge;
    }

    /**
     * flowApiBridge.
     *
     * @return result
     * @since 0.1.0
     */

    public FlowApiEngine.TestBridge flowApiBridge() {
        return flowApiBridge;
    }

    /**
     * mcpClient.
     *
     * @return result
     * @since 0.1.0
     */

    public McpClient mcpClient() {
        return mcpClient;
    }

    /**
     * builder.
     *
     * @return result
     * @since 0.1.0
     */

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder.
     *
     * @since 0.1.0
     */
    public static final class Builder {
        private LlmChainEngine.ModelBridge llmBridge;
        private IntentDetectionLlmDetector.ModelInvoker intentInvoker;
        private ExtractorLlmExtractor.ModelInvoker extractorInvoker;
        private FlowAgentEngine.ReactBridge flowAgentBridge;
        private ComplexIntentDetectionEngine.TestBridge complexIntentBridge;
        private FlowApiEngine.TestBridge flowApiBridge;
        private McpClient mcpClient;

        /**
         * llmBridge.
         *
         * @param bridge bridge
         * @return result
         * @since 0.1.0
         */

        public Builder llmBridge(LlmChainEngine.ModelBridge bridge) {
            this.llmBridge = bridge;
            return this;
        }

        /**
         * intentInvoker.
         *
         * @param invoker invoker
         * @return result
         * @since 0.1.0
         */

        public Builder intentInvoker(IntentDetectionLlmDetector.ModelInvoker invoker) {
            this.intentInvoker = invoker;
            return this;
        }

        /**
         * extractorInvoker.
         *
         * @param invoker invoker
         * @return result
         * @since 0.1.0
         */

        public Builder extractorInvoker(ExtractorLlmExtractor.ModelInvoker invoker) {
            this.extractorInvoker = invoker;
            return this;
        }

        /**
         * flowAgentBridge.
         *
         * @param bridge bridge
         * @return result
         * @since 0.1.0
         */

        public Builder flowAgentBridge(FlowAgentEngine.ReactBridge bridge) {
            this.flowAgentBridge = bridge;
            return this;
        }

        /**
         * complexIntentBridge.
         *
         * @param bridge bridge
         * @return result
         * @since 0.1.0
         */

        public Builder complexIntentBridge(ComplexIntentDetectionEngine.TestBridge bridge) {
            this.complexIntentBridge = bridge;
            return this;
        }

        /**
         * flowApiBridge.
         *
         * @param bridge bridge
         * @return result
         * @since 0.1.0
         */

        public Builder flowApiBridge(FlowApiEngine.TestBridge bridge) {
            this.flowApiBridge = bridge;
            return this;
        }

        /**
         * mcpClient.
         *
         * @param client client
         * @return result
         * @since 0.1.0
         */

        public Builder mcpClient(McpClient client) {
            this.mcpClient = client;
            return this;
        }

        /**
         * build.
         *
         * @return result
         * @since 0.1.0
         */

        public StudioEngineTestOverrides build() {
            return new StudioEngineTestOverrides(this);
        }
    }
}
