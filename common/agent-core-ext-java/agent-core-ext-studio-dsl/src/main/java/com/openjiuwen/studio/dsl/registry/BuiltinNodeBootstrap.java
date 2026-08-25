/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.registry;

import com.openjiuwen.studio.dsl.adapter.control.AggregateNodeHandler;
import com.openjiuwen.studio.dsl.adapter.control.BranchNodeHandler;
import com.openjiuwen.studio.dsl.adapter.control.EndNodeHandler;
import com.openjiuwen.studio.dsl.adapter.control.ExceptionNodeHandler;
import com.openjiuwen.studio.dsl.adapter.control.LoopNodeHandler;
import com.openjiuwen.studio.dsl.adapter.control.NestedWorkflowNodeHandler;
import com.openjiuwen.studio.dsl.adapter.control.SetVariableNodeHandler;
import com.openjiuwen.studio.dsl.adapter.control.StartNodeHandler;
import com.openjiuwen.studio.dsl.adapter.external.AgentNodeHandler;
import com.openjiuwen.studio.dsl.adapter.external.CodeNodeHandler;
import com.openjiuwen.studio.dsl.adapter.external.McpNodeHandler;
import com.openjiuwen.studio.dsl.adapter.external.PluginNodeHandler;
import com.openjiuwen.studio.dsl.adapter.external.StreamTransformNodeHandler;
import com.openjiuwen.studio.dsl.adapter.interact.CardNodeHandler;
import com.openjiuwen.studio.dsl.adapter.interact.InputNodeHandler;
import com.openjiuwen.studio.dsl.adapter.interact.MessageNodeHandler;
import com.openjiuwen.studio.dsl.adapter.interact.QuestionerNodeHandler;
import com.openjiuwen.studio.dsl.adapter.model.ExtractorNodeHandler;
import com.openjiuwen.studio.dsl.adapter.model.IntentDetectionNodeHandler;
import com.openjiuwen.studio.dsl.adapter.model.KnowledgeRetrievalNodeHandler;
import com.openjiuwen.studio.dsl.adapter.model.LlmNodeHandler;

/**
 * Registers FEAT-031 21 built-in IR types with Studio-aligned adapters (L2 §4.3).
 *
 * @since 2026-08-17
 */
public final class BuiltinNodeBootstrap {
    private BuiltinNodeBootstrap() {}
    /**
     * registerAll.
     * @param registry registry
     */
    public static void registerAll(NodeTypeRegistry registry) {
        registry.register(new StartNodeHandler());
        registry.register(new EndNodeHandler());
        registry.register(new BranchNodeHandler());
        registry.register(new LoopNodeHandler(registry));
        registry.register(new AggregateNodeHandler());
        registry.register(new NestedWorkflowNodeHandler(registry));
        registry.register(new SetVariableNodeHandler());
        registry.register(new ExceptionNodeHandler());
        registry.register(new LlmNodeHandler());
        registry.register(new IntentDetectionNodeHandler());
        registry.register(new ExtractorNodeHandler());
        registry.register(new KnowledgeRetrievalNodeHandler());
        registry.register(new InputNodeHandler());
        registry.register(new MessageNodeHandler());
        registry.register(new CardNodeHandler());
        registry.register(new QuestionerNodeHandler());
        registry.register(new CodeNodeHandler());
        registry.register(new PluginNodeHandler());
        registry.register(new McpNodeHandler());
        registry.register(new AgentNodeHandler());
        registry.register(new StreamTransformNodeHandler());
    }
}
