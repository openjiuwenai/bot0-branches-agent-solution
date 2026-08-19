/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.versatile.controller.handoff;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openjiuwen.service.adapters.versatile.agentfw.IntentAgentResolver;
import com.openjiuwen.service.adapters.versatile.agentfw.VersatileHttpClient;
import com.openjiuwen.service.adapters.versatile.agentfw.VersatileRequestExtractor;
import com.openjiuwen.service.adapters.versatile.agentfw.VersatileResponseExtractor;
import com.openjiuwen.service.adapters.versatile.autoconfigure.VersatileProperties;
import com.openjiuwen.service.adapters.versatile.controller.handoff.autoconfigure.ControllerHandoffProperties;
import com.openjiuwen.service.spec.dto.QueryChunk;
import com.openjiuwen.service.spec.dto.QueryResponse;
import com.openjiuwen.service.spec.dto.ServeRequest;
import com.openjiuwen.service.spec.spi.AgentHandler;
import com.openjiuwen.service.spec.spi.QueryStreamObserver;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * AgentHandler that proxies a Versatile controller (FEAT-002 baseline behavior) and
 * additionally consumes controller intent-handoff messages: classification strictly
 * precedes the baseline error mapping; a hit suppresses the line from the baseline
 * extractor and routes through the handoff executor (spec 3.1-3.7/4.2). Never emits
 * a "COMPLETED" QueryChunk.
 *
 * @since 2026-08-19
 */
public class ControllerHandoffAgentHandler implements AgentHandler {
    private static final Logger log = LoggerFactory.getLogger(ControllerHandoffAgentHandler.class);
    private static final String INTERRUPT_MESSAGE = "Remote agent requires input";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final VersatileProperties properties;
    private final VersatileHttpClient client;
    private final VersatileRequestExtractor requestExtractor;
    private final IntentHandoffClassifier handoffClassifier;
    private final HandoffLoopGuard loopGuard;
    private final ObjectProvider<ControllerHandoffExecutor> executorProvider;
    private final ControllerHandoffProperties handoffProperties;

    public ControllerHandoffAgentHandler(VersatileProperties versatileProperties,
            IntentHandoffClassifier handoffClassifier,
            HandoffLoopGuard loopGuard,
            ObjectProvider<ControllerHandoffExecutor> executorProvider,
            ControllerHandoffProperties handoffProperties) {
        this.properties = Objects.requireNonNull(versatileProperties, "versatileProperties");
        this.client = new VersatileHttpClient(this.properties);
        this.requestExtractor = new VersatileRequestExtractor(this.properties);
        this.handoffClassifier = handoffClassifier;
        this.loopGuard = loopGuard;
        this.executorProvider = executorProvider;
        this.handoffProperties = handoffProperties;
    }

    /** 单次控制器执行的结果：基线终态事件 + 首个转调命中（可空）。 */
    private record ControllerExecution(List<QueryChunk> finalEvents, HandoffClassification handoffHit) {
    }

    @Override
    public QueryResponse query(ServeRequest request) {
        log.info("Handling controller-handoff query conversation_id={} stream={} user_id={} tenant_id={} messages={}",
                request.getConversationId(), request.isStream(), request.getUserId(),
                request.getTenantId(), request.getMessages().size());
        HandoffLoopGuard.GuardResult inbound = loopGuard.checkInbound(request);
        if (inbound != HandoffLoopGuard.GuardResult.ALLOW) {
            throw new IllegalStateException(handoffErrorCode(inbound)
                    + " inbound rejected conversation_id=" + request.getConversationId());
        }
        BufferingObserver buffer = new BufferingObserver();
        List<QueryChunk> finalEvents = runHandoffChain(request, buffer);
        if (buffer.error != null) {
            throw new IllegalStateException(String.valueOf(buffer.lastErrorPayload), buffer.error);
        }
        if (finalEvents != null) {
            return new QueryResponse(resolveQueryResult(request, finalEvents),
                    request.getConversationId());
        }
        // 转调/信号路径：executor 或信号已驱动 observer，聚合其内容作为最终应答
        return new QueryResponse(assistantResult(buffer.joinedContent()), request.getConversationId());
    }

    @Override
    public void streamQuery(ServeRequest request, QueryStreamObserver observer) {
        log.info("Handling controller-handoff streamQuery conversation_id={} stream={} user_id={} tenant_id={} messages={}",
                request.getConversationId(), request.isStream(), request.getUserId(),
                request.getTenantId(), request.getMessages().size());
        HandoffLoopGuard.GuardResult inbound = loopGuard.checkInbound(request);
        if (inbound != HandoffLoopGuard.GuardResult.ALLOW) {
            emitHandoffError(observer, handoffErrorCode(inbound),
                    "inbound route-trace rejected: " + inbound
                            + " conversation_id=" + request.getConversationId());
            return;
        }
        try {
            List<QueryChunk> finalEvents = runHandoffChain(request, observer);
            if (observer.isCancelled()) {
                log.warn("controller-handoff streamQuery cancelled conversation_id={}",
                        request.getConversationId());
                return;
            }
            if (finalEvents == null) {
                return; // 转调/信号/错误路径已驱动 observer 到终态
            }
            Optional<QueryChunk> terminalError = finalEvents.stream()
                    .filter(c -> QueryChunk.TYPE_ERROR.equals(c.getType()))
                    .findFirst();
            if (terminalError.isPresent()) {
                log.error("controller-handoff streamQuery ended with error terminal conversation_id={} error={}",
                        request.getConversationId(), terminalError.get().getData());
                observer.onError(new IllegalStateException(String.valueOf(terminalError.get().getData())));
                return;
            }
            observer.onComplete();
            log.info("Completed controller-handoff streamQuery conversation_id={}",
                    request.getConversationId());
        } catch (CancellationException ignored) {
            log.warn("controller-handoff streamQuery cancelled conversation_id={}",
                    request.getConversationId());
        } catch (RuntimeException exception) {
            log.error("controller-handoff streamQuery failed conversation_id={}",
                    request.getConversationId(), exception);
            observer.onError(exception);
        }
    }

    /**
     * 消费控制器 SSE 流。逐行先分类：命中转调的行被抑制（不进基线 extractor，不会被
     * 映射为 TYPE_CHUNK/TYPE_INTERRUPT/TYPE_ERROR —— 识别先于异常映射，spec 2.2），
     * 未命中行走基线 consumeLine。有命中时基线 finish() 终态事件整组抑制；
     * 无命中时与基线 VersatileAgentHandler.execute 行为一致。
     */
    private ControllerExecution execute(ServeRequest request, QueryStreamObserver observer) {
        VersatileRequestExtractor.RemoteRequest remoteRequest = requestExtractor.extract(request);
        log.info("Resolved controller remote request conversation_id={} url={}",
                request.getConversationId(), remoteRequest.url());
        VersatileResponseExtractor responseExtractor =
                new VersatileResponseExtractor(properties, new IntentAgentResolver(properties));
        List<QueryChunk> collected = new ArrayList<>();
        AtomicReference<HandoffClassification> hit = new AtomicReference<>();
        try {
            client.postStream(remoteRequest, line -> {
                if (observer != null && observer.isCancelled()) {
                    throw new CancellationException();
                }
                if (hit.get() != null) {
                    return; // 已命中转调：控制器剩余输出全部抑制（执行已移交下游）
                }
                HandoffClassification classification = handoffClassifier.classify(line);
                if (classification.outcome() != HandoffClassification.Outcome.NOT_HANDOFF) {
                    hit.compareAndSet(null, classification);
                    return; // 抑制转调信号
                }
                List<QueryChunk> produced = responseExtractor.consumeLine(line);
                if (observer != null) {
                    emit(produced, observer);
                } else {
                    collected.addAll(produced);
                }
                if (observer != null && observer.isCancelled()) {
                    throw new CancellationException();
                }
            });
            if (observer != null && observer.isCancelled()) {
                throw new CancellationException();
            }
            if (hit.get() != null) {
                return new ControllerExecution(List.of(), hit.get());
            }
            List<QueryChunk> finalEvents = enrichDelegateInterrupts(responseExtractor.finish(), request);
            if (observer != null) {
                emit(finalEvents, observer);
            } else {
                collected.addAll(finalEvents);
            }
            return new ControllerExecution(observer != null ? finalEvents : collected, null);
        } catch (CancellationException exception) {
            throw exception;
        } catch (IOException | InterruptedException | RuntimeException exception) {
            log.error("controller invocation failed conversation_id={}", request.getConversationId(), exception);
            throw new IllegalStateException("Versatile invocation failed", exception);
        }
    }

    /**
     * 转调链驱动：识别 → 信号命中（上行 not-in-scope 应答）或 executor 出站调用；
     * 下游返回 not-in-scope 标记时重跑本层控制器重新识别（二级退回一级 upstream-signal，
     * spec 2.2/4.4），重路由受单请求 redirect/去重状态保护（重复目标即 DUPLICATE_TARGET）。
     *
     * @return 无转调命中时的基线终态事件；{@code null} 表示 observer 已被驱动到终态
     */
    private List<QueryChunk> runHandoffChain(ServeRequest request, QueryStreamObserver observer) {
        RequestHandoffState state = new RequestHandoffState();
        ControllerExecution execution = execute(request, observer);
        while (execution.handoffHit() != null) {
            HandoffClassification hit = execution.handoffHit();
            if (isSignalHandoff(hit)) {
                log.info("handoff not-in-scope signal emitted conversation_id={} type={} intent={} domain={}",
                        request.getConversationId(), hit.handoff().handoffType(),
                        hit.handoff().intentId(), hit.handoff().businessDomain());
                String envelope = HandoffSignals.notInScopeEnvelope(hit.handoff());
                observer.onNext(new QueryChunk(QueryChunk.TYPE_CHUNK, envelope));
                observer.onComplete();
                return null;
            }
            ControllerHandoffExecutor.ExecResult result = dispatchHandoff(hit, request, observer, state);
            if (result == ControllerHandoffExecutor.ExecResult.NOT_IN_SCOPE) {
                log.info("handoff downstream not-in-scope, re-running controller conversation_id={}",
                        request.getConversationId());
                execution = execute(request, observer);
                continue;
            }
            return null; // executor/错误路径已驱动 observer 到终态
        }
        return execution.finalEvents();
    }

    /** handoff.signal.handoff-types 命中：产出上行信号而非出站调用。 */
    private boolean isSignalHandoff(HandoffClassification classification) {
        if (classification.handoff() == null) {
            return false;
        }
        return handoffProperties.getSignal().getHandoffTypes()
                .contains(classification.handoff().handoffType());
    }

    /** 命中转调后的终态分发：executor 直接驱动 observer（spec 4.2）。 */
    private ControllerHandoffExecutor.ExecResult dispatchHandoff(HandoffClassification classification,
            ServeRequest request, QueryStreamObserver observer, RequestHandoffState state) {
        if (classification.outcome() == HandoffClassification.Outcome.CONTRACT_VIOLATION) {
            emitHandoffError(observer, "VERSATILE_HANDOFF_MESSAGE_CONTRACT",
                    "identification hit but required field extraction failed conversation_id="
                            + request.getConversationId());
            return ControllerHandoffExecutor.ExecResult.TERMINAL;
        }
        ControllerHandoffExecutor executor = executorProvider == null ? null : executorProvider.getIfAvailable();
        if (executor == null) {
            emitHandoffError(observer, "VERSATILE_HANDOFF_CALLER_UNAVAILABLE",
                    "no RemoteAgentCaller available for handoff conversation_id="
                            + request.getConversationId());
            return ControllerHandoffExecutor.ExecResult.TERMINAL;
        }
        return executor.execute(classification.handoff(), request, observer, state);
    }

    private static String handoffErrorCode(HandoffLoopGuard.GuardResult inbound) {
        return inbound == HandoffLoopGuard.GuardResult.LOOP_LIMIT
                ? "VERSATILE_HANDOFF_LOOP_LIMIT" : "VERSATILE_HANDOFF_DUPLICATE_TARGET";
    }

    private static void emitHandoffError(QueryStreamObserver observer, String code, String detail) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("code", code);
        payload.put("reason", detail == null ? "" : detail);
        String json;
        try {
            json = MAPPER.writeValueAsString(payload);
        } catch (Exception ex) {
            json = "{\"code\":\"" + code + "\"}";
        }
        log.warn("handoff path failed code={} detail={}", code, detail);
        observer.onNext(new QueryChunk(QueryChunk.TYPE_ERROR, json));
        observer.onError(new IllegalStateException(json));
    }

    private static void emit(List<QueryChunk> chunks, QueryStreamObserver observer) {
        if (observer == null || chunks == null) {
            return;
        }
        for (QueryChunk chunk : chunks) {
            if (observer.isCancelled()) {
                return;
            }
            observer.onNext(chunk);
        }
    }

    /** 非流式聚合 observer：收集 executor 驱动的 chunk 与终态。 */
    static final class BufferingObserver implements QueryStreamObserver {
        final List<QueryChunk> chunks = new ArrayList<>();
        boolean completed;
        Throwable error;
        Object lastErrorPayload;

        @Override
        public void onNext(QueryChunk chunk) {
            chunks.add(chunk);
        }

        @Override
        public void onError(Throwable error) {
            this.error = error;
            if (!chunks.isEmpty()) {
                lastErrorPayload = chunks.get(chunks.size() - 1).getData();
            }
        }

        @Override
        public void onComplete() {
            this.completed = true;
        }

        String joinedContent() {
            StringBuilder sb = new StringBuilder();
            for (QueryChunk chunk : chunks) {
                if (QueryChunk.TYPE_CHUNK.equals(chunk.getType()) && chunk.getData() != null) {
                    sb.append(chunk.getData());
                }
            }
            return sb.toString();
        }
    }

    // ==== FEAT-002 基线对齐拷贝开始（源自 VersatileAgentHandler，行为零变更）====

    private Map<String, Object> resolveQueryResult(ServeRequest request, List<QueryChunk> chunks) {
        ChunkAccumulator acc = new ChunkAccumulator();
        for (QueryChunk chunk : chunks) {
            acc.process(chunk, request);
        }
        return acc.buildResult(request);
    }

    private final class ChunkAccumulator {
        private boolean isInterrupted;
        private Map<String, Object> interruptPayload;
        private final List<String> interruptMessages = new ArrayList<>();
        private Map<String, Object> a2aDelegatePayload;
        private Object legacyAnswerContent;
        private String ambiguousIntentId;

        void process(QueryChunk chunk, ServeRequest request) {
            if (QueryChunk.TYPE_ERROR.equals(chunk.getType())) {
                log.error("controller query returned remote error conversation_id={} error={}",
                        request.getConversationId(), chunk.getData());
                throw new IllegalStateException(String.valueOf(chunk.getData()));
            }
            if (QueryChunk.TYPE_INTERRUPT.equals(chunk.getType())) {
                if (chunk.getData() instanceof Map<?, ?> m && isA2aDelegate(m)) {
                    a2aDelegatePayload = copyToStringMap(m);
                    return;
                }
                isInterrupted = true;
                interruptPayload = chunk.getData() instanceof Map<?, ?> m ? copyToStringMap(m) : null;
                return;
            }
            Optional<Map<String, Object>> envelope = answerEnvelope(chunk.getData());
            if (envelope.isPresent()) {
                Object content = envelope.get().get("response_content");
                if (content == null) {
                    content = envelope.get().get("output");
                }
                legacyAnswerContent = content;
                if (envelope.get().get("intent_id") instanceof String intentId && !intentId.isBlank()) {
                    ambiguousIntentId = intentId;
                }
                return;
            }
            interruptMessages.add(String.valueOf(chunk.getData()));
        }

        Map<String, Object> buildResult(ServeRequest request) {
            if (a2aDelegatePayload != null) {
                return a2aDelegateResult(request, a2aDelegatePayload);
            }
            if (legacyAnswerContent != null) {
                Map<String, Object> result = assistantResult(legacyAnswerContent);
                if (ambiguousIntentId != null) {
                    result.put("intent_id", ambiguousIntentId);
                }
                return result;
            }
            if (isInterrupted) {
                Map<String, Object> result = assistantResult(
                        interruptPayload != null && interruptPayload.get("message") != null
                                ? String.valueOf(interruptPayload.get("message"))
                                : interruptMessage(interruptMessages));
                if (interruptPayload != null) {
                    result.put("_interrupt", interruptPayload);
                }
                log.info("controller query returned interrupt conversation_id={}", request.getConversationId());
                return result;
            }
            return assistantResult(interruptMessage(interruptMessages));
        }
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

    // ==== FEAT-002 基线对齐拷贝结束 ====
}
