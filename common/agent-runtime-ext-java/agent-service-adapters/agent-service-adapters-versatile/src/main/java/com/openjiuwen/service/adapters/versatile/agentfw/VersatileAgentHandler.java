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
    private static final String INTERRUPT_MESSAGE = "Remote agent requires input";

    private final VersatileHttpClient client;
    private final VersatileRequestExtractor extractor;
    private final VersatileProperties properties;
    private final IntentAgentResolver agentResolver;

    public VersatileAgentHandler(VersatileProperties properties) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.client = new VersatileHttpClient(this.properties);
        this.extractor = new VersatileRequestExtractor(this.properties);
        this.agentResolver = new IntentAgentResolver(properties);
    }

    @Override
    public QueryResponse query(ServeRequest request) {
        log.info("Handling Versatile query conversation_id={} stream={} user_id={} tenant_id={} messages={}",
                request.getConversationId(), request.isStream(), request.getUserId(),
                request.getTenantId(), request.getMessages().size());
        log.debug("Versatile query request={}", logServeRequest(request));
        Map<String, Object> result = resolveQueryResult(request, invokeForQuery(request));
        QueryResponse response = new QueryResponse(result, request.getConversationId());
        log.info("Completed Versatile query conversation_id={} content_present={}",
                request.getConversationId(), result.get("content") != null
                        && !String.valueOf(result.get("content")).isEmpty());
        log.debug("Versatile query response conversation_id={} result={}",
                request.getConversationId(), result);
        return response;
    }

    private List<QueryChunk> invokeForQuery(ServeRequest request) {
        VersatileRequestExtractor.RemoteRequest remoteRequest = extractor.extract(request);
        logRemoteRequest("Resolved Versatile remote request", request, remoteRequest);
        VersatileResponseExtractor responseExtractor = new VersatileResponseExtractor(properties, agentResolver);
        List<QueryChunk> chunks = new ArrayList<>();
        try {
            client.postStream(remoteRequest, line -> {
                chunks.addAll(responseExtractor.consumeLine(line));
            });
            chunks.addAll(responseExtractor.finish());
        } catch (IOException | InterruptedException | RuntimeException exception) {
            log.error("Versatile invocation failed conversation_id={}", request.getConversationId(), exception);
            throw new IllegalStateException("Versatile invocation failed", exception);
        }
        return chunks;
    }

    private Map<String, Object> resolveQueryResult(ServeRequest request, List<QueryChunk> chunks) {
        boolean isInterrupted = false;
        Map<String, Object> interruptPayload = null;
        List<String> interruptMessages = new ArrayList<>();
        Map<String, Object> a2aDelegatePayload = null;
        Object legacyAnswerContent = null;
        for (QueryChunk chunk : chunks) {
            if (QueryChunk.TYPE_ERROR.equals(chunk.getType())) {
                log.error("Versatile query returned remote error conversation_id={} error={}",
                        request.getConversationId(), chunk.getData());
                throw new IllegalStateException(String.valueOf(chunk.getData()));
            }
            if (QueryChunk.TYPE_INTERRUPT.equals(chunk.getType())) {
                if (chunk.getData() instanceof Map<?, ?> m && isA2aDelegate(m)) {
                    a2aDelegatePayload = copyToStringMap(m);
                    continue;
                }
                isInterrupted = true;
                interruptPayload = chunk.getData() instanceof Map<?, ?> m
                        ? copyToStringMap(m) : null;
                continue;
            }
            Optional<Map<String, Object>> envelope = answerEnvelope(chunk.getData());
            if (envelope.isPresent()) {
                Object content = envelope.get().get("response_content");
                if (content == null) {
                    content = envelope.get().get("output");
                }
                legacyAnswerContent = content;
                continue;
            }
            interruptMessages.add(String.valueOf(chunk.getData()));
        }
        if (a2aDelegatePayload != null) {
            return a2aDelegateResult(request, a2aDelegatePayload);
        }
        if (legacyAnswerContent != null) {
            return assistantResult(legacyAnswerContent);
        }
        if (isInterrupted) {
            Map<String, Object> result = assistantResult(
                    interruptPayload != null && interruptPayload.get("message") != null
                            ? String.valueOf(interruptPayload.get("message"))
                            : interruptMessage(interruptMessages));
            if (interruptPayload != null) {
                result.put("_interrupt", interruptPayload);
            }
            log.info("Versatile query returned interrupt conversation_id={}", request.getConversationId());
            return result;
        }
        return assistantResult(interruptMessage(interruptMessages));
    }

    private static boolean isA2aDelegate(Map<?, ?> data) {
        Object context = data.get("context");
        if (!(context instanceof Map<?, ?> ctx)) {
            return false;
        }
        return "a2a_delegate".equals(ctx.get("_interrupt_kind"));
    }

    private static Map<String, Object> a2aDelegateResult(ServeRequest request, Map<String, Object> payload) {
        Object rc = payload.get("responseContent");
        String responseContent = rc instanceof String s ? s : "";
        Map<String, Object> result = assistantResult(responseContent);
        Map<String, Object> interrupt = new LinkedHashMap<>(payload);
        interrupt.put("message", request.lastUserQuery());
        interrupt.put("_stream_mode", request.isStream() ? "sse" : "");
        result.put("_interrupt", interrupt);
        return result;
    }

    private static Optional<Map<String, Object>> answerEnvelope(Object data) {
        if (!(data instanceof Map<?, ?> envelope) || !"answer".equals(envelope.get("type"))) {
            return Optional.empty();
        }
        if (envelope.get("response_content") == null && envelope.get("output") == null) {
            return Optional.empty();
        }
        Map<String, Object> typed = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : envelope.entrySet()) {
            typed.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return Optional.of(typed);
    }

    private static Map<String, Object> copyToStringMap(Map<?, ?> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            if (entry.getKey() != null) {
                result.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        return result;
    }

    private static String interruptMessage(List<String> messages) {
        String joined = String.join("\n", messages.stream()
                .filter(message -> message != null && !message.isBlank())
                .distinct()
                .toList());
        return joined.isBlank() ? INTERRUPT_MESSAGE : joined;
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
        VersatileResponseExtractor responseExtractor = new VersatileResponseExtractor(properties, agentResolver);
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
            List<QueryChunk> finalEvents = enrichDelegateInterrupts(responseExtractor.finish(), request);
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

    /**
     * Enriches {@code a2a_delegate} interrupt chunks produced by the extractor
     * with {@code message} (from the current request's last user query) and
     * {@code _stream_mode}. The extractor cannot supply these because it only
     * sees SSE lines; the handler has the {@link ServeRequest}. Without this
     * enrichment, the orchestrator's streaming recursive-forward path would
     * lack the user query to forward to the next layer.
     *
     * @param chunks the chunks emitted by the response extractor before completion
     * @param request the current serve request, source of the user query and stream mode
     * @return the enriched chunk list (same size as the input when non-empty)
     */
    private static List<QueryChunk> enrichDelegateInterrupts(List<QueryChunk> chunks, ServeRequest request) {
        if (chunks == null || chunks.isEmpty()) {
            return chunks;
        }
        List<QueryChunk> result = new ArrayList<>(chunks.size());
        for (QueryChunk chunk : chunks) {
            if (QueryChunk.TYPE_INTERRUPT.equals(chunk.getType()) && chunk.getData() instanceof Map<?, ?> m
                    && isA2aDelegate(m)) {
                Map<String, Object> enriched = copyToStringMap(m);
                enriched.put("message", request.lastUserQuery());
                enriched.put("_stream_mode", request.isStream() ? "sse" : "");
                result.add(new QueryChunk(QueryChunk.TYPE_INTERRUPT, enriched));
            } else {
                result.add(chunk);
            }
        }
        return result;
    }

    private Map<String, Object> logServeRequest(ServeRequest request) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("conversation_id", request.getConversationId());
        data.put("stream", request.isStream());
        data.put("user_id", request.getUserId());
        data.put("space_id", request.getSpaceId());
        data.put("tenant_id", request.getTenantId());
        if (properties.isLogMaskSensitive()) {
            data.put("messages", maskMessages(request.getMessages()));
            data.put("metadata", maskMetadata(request.getMetadata()));
        } else {
            data.put("messages", request.getMessages());
            data.put("metadata", request.getMetadata());
        }
        return data;
    }

    private static List<Map<String, Object>> maskMessages(List<Map<String, Object>> messages) {
        if (messages == null) {
            return List.of();
        }
        List<Map<String, Object>> masked = new ArrayList<>(messages.size());
        for (Map<String, Object> msg : messages) {
            Map<String, Object> copy = new LinkedHashMap<>(msg);
            Object content = copy.get("content");
            if (content != null) {
                copy.put("content", "***masked***");
            }
            masked.add(copy);
        }
        return masked;
    }

    private static Map<String, Object> maskMetadata(Map<String, Object> metadata) {
        if (metadata == null) {
            return Map.of();
        }
        Map<String, Object> masked = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : metadata.entrySet()) {
            masked.put(entry.getKey(), "***masked***");
        }
        return masked;
    }

    private void logRemoteRequest(
            String message, ServeRequest request, VersatileRequestExtractor.RemoteRequest remoteRequest) {
        log.info("{} conversation_id={} url={} headers={} params={} body_keys={}",
                message, request.getConversationId(), remoteRequest.url(),
                remoteRequest.headers().size(), remoteRequest.params().size(), remoteRequest.body().keySet());
        if (properties.isLogMaskSensitive()) {
            log.debug("Versatile remote request conversation_id={} url={} headers_keys={} params_keys={} body_keys={}",
                    request.getConversationId(), remoteRequest.url(),
                    remoteRequest.headers().keySet(), remoteRequest.params().keySet(), remoteRequest.body().keySet());
        } else {
            log.debug("Versatile remote request conversation_id={} request={}",
                    request.getConversationId(), VersatileHttpClient.logRequest(remoteRequest, remoteRequest.url()));
        }
    }
}
