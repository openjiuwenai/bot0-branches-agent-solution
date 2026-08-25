/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.studio.dsl.adapter.interact;

import com.openjiuwen.core.context.ModelContext;
import com.openjiuwen.core.session.NodeSessionApi;
import com.openjiuwen.core.workflow.ComponentExecutable;
import com.openjiuwen.studio.dsl.adapter.AbstractStudioNode;
import com.openjiuwen.studio.dsl.adapter.DelegatingStudioNode;
import com.openjiuwen.studio.dsl.exec.NodeBuildContext;
import com.openjiuwen.studio.dsl.model.AssembledNode;
import com.openjiuwen.studio.dsl.model.NodePayload;
import com.openjiuwen.studio.dsl.questioner.QuestionerConfig;
import com.openjiuwen.studio.dsl.questioner.QuestionerEngine;
import com.openjiuwen.studio.dsl.contract.NodeHandlerFactory;

import java.util.Map;
import java.util.Set;

/**
 * jiuwen.questioner — agent_runtime Questioner main path + rails (FEAT-031 / P3).
 *
 * <p>Prefers core QuestionerExecutable when model wired; else {@link QuestionerEngine}
 * (questionContent / field extract / rails / INPUT_REQUIRED).
 *
 * @since 2026-08-17
 */
public final class QuestionerNodeHandler implements NodeHandlerFactory {
    @Override
    public String canonicalType() {
        return "jiuwen.questioner";
    }

    @Override
    public Set<String> aliases() {
        return Set.of();
    }

    @Override
    public ComponentExecutable create(AssembledNode node, NodeBuildContext ctx) {
        if (ctx.coreExecutableFactory() != null) {
            return ctx.coreExecutableFactory()
                    .createQuestioner(node)
                    .<ComponentExecutable>map(core -> new DelegatingStudioNode(node, core))
                    .orElseGet(() -> new QuestionerExecutable(node));
        }
        return new QuestionerExecutable(node);
    }

    static final class QuestionerExecutable extends AbstractStudioNode {
        QuestionerExecutable(AssembledNode node) {
            super(node);
        }

        @Override
        protected NodePayload doInvoke(Map<String, Object> inputs, NodeSessionApi session, ModelContext context) {
            QuestionerConfig config = QuestionerConfig.fromNodeConfigs(node.configs());
            QuestionerEngine engine = new QuestionerEngine(node.id(), config);
            Map<String, Object> uf = engine.invoke(inputs, session);
            return NodePayload.userFields(uf);
        }
    }
}
