/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.testsupport;

import com.openjiuwen.core.foundation.tool.mcp.McpClient;
import com.openjiuwen.studio.dsl.complexintent.ComplexIntentDetectionEngine;
import com.openjiuwen.studio.dsl.config.StudioDslNodeProperties;
import com.openjiuwen.studio.dsl.contract.ToolRegistry;
import com.openjiuwen.studio.dsl.exec.NodeBuildContext;
import com.openjiuwen.studio.dsl.exec.StudioEngineTestOverrides;
import com.openjiuwen.studio.dsl.extractor.ExtractorConfig;
import com.openjiuwen.studio.dsl.extractor.ExtractorEngine;
import com.openjiuwen.studio.dsl.extractor.ExtractorLlmExtractor;
import com.openjiuwen.studio.dsl.flowagent.FlowAgentConfig;
import com.openjiuwen.studio.dsl.flowagent.FlowAgentEngine;
import com.openjiuwen.studio.dsl.flowapi.FlowApiEngine;
import com.openjiuwen.studio.dsl.flowmcp.FlowMcpEngine;
import com.openjiuwen.studio.dsl.intentdetection.IntentDetectionEngine;
import com.openjiuwen.studio.dsl.intentdetection.IntentDetectionLlmDetector;
import com.openjiuwen.studio.dsl.llmchain.LlmChainConfig;
import com.openjiuwen.studio.dsl.llmchain.LlmChainEngine;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.Map;

/**
 * Test-only stub wiring — ThreadLocal lives here, not in production engine classes.
 *
 * @since 2026-08-27
 */

public final class StudioEngineTestSupport {
    private static final ThreadLocal<StudioEngineTestOverrides> OVERRIDES = new ThreadLocal<>();
    private static final AtomicInteger INSTALL_COUNT = new AtomicInteger();

    private StudioEngineTestSupport() {}

    /**
     * install.
     *
     * @param overrides overrides
     * @since 0.1.0
     */

    public static void install(StudioEngineTestOverrides overrides) {
        OVERRIDES.set(overrides);
        INSTALL_COUNT.incrementAndGet();
    }

    /**
     * clear.
     *
     * @since 0.1.0
     *
     */

    public static void clear() {
        OVERRIDES.remove();
        INSTALL_COUNT.updateAndGet(n -> Math.max(0, n - 1));
    }

    /**
     * isActive.
     *
     * @return result
     * @since 0.1.0
     */

    public static boolean isActive() {
        return INSTALL_COUNT.get() > 0;
    }

    /**
     * current.
     *
     * @return result
     * @since 0.1.0
     */

    public static StudioEngineTestOverrides current() {
        return OVERRIDES.get();
    }

    /**
     * context.
     *
     * @param workflowId workflowId
     * @return result
     * @since 0.1.0
     */

    public static NodeBuildContext context(String workflowId) {
        StudioEngineTestOverrides o = OVERRIDES.get();
        NodeBuildContext ctx = NodeBuildContext.defaults(workflowId);
        return o == null ? ctx : ctx.withTestOverrides(o);
    }

    /**
     * context.
     *
     * @param workflowId workflowId
     * @param props props
     * @return result
     * @since 0.1.0
     */

    public static NodeBuildContext context(String workflowId, StudioDslNodeProperties props) {
        StudioEngineTestOverrides o = OVERRIDES.get();
        NodeBuildContext ctx = NodeBuildContext.defaults(workflowId, props);
        return o == null ? ctx : ctx.withTestOverrides(o);
    }

    /**
     * Attach current ThreadLocal overrides to a host-built context (e.g. {@code StudioDslModule.newRootContext}).
     *
     * @param ctx ctx
     * @return result
     * @since 0.1.0
     */
    public static NodeBuildContext withCurrentOverrides(NodeBuildContext ctx) {
        StudioEngineTestOverrides o = OVERRIDES.get();
        return o == null || ctx == null ? ctx : ctx.withTestOverrides(o);
    }

    // --- FlowAgent ---

    /**
     * installFlowAgent.
     *
     * @param bridge bridge
     * @since 0.1.0
     */

    public static void installFlowAgent(FlowAgentEngine.ReactBridge bridge) {
        install(StudioEngineTestOverrides.builder().flowAgentBridge(bridge).build());
    }

    /**
     * createFlowAgent.
     *
     * @param nodeId nodeId
     * @param conf conf
     * @return result
     * @since 0.1.0
     */

    public static FlowAgentEngine createFlowAgent(String nodeId, Map<String, Object> conf) {
        FlowAgentConfig config = FlowAgentConfig.from(nodeId, conf);
        FlowAgentEngine.ReactBridge bridge = bridge(StudioEngineTestOverrides::flowAgentBridge);
        if (bridge != null) {
            return new FlowAgentEngine(nodeId, config, bridge);
        }
        FlowAgentEngine engine = new FlowAgentEngine(nodeId);
        engine.init(conf);
        return engine;
    }

    // --- LlmChain ---

    /**
     * installLlm.
     *
     * @param bridge bridge
     * @since 0.1.0
     */

    public static void installLlm(LlmChainEngine.ModelBridge bridge) {
        install(StudioEngineTestOverrides.builder().llmBridge(bridge).build());
    }

    /**
     * createLlmChain.
     *
     * @param nodeId nodeId
     * @param conf conf
     * @return result
     * @since 0.1.0
     */

    public static LlmChainEngine createLlmChain(String nodeId, Map<String, Object> conf) {
        LlmChainConfig config = LlmChainConfig.from(nodeId, conf);
        LlmChainEngine.ModelBridge bridge = bridge(StudioEngineTestOverrides::llmBridge);
        if (bridge != null) {
            return new LlmChainEngine(nodeId, config, bridge);
        }
        LlmChainEngine engine = new LlmChainEngine(nodeId);
        engine.init(conf);
        return engine;
    }

    // --- IntentDetection ---

    /**
     * installIntent.
     *
     * @param invoker invoker
     * @since 0.1.0
     */

    public static void installIntent(IntentDetectionLlmDetector.ModelInvoker invoker) {
        install(StudioEngineTestOverrides.builder().intentInvoker(invoker).build());
    }

    /**
     * createIntentDetection.
     *
     * @param nodeId nodeId
     * @param configs configs
     * @param toolRegistry toolRegistry
     * @return result
     * @since 0.1.0
     */

    public static IntentDetectionEngine createIntentDetection(
            String nodeId, Map<String, Object> configs, ToolRegistry toolRegistry) {
        IntentDetectionLlmDetector.ModelInvoker invoker = bridge(StudioEngineTestOverrides::intentInvoker);
        if (invoker != null) {
            return new IntentDetectionEngine(nodeId, configs, invoker, toolRegistry);
        }
        return new IntentDetectionEngine(nodeId, configs, toolRegistry);
    }

    // --- Extractor ---

    /**
     * installExtractor.
     *
     * @param invoker invoker
     * @since 0.1.0
     */

    public static void installExtractor(ExtractorLlmExtractor.ModelInvoker invoker) {
        install(StudioEngineTestOverrides.builder().extractorInvoker(invoker).build());
    }

    /**
     * createExtractor.
     *
     * @param nodeId nodeId
     * @param conf conf
     * @return result
     * @since 0.1.0
     */

    public static ExtractorEngine createExtractor(String nodeId, Map<String, Object> conf) {
        ExtractorLlmExtractor.ModelInvoker invoker = bridge(StudioEngineTestOverrides::extractorInvoker);
        if (invoker != null) {
            return new ExtractorEngine(nodeId, ExtractorConfig.fromNodeConfigs(conf), invoker);
        }
        return new ExtractorEngine(nodeId);
    }

    // --- ComplexIntent ---

    /**
     * installComplexIntent.
     *
     * @param bridge bridge
     * @since 0.1.0
     */

    public static void installComplexIntent(ComplexIntentDetectionEngine.TestBridge bridge) {
        install(StudioEngineTestOverrides.builder().complexIntentBridge(bridge).build());
    }

    /**
     * createComplexIntent.
     *
     * @param nodeId nodeId
     * @param conf conf
     * @return result
     * @since 0.1.0
     */

    public static ComplexIntentDetectionEngine createComplexIntent(String nodeId, Map<String, Object> conf) {
        ComplexIntentDetectionEngine.TestBridge bridge = bridge(StudioEngineTestOverrides::complexIntentBridge);
        if (bridge != null) {
            return new ComplexIntentDetectionEngine(nodeId, conf, null, null, null, null, bridge);
        }
        return new ComplexIntentDetectionEngine(nodeId, conf);
    }

    // --- FlowApi ---

    /**
     * installFlowApi.
     *
     * @param bridge bridge
     * @since 0.1.0
     */

    public static void installFlowApi(FlowApiEngine.TestBridge bridge) {
        install(StudioEngineTestOverrides.builder().flowApiBridge(bridge).build());
    }

    /**
     * createFlowApi.
     *
     * @param nodeId nodeId
     * @param bridge bridge
     * @return result
     * @since 0.1.0
     */

    public static FlowApiEngine createFlowApi(String nodeId, FlowApiEngine.TestBridge bridge) {
        return new FlowApiEngine(nodeId, bridge);
    }
    // --- FlowMcp ---

    /**
     * installMcp.
     *
     * @param client client
     * @since 0.1.0
     */

    public static void installMcp(McpClient client) {
        install(StudioEngineTestOverrides.builder().mcpClient(client).build());
    }

    /**
     * createFlowMcp.
     *
     * @param nodeId nodeId
     * @param conf conf
     * @return result
     * @since 0.1.0
     */

    public static FlowMcpEngine createFlowMcp(String nodeId, Map<String, Object> conf) {
        McpClient client = bridge(StudioEngineTestOverrides::mcpClient);
        FlowMcpEngine engine = client != null ? new FlowMcpEngine(nodeId, client) : new FlowMcpEngine(nodeId);
        engine.init(conf);
        return engine;
    }

    @FunctionalInterface
    private interface OverrideBridge<T> {
        /**
         * get.
         * @param overrides overrides
         * @return result
         * @since 0.1.0
         */
        T get(StudioEngineTestOverrides overrides);
    }

    private static <T> T bridge(OverrideBridge<T> getter) {
        StudioEngineTestOverrides o = OVERRIDES.get();
        return o == null ? null : getter.get(o);
    }
}
