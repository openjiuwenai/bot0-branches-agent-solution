/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.ext.agentfw;

import com.openjiuwen.service.adapters.agentcore.agentfw.JiuwenCoreAgentHandler;
import com.openjiuwen.service.adapters.agentcore.ext.external.ClientToolRail;
import com.openjiuwen.service.adapters.agentcore.ext.external.RemoteA2aToolInstaller;
import com.openjiuwen.service.adapters.agentcore.ext.intent.IntentDeepAgentInstaller;
import com.openjiuwen.service.adapters.agentcore.ext.middleware.otel.OtelRailBinding;
import com.openjiuwen.service.adapters.agentcore.ext.middleware.otel.OtelRuntimeSupport;
import com.openjiuwen.service.adapters.agentcore.ext.middleware.skillhub.SkillHubManager;
import com.openjiuwen.service.adapters.agentcore.external.ExternalSvcAdapterRegistrar;
import com.openjiuwen.service.adapters.agentcore.middleware.MiddlewareAdapterRegistrar;
import com.openjiuwen.service.spec.dto.QueryResponse;
import com.openjiuwen.service.spec.dto.ServeRequest;
import com.openjiuwen.service.spec.spi.QueryStreamObserver;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Objects;

/**
 * AgentCore handler extension that installs remote A2A tools and SkillHub skills before execution.
 *
 * @since 2026-06-30
 */
public class JiuwenCoreAgentExtHandler extends JiuwenCoreAgentHandler {
    private static final Logger log = LoggerFactory.getLogger(JiuwenCoreAgentExtHandler.class);

    private RemoteA2aToolInstaller remoteToolInstaller = RemoteA2aToolInstaller.noop();
    private IntentDeepAgentInstaller intentInstaller;
    private SkillHubManager skillHubManager;

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
        installBeforeRun();
        OtelRailBinding otelBinding = bindOtel(request);
        try (var binding = ClientToolRail.bind(getAgent(), request)) {
            super.streamQuery(request, observer);
        } finally {
            closeOtelQuietly(otelBinding);
        }
    }

    @Override
    public QueryResponse query(ServeRequest request) {
        installBeforeRun();
        OtelRailBinding otelBinding = bindOtel(request);
        try (var binding = ClientToolRail.bind(getAgent(), request)) {
            return super.query(request);
        } finally {
            closeOtelQuietly(otelBinding);
        }
    }

    private void closeOtelQuietly(OtelRailBinding otelBinding) {
        try {
            otelBinding.close();
        } catch (IllegalStateException | IllegalArgumentException | UnsupportedOperationException ex) {
            log.warn("OTel binding close failed reason={}", ex.getMessage());
        }
    }

    private OtelRailBinding bindOtel(ServeRequest request) {
        // 同模块 middleware/otel：未启用时 OtelRuntimeSupport 内部返回 no-op 绑定
        return OtelRuntimeSupport.bindRequest(getAgent(), request.getConversationId());
    }

    private void installBeforeRun() {
        if (intentInstaller == null) {
            remoteToolInstaller.install(getAgent());
        } else {
            intentInstaller.install(getAgent());
            if (intentInstaller.exposeAgentCardTools()) {
                remoteToolInstaller.install(getAgent());
            }
        }
        if (skillHubManager != null) {
            skillHubManager.register(getAgent());
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
