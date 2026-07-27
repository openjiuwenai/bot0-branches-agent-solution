/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.busconsumer;

import com.openjiuwen.service.spec.dto.QueryChunk;
import com.openjiuwen.service.spec.dto.QueryResponse;
import com.openjiuwen.service.spec.dto.ServeRequest;
import com.openjiuwen.service.spec.spi.AgentHandler;
import com.openjiuwen.service.spec.spi.QueryStreamObserver;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.util.Map;

/**
 * Standalone agent runtime used to verify FEAT-017 against a real agent-bus process.
 *
 * @since 2026-07-23
 */
@SpringBootApplication
public class AgentBusConsumerDemoApplication {
    public static void main(String[] args) {
        SpringApplication.run(AgentBusConsumerDemoApplication.class, args);
    }

    @Bean
    AgentHandler agentHandler() {
        return new AgentHandler() {
            @Override
            public QueryResponse query(ServeRequest request) {
                return new QueryResponse(responseData(request), request.getConversationId());
            }

            @Override
            public void streamQuery(ServeRequest request, QueryStreamObserver observer) {
                observer.onNext(new QueryChunk(QueryChunk.TYPE_CHUNK, responseData(request)));
                observer.onComplete();
            }
        };
    }

    private static Map<String, Object> responseData(ServeRequest request) {
        return Map.of("role", "assistant",
                "content", "FEAT-017 runtime received: " + request.lastUserQuery(),
                "tenantId", request.getTenantId() == null ? "" : request.getTenantId());
    }
}
