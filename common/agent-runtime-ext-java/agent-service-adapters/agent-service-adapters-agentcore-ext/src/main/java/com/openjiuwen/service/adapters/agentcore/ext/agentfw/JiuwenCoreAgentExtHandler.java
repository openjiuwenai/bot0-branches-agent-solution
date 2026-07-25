/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.ext.agentfw;

import com.openjiuwen.service.adapters.agentcore.agentfw.JiuwenCoreAgentHandler;
import com.openjiuwen.service.adapters.agentcore.ext.external.RemoteA2aToolInstaller;
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
 * AgentCore handler extension that installs remote A2A tools and SkillHub
 * skills before execution.
 *
 * @since 2026-06-30
 */
public class JiuwenCoreAgentExtHandler extends JiuwenCoreAgentHandler {
    private static final Logger log = LoggerFactory.getLogger(JiuwenCoreAgentExtHandler.class);

    private RemoteA2aToolInstaller remoteToolInstaller = RemoteA2aToolInstaller.noop();
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

    /**
     * Inject the SkillHubManager when the SkillHub chain is active (enabled=true
     * and provider present). Null when inactive - handler runs without skills.
     *
     * @param skillHubManager the SkillHub manager bean, or null when middleware is inactive
     */
    @Autowired(required = false)
    void setSkillHubManager(SkillHubManager skillHubManager) {
        this.skillHubManager = skillHubManager;
    }

    @Override
    public void start() {
        if (skillHubManager != null) {
            // provider.start() config/auth failures propagate (fail fast).
            // download/integrity-check failures are degraded + retried in background
            // inside Manager.start() and never reach here.
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
        super.streamQuery(request, observer);
    }

    @Override
    public QueryResponse query(ServeRequest request) {
        installBeforeRun();
        return super.query(request);
    }

    private void installBeforeRun() {
        remoteToolInstaller.install(getAgent());
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
