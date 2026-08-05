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
import java.util.concurrent.TimeUnit;

/**
 * Standalone agent runtime used to verify FEAT-017 against a real agent-bus process.
 *
 * @since 2026-07-23
 */
@SpringBootApplication
public class AgentBusConsumerDemoApplication {
    private static final int STREAM_CHUNK_COUNT = 10;

    private static final long STREAM_CHUNK_INTERVAL_SECONDS = 1L;

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
                try {
                    for (int chunkIndex = 1; chunkIndex <= STREAM_CHUNK_COUNT; chunkIndex++) {
                        if (observer.isCancelled()) {
                            return;
                        }
                        observer.onNext(new QueryChunk(QueryChunk.TYPE_CHUNK, streamResponseData(request, chunkIndex)));
                        TimeUnit.SECONDS.sleep(STREAM_CHUNK_INTERVAL_SECONDS);
                    }
                    observer.onComplete();
                } catch (InterruptedException exception) {
                    observer.onError(exception);
                } catch (RuntimeException exception) {
                    observer.onError(exception);
                }
            }
        };
    }

    private static Map<String, Object> responseData(ServeRequest request) {
        return Map.of("role", "assistant",
                "content", "FEAT-017 runtime received: " + request.lastUserQuery(),
                "tenantId", request.getTenantId() == null ? "" : request.getTenantId());
    }

    private static Map<String, Object> streamResponseData(ServeRequest request, int chunkIndex) {
        return Map.of("role", "assistant",
                "content", "FEAT-017 runtime stream chunk " + chunkIndex + ": " + request.lastUserQuery(),
                "chunkIndex", chunkIndex,
                "chunkCount", STREAM_CHUNK_COUNT,
                "tenantId", request.getTenantId() == null ? "" : request.getTenantId());
    }
}
