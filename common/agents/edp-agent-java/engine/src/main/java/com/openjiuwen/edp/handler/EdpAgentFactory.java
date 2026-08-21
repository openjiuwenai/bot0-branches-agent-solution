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

import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Per-Task Agent factory for DFX-002 concurrency mode.
 *
 * <p>Creates fresh {@link DeepAgent} instances using {@link HarnessFactory},
 * then applies the same business initialization (skills, enhancers, scripts)
 * as the singleton agent via {@link EdpaExtHandler#initPerTaskAgent}.
 *
 * <p><strong>V1 thread safety</strong>: {@code create()} is serialized via
 * {@link ReentrantLock} because {@code HarnessFactory.createDeepAgent()} and
 * {@code DeepAgent.ensureInitialized()} write to the global
 * {@code Runner.resourceMgr()} maps ({@code ResourceMgr.idToCard},
 * {@code ToolMgr.mcpServerNameToIds} etc.), which are plain {@code HashMap}
 * and not thread-safe. Agent execution (LLM calls, tool invocations) runs
 * outside the lock and is fully parallel.
 *
 * <p>V2 optimization (future): once agent-core-java supports
 * {@code ensureInitializedLocal()} and {@code createDeepAgent(..., skipGlobal=true)},
 * the lock can be removed and creation becomes fully parallel.
 *
 * @since 0.1.2
 */
public class EdpAgentFactory implements AgentFactory {

    private static final Logger log = LoggerFactory.getLogger(EdpAgentFactory.class);

    /** V1: serializes agent creation to prevent concurrent writes to global ResourceMgr HashMaps. */
    private final Lock creationLock = new ReentrantLock();

    private final EdpaExtHandler.InitResult initResult;

    private final AgentCard agentCard;

    private final DeepAgentConfig deepAgentConfig;

    /**
     * Creates a factory with the startup initialization result.
     *
     * @param initResult the InitResult from {@link EdpaExtHandler#performInit},
     *                   containing shared config and pre-computed enhancement data
     */
    public EdpAgentFactory(EdpaExtHandler.InitResult initResult) {
        this.initResult = initResult;
        this.agentCard = initResult.getDeepAgent().getCard();
        this.deepAgentConfig = initResult.getDeepAgent().getConfig();
    }

    @Override
    public Object create() {
        creationLock.lock();
        try {
            log.info("EdpAgentFactory creating per-Task DeepAgent, agentId={}", agentCard.getId());
            return createAgent();
        } finally {
            creationLock.unlock();
        }
    }

    /**
     * Creates and initializes a single DeepAgent instance with full business capabilities.
     *
     * <p>Called inside the creation lock; subclasses may override for testing
     * or to customise the creation pipeline.
     *
     * @return a fully initialized DeepAgent with skills and business enhancers applied
     */
    protected DeepAgent createAgent() {
        DeepAgent agent = HarnessFactory.createDeepAgent(agentCard, deepAgentConfig, null);
        agent.ensureInitialized();
        EdpaExtHandler.initPerTaskAgent(agent, initResult);
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
