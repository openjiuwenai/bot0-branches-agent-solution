/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.ext.middleware.otel;

import com.openjiuwen.core.runner.Runner;
import com.openjiuwen.service.adapters.agentcore.agentfw.JiuwenCoreAgentHandler;
import com.openjiuwen.service.adapters.agentcore.external.ExternalSvcAdapterRegistrar;
import com.openjiuwen.service.adapters.agentcore.middleware.MiddlewareAdapterRegistrar;
import com.openjiuwen.service.spec.dto.QueryResponse;
import com.openjiuwen.service.spec.dto.ServeRequest;
import com.openjiuwen.service.spec.spi.QueryStreamObserver;

/**
 * AgentHandler enhancement for the default (agentcore) path: wraps each execution with a
 * per-request trajectory rail binding. Declared before the default auto-configuration so
 * the stock handler backs off via {@code @ConditionalOnMissingBean}.
 *
 * @since 2026-08-07
 */
public class OtelJiuwenCoreAgentHandler extends JiuwenCoreAgentHandler {

    public OtelJiuwenCoreAgentHandler(Object agent, MiddlewareAdapterRegistrar middlewareAdapterRegistrar,
                                      ExternalSvcAdapterRegistrar externalSvcAdapterRegistrar) {
        super(agent, middlewareAdapterRegistrar, externalSvcAdapterRegistrar);
    }

    @Override
    public void streamQuery(ServeRequest request, QueryStreamObserver observer) {
        try (OtelRailBinding binding = OtelRuntimeSupport.bindRequest(
                resolveAgentInstance(), request.getConversationId())) {
            super.streamQuery(request, observer);
        }
    }

    @Override
    public QueryResponse query(ServeRequest request) {
        try (OtelRailBinding binding = OtelRuntimeSupport.bindRequest(
                resolveAgentInstance(), request.getConversationId())) {
            return super.query(request);
        }
    }

    private Object resolveAgentInstance() {
        Object candidate = getAgent();
        if (candidate instanceof String agentId) {
            return Runner.resourceMgr().getAgent(agentId);
        }
        return candidate;
    }
}
