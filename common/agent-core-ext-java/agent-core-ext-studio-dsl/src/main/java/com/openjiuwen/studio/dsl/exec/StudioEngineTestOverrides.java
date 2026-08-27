/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.exec;

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

    private StudioEngineTestOverrides(Builder builder) {
        this.llmBridge = builder.llmBridge;
        this.intentInvoker = builder.intentInvoker;
        this.extractorInvoker = builder.extractorInvoker;
        this.flowAgentBridge = builder.flowAgentBridge;
        this.complexIntentBridge = builder.complexIntentBridge;
        this.flowApiBridge = builder.flowApiBridge;
    }

    public LlmChainEngine.ModelBridge llmBridge() {
        return llmBridge;
    }

    public IntentDetectionLlmDetector.ModelInvoker intentInvoker() {
        return intentInvoker;
    }

    public ExtractorLlmExtractor.ModelInvoker extractorInvoker() {
        return extractorInvoker;
    }

    public FlowAgentEngine.ReactBridge flowAgentBridge() {
        return flowAgentBridge;
    }

    public ComplexIntentDetectionEngine.TestBridge complexIntentBridge() {
        return complexIntentBridge;
    }

    public FlowApiEngine.TestBridge flowApiBridge() {
        return flowApiBridge;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private LlmChainEngine.ModelBridge llmBridge;
        private IntentDetectionLlmDetector.ModelInvoker intentInvoker;
        private ExtractorLlmExtractor.ModelInvoker extractorInvoker;
        private FlowAgentEngine.ReactBridge flowAgentBridge;
        private ComplexIntentDetectionEngine.TestBridge complexIntentBridge;
        private FlowApiEngine.TestBridge flowApiBridge;

        public Builder llmBridge(LlmChainEngine.ModelBridge bridge) {
            this.llmBridge = bridge;
            return this;
        }

        public Builder intentInvoker(IntentDetectionLlmDetector.ModelInvoker invoker) {
            this.intentInvoker = invoker;
            return this;
        }

        public Builder extractorInvoker(ExtractorLlmExtractor.ModelInvoker invoker) {
            this.extractorInvoker = invoker;
            return this;
        }

        public Builder flowAgentBridge(FlowAgentEngine.ReactBridge bridge) {
            this.flowAgentBridge = bridge;
            return this;
        }

        public Builder complexIntentBridge(ComplexIntentDetectionEngine.TestBridge bridge) {
            this.complexIntentBridge = bridge;
            return this;
        }

        public Builder flowApiBridge(FlowApiEngine.TestBridge bridge) {
            this.flowApiBridge = bridge;
            return this;
        }

        public StudioEngineTestOverrides build() {
            return new StudioEngineTestOverrides(this);
        }
    }
}
