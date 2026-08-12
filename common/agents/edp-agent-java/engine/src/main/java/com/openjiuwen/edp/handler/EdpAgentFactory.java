/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.edp.handler;

import com.openjiuwen.core.singleagent.schema.AgentCard;
import com.openjiuwen.harness.deep_agent.DeepAgent;
import com.openjiuwen.harness.factory.HarnessFactory;
import com.openjiuwen.harness.schema.config.DeepAgentConfig;
import com.openjiuwen.service.adapters.agentcore.ext.concurrency.AgentFactory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Per-Task Agent factory for DFX-002 concurrency mode.
 *
 * <p>Creates fresh {@link DeepAgent} instances using {@link HarnessFactory},
 * with shared configuration extracted from the startup-time global initialization
 * performed by {@link EdpaExtHandler#performInit}.
 *
 * @since 0.1.2
 */
public class EdpAgentFactory implements AgentFactory {

    private static final Logger log = LoggerFactory.getLogger(EdpAgentFactory.class);

    private final AgentCard agentCard;

    private final DeepAgentConfig deepAgentConfig;

    /**
     * Creates a factory with the shared agent card and config template.
     *
     * @param agentCard the deterministic agent card (id/name from startup config)
     * @param deepAgentConfig the DeepAgent configuration template
     */
    public EdpAgentFactory(AgentCard agentCard, DeepAgentConfig deepAgentConfig) {
        this.agentCard = agentCard;
        this.deepAgentConfig = deepAgentConfig;
    }

    @Override
    public Object create() {
        log.info("EdpAgentFactory creating per-Task DeepAgent, agentId={}", agentCard.getId());
        DeepAgent agent = HarnessFactory.createDeepAgent(agentCard, deepAgentConfig, null);
        agent.ensureInitialized();
        return agent;
    }

    @Override
    public void destroy(Object agent) {
        if (agent instanceof DeepAgent deepAgent) {
            log.info("EdpAgentFactory destroying DeepAgent, agentId={}", agentCard.getId());
            deepAgent.destroy();
        }
    }
}
