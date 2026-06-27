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

import java.util.ArrayList;
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
        VersatileRequestExtractor.RemoteRequest remoteRequest = extractor.extract(request);
        log.info("Resolved Versatile remote request conversation_id={} url={} headers={} params={} body_keys={}",
                request.getConversationId(), remoteRequest.url(),
                remoteRequest.headers().size(), remoteRequest.params().size(), remoteRequest.body().keySet());
        log.debug("Versatile remote request conversation_id={} request={}",
                request.getConversationId(), VersatileHttpClient.logRequest(remoteRequest, remoteRequest.url()));

        VersatileResponseExtractor responseExtractor = new VersatileResponseExtractor(properties.getResultNodeName());
        List<QueryChunk> chunks = new ArrayList<>();
        String[] lastEvent = new String[1];
        try {
            client.postStream(remoteRequest, line -> {
                String event = stripSsePrefix(line);
                if (event != null && !event.isBlank()) {
                    lastEvent[0] = event;
                }
                chunks.addAll(responseExtractor.consumeLine(line));
            });
            chunks.addAll(responseExtractor.finish());
        } catch (Exception exception) {
            log.error("Versatile invocation failed conversation_id={}", request.getConversationId(), exception);
            throw new IllegalStateException("Versatile invocation failed", exception);
        }

        Object content = null;
        for (QueryChunk chunk : chunks) {
            if (QueryChunk.TYPE_ERROR.equals(chunk.getType())) {
                log.error("Versatile query returned remote error conversation_id={} error={}",
                        request.getConversationId(), chunk.getData());
                throw new IllegalStateException(String.valueOf(chunk.getData()));
            }
            if (QueryChunk.TYPE_ANSWER.equals(chunk.getType()) && chunk.getData() != null) {
                content = chunk.getData();
            }
        }
        if (content == null) {
            content = lastEvent[0] != null ? lastEvent[0] : "";
            log.info("Versatile query finished without answer, using fallback content conversation_id={}",
                    request.getConversationId());
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("role", "assistant");
        result.put("content", String.valueOf(content));
        QueryResponse response = new QueryResponse(result, request.getConversationId());
        log.info("Completed Versatile query conversation_id={} content_present={}",
                request.getConversationId(), !String.valueOf(content).isEmpty());
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

    private List<QueryChunk> execute(ServeRequest request, QueryStreamObserver observer) {
        VersatileRequestExtractor.RemoteRequest remoteRequest = extractor.extract(request);
        log.info("Resolved Versatile remote request conversation_id={} url={} headers={} params={} body_keys={}",
                request.getConversationId(), remoteRequest.url(),
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
            List<QueryChunk> finalEvents = responseExtractor.finish();
            emit(finalEvents, observer);
            return finalEvents;
        } catch (CancellationException exception) {
            throw exception;
        } catch (Exception exception) {
            log.error("Versatile invocation failed conversation_id={}", request.getConversationId(), exception);
            throw new IllegalStateException("Versatile invocation failed", exception);
        }
    }

    private void emit(List<QueryChunk> chunks, QueryStreamObserver observer) {
        if (observer == null || chunks == null || chunks.isEmpty()) {
            return;
        }
        for (QueryChunk chunk : chunks) {
            if (observer.isCancelled()) {
                return;
            }
            observer.onNext(chunk);
        }
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

    private static String stripSsePrefix(String line) {
        if (line == null) {
            return null;
        }
        String trimmed = line.trim();
        if (trimmed.startsWith("data:")) {
            return trimmed.substring("data:".length()).trim();
        }
        if (trimmed.contains(":") && !trimmed.startsWith("{") && !trimmed.startsWith("[")) {
            return null;
        }
        return trimmed;
    }

}
