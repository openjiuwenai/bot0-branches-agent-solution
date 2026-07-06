/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.ext.agentfw;

import com.openjiuwen.service.adapters.agentcore.agentfw.JiuwenCoreAgentHandler;
import com.openjiuwen.service.adapters.agentcore.ext.external.RemoteA2aToolInstaller;
import com.openjiuwen.service.adapters.agentcore.external.ExternalSvcAdapterRegistrar;
import com.openjiuwen.service.adapters.agentcore.middleware.MiddlewareAdapterRegistrar;
import com.openjiuwen.service.spec.dto.QueryResponse;
import com.openjiuwen.service.spec.dto.ServeRequest;
import com.openjiuwen.service.spec.spi.QueryStreamObserver;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Objects;

/**
 * AgentCore handler extension that installs remote A2A tools before execution.
 *
 * @since 2026-06-30
 */
public class JiuwenCoreAgentExtHandler extends JiuwenCoreAgentHandler {
    private RemoteA2aToolInstaller remoteToolInstaller = RemoteA2aToolInstaller.noop();

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
    }

    private static Object requireAgentInstance(Object agent) {
        Objects.requireNonNull(agent, "agent");
        if (agent instanceof String) {
            throw new IllegalArgumentException("JiuwenCoreAgentExtHandler requires an agent instance, not agent id");
        }
        return agent;
    }
}
