/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.ext.agentfw;

import com.openjiuwen.service.adapters.agentcore.agentfw.JiuwenCoreAgentHandler;
import com.openjiuwen.service.adapters.agentcore.ext.external.RemoteA2aToolInstaller;
import com.openjiuwen.service.adapters.agentcore.middleware.MiddlewareAdapterRegistrar;
import com.openjiuwen.service.spec.dto.QueryResponse;
import com.openjiuwen.service.spec.dto.ServeRequest;
import com.openjiuwen.service.spec.spi.QueryStreamObserver;

import java.util.Objects;

/**
 * AgentCore handler extension that installs remote A2A tools before execution.
 *
 * @since 2026-06-30
 */
public class JiuwenCoreAgentExtHandler extends JiuwenCoreAgentHandler {
    private final RemoteA2aToolInstaller remoteToolInstaller;

    public JiuwenCoreAgentExtHandler(Object agent, MiddlewareAdapterRegistrar middlewareAdapterRegistrar,
                                     RemoteA2aToolInstaller remoteToolInstaller) {
        super(agent, middlewareAdapterRegistrar);
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
}
