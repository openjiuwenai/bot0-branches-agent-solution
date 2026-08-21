/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.ext.agentfw;

import com.openjiuwen.core.runner.Runner;
import com.openjiuwen.core.session.stream.StreamMode;
import com.openjiuwen.service.adapters.agentcore.agentfw.JiuwenCoreAgentHandler;
import com.openjiuwen.service.adapters.agentcore.ext.concurrency.AgentInstanceManager;
import com.openjiuwen.service.adapters.agentcore.ext.concurrency.ConversationBusyException;
import com.openjiuwen.service.adapters.agentcore.ext.concurrency.TaskQuotaTracker;
import com.openjiuwen.service.adapters.agentcore.ext.external.ClientToolRail;
import com.openjiuwen.service.adapters.agentcore.ext.external.RemoteA2aToolInstaller;
import com.openjiuwen.service.adapters.agentcore.ext.middleware.otel.OtelRailBinding;
import com.openjiuwen.service.adapters.agentcore.ext.middleware.otel.OtelRuntimeSupport;
import com.openjiuwen.service.adapters.agentcore.ext.middleware.skillhub.SkillHubManager;
import com.openjiuwen.service.adapters.agentcore.external.ExternalSvcAdapterRegistrar;
import com.openjiuwen.service.adapters.agentcore.middleware.MiddlewareAdapterRegistrar;
import com.openjiuwen.service.spec.dto.AgentFailureDescriptor;
import com.openjiuwen.service.spec.dto.QueryResponse;
import com.openjiuwen.service.spec.dto.ServeRequest;
import com.openjiuwen.service.spec.exception.AgentExecutionException;
import com.openjiuwen.service.spec.spi.QueryStreamObserver;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * AgentCore handler extension that installs remote A2A tools and SkillHub skills before execution.
 *
 * <p>When {@link AgentInstanceManager} is injected (DFX-002 concurrency mode),
 * each request acquires a fresh per-Task Agent instance to avoid concurrency
 * issues in agent-core-java. The per-Task agent is passed to Runner via the
 * {@code currentTaskAgent} ThreadLocal, which is cleaned in the finally block.
 *
 * @since 2026-06-30
 */
public class JiuwenCoreAgentExtHandler extends JiuwenCoreAgentHandler {
    private static final Logger log = LoggerFactory.getLogger(JiuwenCoreAgentExtHandler.class);

    /** Stable failure code surfaced to protocol clients on a busy conversation. */
    static final String CONVERSATION_BUSY = "CONVERSATION_BUSY";

    private RemoteA2aToolInstaller remoteToolInstaller = RemoteA2aToolInstaller.noop();
    private IntentDeepAgentInstaller intentInstaller;
    private SkillHubManager skillHubManager;

    @Autowired(required = false)
    private AgentInstanceManager agentManager;

    @Autowired(required = false)
    private TaskQuotaTracker quotaTracker;

    final ThreadLocal<Object> currentTaskAgent = new ThreadLocal<>();

    public JiuwenCoreAgentExtHandler(Object agent) {
        super(requireAgentInstance(agent));
    }

    public JiuwenCoreAgentExtHandler(Object agent, MiddlewareAdapterRegistrar middlewareAdapterRegistrar) {
        super(requireAgentInstance(agent), middlewareAdapterRegistrar);
    }

    public JiuwenCoreAgentExtHandler(Object agent, ExternalSvcAdapterRegistrar externalSvcAdapterRegistrar) {
        super(requireAgentInstance(agent), externalSvcAdapterRegistrar);
    }

    public JiuwenCoreAgentExtHandler(Object agent, MiddlewareAdapterRegistrar middlewareAdapterRegistrar,
            ExternalSvcAdapterRegistrar externalSvcAdapterRegistrar) {
        super(requireAgentInstance(agent), middlewareAdapterRegistrar, externalSvcAdapterRegistrar);
    }

    @Autowired(required = false)
    void setRemoteA2aToolInstaller(RemoteA2aToolInstaller remoteToolInstaller) {
        this.remoteToolInstaller = Objects.requireNonNull(remoteToolInstaller, "remoteToolInstaller");
    }

    @Autowired(required = false)
    void setIntentDeepAgentInstaller(IntentDeepAgentInstaller intentInstaller) {
        this.intentInstaller = Objects.requireNonNull(intentInstaller, "intentInstaller");
    }

    /**
     * Inject the SkillHubManager when the SkillHub chain is active (enabled=true and provider present). Null when
     * inactive - handler runs without skills.
     *
     * @param skillHubManager
     *            the SkillHub manager bean, or null when middleware is inactive
     */
    @Autowired(required = false)
    void setSkillHubManager(SkillHubManager skillHubManager) {
        this.skillHubManager = skillHubManager;
    }

    void setAgentManager(AgentInstanceManager agentManager) {
        this.agentManager = agentManager;
    }

    void setQuotaTracker(TaskQuotaTracker quotaTracker) {
        this.quotaTracker = quotaTracker;
    }

    @Override
    public void start() {
        if (skillHubManager != null) {
            // Layered failure semantics:
            // - provider.start() config failures: thrown (fail fast)
            // - required auth/access/lookup failures: thrown (fail fast)
            // - download/integrity-check failures: degraded + retried in
            // background inside Manager.start() (never reach here)
            // Any exception from skillHubManager.start() propagates and blocks
            // super.start(), so the Agent card never becomes ready.
            skillHubManager.start();
        }
        super.start();
    }

    @Override
    public void stop() {
        if (skillHubManager != null) {
            try {
                skillHubManager.stop();
            } catch (IllegalStateException ex) {
                log.warn("SkillHub stop failed reason={}", ex.getMessage());
            }
        }
        super.stop();
    }

    @Override
    public void streamQuery(ServeRequest request, QueryStreamObserver observer) {
        Object agent = resolveTaskAgent(request);
        try {
            installBeforeRun(agent);
            OtelRailBinding otelBinding = bindOtel(agent, request);
            try (var binding = ClientToolRail.bind(agent, request)) {
                currentTaskAgent.set(agent);
                onTaskWorking(request);
                super.streamQuery(request, observer);
            } finally {
                closeOtelQuietly(otelBinding);
            }
        } finally {
            currentTaskAgent.remove();
            releaseTaskResources(request, agent);
        }
    }

    @Override
    public QueryResponse query(ServeRequest request) {
        Object agent = resolveTaskAgent(request);
        try {
            installBeforeRun(agent);
            OtelRailBinding otelBinding = bindOtel(agent, request);
            try (var binding = ClientToolRail.bind(agent, request)) {
                currentTaskAgent.set(agent);
                onTaskWorking(request);
                return super.query(request);
            } finally {
                closeOtelQuietly(otelBinding);
            }
        } finally {
            currentTaskAgent.remove();
            releaseTaskResources(request, agent);
        }
    }

    private void closeOtelQuietly(OtelRailBinding otelBinding) {
        try {
            otelBinding.close();
        } catch (IllegalStateException | IllegalArgumentException | UnsupportedOperationException ex) {
            log.warn("OTel binding close failed reason={}", ex.getMessage());
        }
    }

    private OtelRailBinding bindOtel(Object agent, ServeRequest request) {
        // 同模块 middleware/otel：未启用时 OtelRuntimeSupport 内部返回 no-op 绑定
        return OtelRuntimeSupport.bindRequest(agent, request.getConversationId());
    }

    @Override
    protected Iterator<Object> executeAgentStreaming(Map<String, Object> inputs, Object session,
            List<StreamMode> streamModes) {
        Object agent = currentTaskAgent.get();
        if (agent != null) {
            return Runner.runAgentStreaming(agent, inputs, session, null, streamModes);
        }
        return super.executeAgentStreaming(inputs, session, streamModes);
    }

    @Override
    protected Object executeAgent(Map<String, Object> inputs, Object session) {
        Object agent = currentTaskAgent.get();
        if (agent != null) {
            return Runner.runAgent(agent, inputs, session, null);
        }
        return super.executeAgent(inputs, session);
    }

    protected void installBeforeRun(Object agent) {
        if (intentInstaller == null) {
            remoteToolInstaller.install(agent);
        } else {
            intentInstaller.install(agent);
            if (intentInstaller.exposeAgentCardTools()) {
                remoteToolInstaller.install(agent);
            }
        }
        if (skillHubManager != null) {
            skillHubManager.register(agent);
        }
    }

    private Object resolveTaskAgent(ServeRequest request) {
        if (agentManager != null) {
            try {
                return agentManager.acquire(request.getConversationId());
            } catch (ConversationBusyException ex) {
                throw new AgentExecutionException(
                        "Conversation busy: another task is still running for this conversation",
                        new AgentFailureDescriptor(CONVERSATION_BUSY, null, true), ex);
            }
        }
        return getAgent();
    }

    private void onTaskWorking(ServeRequest request) {
        if (quotaTracker != null) {
            String taskId = request.getMetadata() != null
                    ? (String) request.getMetadata().get("runtime.parentTaskId") : null;
            quotaTracker.onTaskWorking(request.getConversationId(), taskId);
        }
    }

    private void releaseTaskResources(ServeRequest request, Object agent) {
        if (quotaTracker != null) {
            quotaTracker.onTaskReleased(request.getConversationId());
        }
        if (agentManager != null) {
            agentManager.release(request.getConversationId(), agent);
        }
    }

    private static Object requireAgentInstance(Object agent) {
        Objects.requireNonNull(agent, "agent");
        if (agent instanceof String) {
            throw new IllegalArgumentException("JiuwenCoreAgentExtHandler requires an agent instance, not agent id");
        }
        return agent;
    }
}
