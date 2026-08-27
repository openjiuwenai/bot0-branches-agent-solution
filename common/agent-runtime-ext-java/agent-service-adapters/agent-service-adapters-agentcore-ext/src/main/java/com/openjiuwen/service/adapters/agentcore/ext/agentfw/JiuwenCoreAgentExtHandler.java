/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.ext.agentfw;

import com.openjiuwen.core.runner.Runner;
import com.openjiuwen.core.session.stream.StreamMode;
import com.openjiuwen.service.adapters.agentcore.agentfw.JiuwenCoreAgentHandler;
import com.openjiuwen.service.adapters.agentcore.ext.concurrency.AgentInstanceManager;
import com.openjiuwen.service.adapters.agentcore.ext.concurrency.ConversationBusyException;
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
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AgentCore handler extension that installs remote A2A tools and SkillHub skills before execution.
 *
 * <p>When {@link AgentInstanceManager} is injected (DFX-002 concurrency mode),
 * each request acquires a fresh per-Task Agent instance to avoid concurrency
 * issues in agent-core-java. The per-Task agent is passed to Runner via the
 * {@code currentTaskAgent} ThreadLocal, which is cleaned in the finally block.
 *
 * <p>Thread contract (issue #154): the parent template methods
 * {@code executeAgent} / {@code executeAgentStreaming} must run on the same
 * thread that entered {@code query} / {@code streamQuery} (the current parent
 * implementations call back synchronously). If that contract is ever broken —
 * e.g. the parent moves execution onto a pool thread — the ThreadLocal binding
 * is missing on the execution thread and the request fails with
 * {@link IllegalStateException} instead of silently degrading to the shared
 * singleton agent, which would break per-Task isolation unnoticed.
 *
 * @since 2026-06-30
 */
public class JiuwenCoreAgentExtHandler extends JiuwenCoreAgentHandler {
    /** Stable failure code surfaced to protocol clients on a busy conversation. */
    static final String CONVERSATION_BUSY = "CONVERSATION_BUSY";

    private static final Logger log = LoggerFactory.getLogger(JiuwenCoreAgentExtHandler.class);

    final ThreadLocal<Object> currentTaskAgent = new ThreadLocal<>();

    private RemoteA2aToolInstaller remoteToolInstaller = RemoteA2aToolInstaller.noop();
    private IntentDeepAgentInstaller intentInstaller;
    private SkillHubManager skillHubManager;
    @Autowired(required = false)
    private AgentInstanceManager agentManager;

    /**
     * Task-level agent cache, keyed by conversationId.
     * Populated by {@link #prepareTask(ServeRequest)}, drained by
     * {@link #completeTask(Object)} with the matching token.
     * When an agent is present in this cache, {@link #resolveTaskAgent} returns it without
     * creating a new instance, and {@link #releaseTaskResources} skips the agent release.
     */
    private final ConcurrentHashMap<String, TaskAgentEntry> taskAgentCache = new ConcurrentHashMap<>();

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

    /** Cache value binding a cached agent to the token of the task that owns it. */
    private record TaskAgentEntry(Object token, Object agent) {
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
                super.streamQuery(request, observer);
            } finally {
                closeOtelQuietly(otelBinding);
            }
        } finally {
            currentTaskAgent.remove();
            releaseAgentIfUnmanaged(request, agent);
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
                return super.query(request);
            } finally {
                closeOtelQuietly(otelBinding);
            }
        } finally {
            currentTaskAgent.remove();
            releaseAgentIfUnmanaged(request, agent);
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
        return Runner.runAgentStreaming(requireTaskAgent("executeAgentStreaming"), inputs, session, null,
                streamModes);
    }

    @Override
    protected Object executeAgent(Map<String, Object> inputs, Object session) {
        return Runner.runAgent(requireTaskAgent("executeAgent"), inputs, session, null);
    }

    /**
     * Returns the per-Task agent bound by {@link #query}/{@link #streamQuery}
     * on the entering thread, failing loudly when the binding is missing.
     *
     * <p>A missing binding means the execution crossed a thread boundary inside
     * the parent template method (see the class-level thread contract). Falling
     * back to the shared singleton agent would silently break per-Task
     * isolation and reintroduce agent-core concurrency hazards, so the request
     * fails instead.
     *
     * @param executionPoint the calling template method, for diagnostics
     * @return the per-Task agent for the current request
     */
    private Object requireTaskAgent(String executionPoint) {
        Object agent = currentTaskAgent.get();
        if (agent == null) {
            throw new IllegalStateException("Per-Task agent binding lost at " + executionPoint
                    + ": execution thread differs from the query/streamQuery entry thread."
                    + " Refusing to fall back to the shared agent -- per-Task isolation would be broken.");
        }
        return agent;
    }

    /**
     * Installs remote A2A tools, intent-based tools, and SkillHub skills on the agent before execution.
     *
     * @param agent the agent instance to install tools and skills on
     */
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
            String conversationId = request.getConversationId();
            // Reuse cached agent from prepareTask() if available
            TaskAgentEntry cached = taskAgentCache.get(conversationId);
            if (cached != null) {
                return cached.agent();
            }
            // Fallback: no prepareTask() was called (e.g., non-orchestrator path)
            try {
                return agentManager.acquire(conversationId);
            } catch (ConversationBusyException ex) {
                throw new AgentExecutionException(
                        "Conversation busy: another task is still running for this conversation",
                        new AgentFailureDescriptor(CONVERSATION_BUSY, null, true), ex);
            }
        }
        return getAgent();
    }

    private void releaseAgentIfUnmanaged(ServeRequest request, Object agent) {
        // Only release agent if NOT managed by prepareTask/completeTask lifecycle
        String conversationId = request.getConversationId();
        if (agentManager != null && !taskAgentCache.containsKey(conversationId)) {
            agentManager.release(conversationId, agent);
        }
    }

    @Override
    public Optional<Object> prepareTask(ServeRequest request) {
        if (agentManager == null) {
            return Optional.empty(); // singleton mode, nothing to do
        }
        String conversationId = request.getConversationId();
        // A cache entry here belongs to another in-flight task for the same
        // conversation — treat as a conflict instead of silently sharing the agent.
        if (taskAgentCache.containsKey(conversationId)) {
            throw busyConversation(conversationId);
        }
        try {
            Object agent = agentManager.acquire(conversationId);
            // Fresh identity token: only the caller receiving it can release
            // this entry, so a rejected task's completeTask(Optional.empty())
            // never disturbs the agent of the in-flight task owning the cache slot.
            Object token = new Object();
            taskAgentCache.put(conversationId, new TaskAgentEntry(token, agent));
            return Optional.of(token);
        } catch (ConversationBusyException ex) {
            throw busyConversation(conversationId, ex);
        }
    }

    @Override
    public void completeTask(Optional<Object> taskToken) {
        if (agentManager == null || taskToken.isEmpty()) {
            return; // nothing acquired for this task (busy rejection or singleton mode)
        }
        Object token = taskToken.get();
        for (Map.Entry<String, TaskAgentEntry> entry : taskAgentCache.entrySet()) {
            TaskAgentEntry cached = entry.getValue();
            if (cached.token() != token) {
                continue;
            }
            // Release from the manager first so a concurrent acquire for this
            // conversation does not observe a stale busy state; removing the cache
            // entry afterwards only briefly blocks prepareTask (conservative).
            agentManager.release(entry.getKey(), cached.agent());
            taskAgentCache.remove(entry.getKey(), cached);
            return;
        }
        // Foreign or stale token — never release resources owned by another task.
        log.warn("completeTask token does not match any active task agent cache entry -- ignored");
    }

    private static AgentExecutionException busyConversation(String conversationId) {
        return busyConversation(conversationId, null);
    }

    private static AgentExecutionException busyConversation(String conversationId, ConversationBusyException cause) {
        return new AgentExecutionException(
                "Conversation busy: another task is still running for this conversation",
                new AgentFailureDescriptor(CONVERSATION_BUSY, null, true),
                cause != null ? cause
                        : new ConversationBusyException(
                                "Conversation already has an active agent: " + conversationId));
    }

    private static Object requireAgentInstance(Object agent) {
        Objects.requireNonNull(agent, "agent");
        if (agent instanceof String) {
            throw new IllegalArgumentException("JiuwenCoreAgentExtHandler requires an agent instance, not agent id");
        }
        return agent;
    }
}
