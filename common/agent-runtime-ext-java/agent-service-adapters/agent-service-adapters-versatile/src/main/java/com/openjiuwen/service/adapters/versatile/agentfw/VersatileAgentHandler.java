/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.versatile.agentfw;

import com.openjiuwen.service.adapters.versatile.autoconfigure.VersatileProperties;
import com.openjiuwen.service.spec.dto.QueryChunk;
import com.openjiuwen.service.spec.dto.QueryResponse;
import com.openjiuwen.service.spec.dto.ServeRequest;
import com.openjiuwen.service.spec.spi.AgentHandler;
import com.openjiuwen.service.spec.spi.QueryStreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;

public class VersatileAgentHandler implements AgentHandler {

    private static final Logger log = LoggerFactory.getLogger(VersatileAgentHandler.class);

    private final VersatileHttpClient client;
    private final VersatileRequestExtractor extractor;
    private final VersatileProperties properties;

    public VersatileAgentHandler(VersatileProperties properties) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.client = new VersatileHttpClient(this.properties);
        this.extractor = new VersatileRequestExtractor(this.properties);
    }

    @Override
    public QueryResponse query(ServeRequest request) {
        log.info("Handling Versatile query conversation_id={} stream={} user_id={} tenant_id={} messages={}",
                request.getConversationId(), request.isStream(), request.getUserId(),
                request.getTenantId(), request.getMessages().size());
        log.debug("Versatile query request={}", logServeRequest(request));
        VersatileResponseExtractor responseExtractor = execute(request, null);
        if (responseExtractor.error() != null) {
            log.error("Versatile query returned remote error conversation_id={} error={}",
                    request.getConversationId(), responseExtractor.error());
            throw new IllegalStateException(responseExtractor.error());
        }
        Object result = responseExtractor.completed() ? responseExtractor.result() : null;
        QueryResponse response = new QueryResponse(result, request.getConversationId());
        log.info("Completed Versatile query conversation_id={} result_present={}",
                request.getConversationId(), result != null);
        log.debug("Versatile query response conversation_id={} result={}",
                request.getConversationId(), result);
        return response;
    }

    @Override
    public void streamQuery(ServeRequest request, QueryStreamObserver observer) {
        log.info("Handling Versatile streamQuery conversation_id={} stream={} user_id={} tenant_id={} messages={}",
                request.getConversationId(), request.isStream(), request.getUserId(),
                request.getTenantId(), request.getMessages().size());
        log.debug("Versatile streamQuery request={}", logServeRequest(request));
        try {
            execute(request, observer);
            if (observer.isCancelled()) {
                log.warn("Versatile streamQuery cancelled conversation_id={}", request.getConversationId());
                return;
            }
            observer.onComplete();
            log.info("Completed Versatile streamQuery conversation_id={}", request.getConversationId());
        } catch (CancellationException ignored) {
            // Observer cancellation is a normal stream termination path.
            log.warn("Versatile streamQuery cancelled conversation_id={}", request.getConversationId());
        } catch (Exception exception) {
            log.error("Versatile streamQuery failed conversation_id={}", request.getConversationId(), exception);
            observer.onError(exception);
        }
    }

    private VersatileResponseExtractor execute(ServeRequest request, QueryStreamObserver observer) {
        VersatileRequestExtractor.RemoteRequest remoteRequest = extractor.extract(request);
        log.info("Resolved Versatile remote request conversation_id={} intent={} url={} headers={} params={} body_keys={}",
                request.getConversationId(), remoteRequest.intent(), remoteRequest.url(),
                remoteRequest.headers().size(), remoteRequest.params().size(), remoteRequest.body().keySet());
        log.debug("Versatile remote request conversation_id={} request={}",
                request.getConversationId(), VersatileHttpClient.logRequest(remoteRequest, remoteRequest.url()));
        VersatileResponseExtractor responseExtractor = new VersatileResponseExtractor(properties.getResultNodeName());
        try {
            client.postStream(remoteRequest, line -> {
                if (observer != null && observer.isCancelled()) {
                    throw new CancellationException();
                }
                emit(responseExtractor.consumeLine(line), observer);
                if (observer != null && observer.isCancelled()) {
                    throw new CancellationException();
                }
            });
            if (observer != null && observer.isCancelled()) {
                throw new CancellationException();
            }
            emit(responseExtractor.finish(), observer);
            return responseExtractor;
        } catch (CancellationException exception) {
            throw exception;
        } catch (Exception exception) {
            log.error("Versatile invocation failed conversation_id={}", request.getConversationId(), exception);
            throw new IllegalStateException("Versatile invocation failed", exception);
        }
    }

    private void emit(List<VersatileResponseExtractor.Event> events, QueryStreamObserver observer) {
        if (observer == null || events == null || events.isEmpty()) {
            return;
        }
        for (VersatileResponseExtractor.Event event : events) {
            if (observer.isCancelled()) {
                return;
            }
            observer.onNext(toChunk(event));
        }
    }

    private QueryChunk toChunk(VersatileResponseExtractor.Event event) {
        return switch (event.type()) {
            case PASSTHROUGH -> new QueryChunk("chunk", event.data());
            case INPUT_REQUIRED -> new QueryChunk("input_required", event.data());
            case COMPLETED -> new QueryChunk("completed", event.data());
            case FAILED -> new QueryChunk("error", event.data());
        };
    }

    private static Map<String, Object> logServeRequest(ServeRequest request) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("conversation_id", request.getConversationId());
        data.put("stream", request.isStream());
        data.put("user_id", request.getUserId());
        data.put("space_id", request.getSpaceId());
        data.put("tenant_id", request.getTenantId());
        data.put("messages", request.getMessages());
        data.put("metadata", request.getMetadata());
        return data;
    }

}
