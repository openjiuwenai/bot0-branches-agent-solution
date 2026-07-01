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

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;

/**
 * Agent handler that forwards service requests to a Versatile-compatible HTTP endpoint.
 *
 * @since 2026-06-30
 */
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
        QueryInvocationResult invocationResult = invokeForQuery(request);
        Object content = resolveQueryContent(request, invocationResult);
        Map<String, Object> result = assistantResult(content);
        QueryResponse response = new QueryResponse(result, request.getConversationId());
        log.info("Completed Versatile query conversation_id={} content_present={}",
                request.getConversationId(), !String.valueOf(content).isEmpty());
        log.debug("Versatile query response conversation_id={} result={}",
                request.getConversationId(), result);
        return response;
    }

    private QueryInvocationResult invokeForQuery(ServeRequest request) {
        VersatileRequestExtractor.RemoteRequest remoteRequest = extractor.extract(request);
        logRemoteRequest("Resolved Versatile remote request", request, remoteRequest);
        VersatileResponseExtractor responseExtractor = new VersatileResponseExtractor(properties.getResultNodeName());
        List<QueryChunk> chunks = new ArrayList<>();
        LastEventHolder lastEvent = new LastEventHolder();
        try {
            client.postStream(remoteRequest, line -> {
                stripSsePrefix(line).filter(event -> !event.isBlank()).ifPresent(lastEvent::set);
                chunks.addAll(responseExtractor.consumeLine(line));
            });
            chunks.addAll(responseExtractor.finish());
        } catch (IOException | InterruptedException | RuntimeException exception) {
            log.error("Versatile invocation failed conversation_id={}", request.getConversationId(), exception);
            throw new IllegalStateException("Versatile invocation failed", exception);
        }
        return new QueryInvocationResult(chunks, lastEvent.value());
    }

    private Object resolveQueryContent(ServeRequest request, QueryInvocationResult invocationResult) {
        Optional<Object> answer = Optional.empty();
        for (QueryChunk chunk : invocationResult.chunks()) {
            if (QueryChunk.TYPE_ERROR.equals(chunk.getType())) {
                log.error("Versatile query returned remote error conversation_id={} error={}",
                        request.getConversationId(), chunk.getData());
                throw new IllegalStateException(String.valueOf(chunk.getData()));
            }
            if (QueryChunk.TYPE_ANSWER.equals(chunk.getType()) && chunk.getData() != null) {
                answer = Optional.of(chunk.getData());
            }
        }
        if (answer.isPresent()) {
            return answer.get();
        }
        log.info("Versatile query finished without answer, using fallback content conversation_id={}",
                request.getConversationId());
        return invocationResult.lastEvent().orElse("");
    }

    private static Map<String, Object> assistantResult(Object content) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("role", "assistant");
        result.put("content", String.valueOf(content));
        return result;
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
        } catch (RuntimeException exception) {
            log.error("Versatile streamQuery failed conversation_id={}", request.getConversationId(), exception);
            observer.onError(exception);
        }
    }

    private List<QueryChunk> execute(ServeRequest request, QueryStreamObserver observer) {
        VersatileRequestExtractor.RemoteRequest remoteRequest = extractor.extract(request);
        logRemoteRequest("Resolved Versatile remote request", request, remoteRequest);
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
        } catch (IOException | InterruptedException | RuntimeException exception) {
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

    private void logRemoteRequest(
            String message, ServeRequest request, VersatileRequestExtractor.RemoteRequest remoteRequest) {
        log.info("{} conversation_id={} url={} headers={} params={} body_keys={}",
                message, request.getConversationId(), remoteRequest.url(),
                remoteRequest.headers().size(), remoteRequest.params().size(), remoteRequest.body().keySet());
        log.debug("Versatile remote request conversation_id={} request={}",
                request.getConversationId(), VersatileHttpClient.logRequest(remoteRequest, remoteRequest.url()));
    }

    private static Optional<String> stripSsePrefix(String line) {
        if (line == null) {
            return Optional.empty();
        }
        String trimmed = line.trim();
        if (trimmed.startsWith("data:")) {
            return Optional.of(trimmed.substring("data:".length()).trim());
        }
        if (trimmed.contains(":") && !trimmed.startsWith("{") && !trimmed.startsWith("[")) {
            return Optional.empty();
        }
        return Optional.of(trimmed);
    }

    private record QueryInvocationResult(List<QueryChunk> chunks, Optional<String> lastEvent) {
    }

    private static final class LastEventHolder {
        private String value;

        private void set(String value) {
            this.value = value;
        }

        private Optional<String> value() {
            return Optional.ofNullable(value);
        }
    }
}
