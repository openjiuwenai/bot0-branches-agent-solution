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

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * AgentHandler that proxies a Versatile controller (FEAT-002 baseline behavior) and
 * additionally consumes controller intent-handoff messages: classification strictly
 * precedes the baseline error mapping; a hit suppresses the line from the baseline
 * extractor and produces a single-item {@code a2a_delegate} interrupt (resume=true)
 * instead of an outbound call — the runtime coordinator owns the outbound invocation,
 * shadow-task persistence and the resume chain (migration design 2). Never emits
 * a "COMPLETED" QueryChunk.
 *
 * @since 2026-08-19
 */
public class ControllerHandoffAgentHandler implements AgentHandler {
    private static final Logger log = LoggerFactory.getLogger(ControllerHandoffAgentHandler.class);
    private static final String INTERRUPT_MESSAGE = "Remote agent requires input";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** runtime 协调器消费的中断种类标记（与基线 buildA2aDelegateInterrupt 契约一致）。 */
    static final String DELEGATE_INTERRUPT_KIND = "a2a_delegate";

    private final VersatileProperties properties;
    private final VersatileHttpClient client;
    private final VersatileRequestExtractor requestExtractor;
    private final IntentHandoffClassifier handoffClassifier;
    private final HandoffLoopGuard loopGuard;
    private final HandoffTargetResolver targetResolver;
    private final ControllerHandoffProperties handoffProperties;

    public ControllerHandoffAgentHandler(VersatileProperties versatileProperties,
            IntentHandoffClassifier handoffClassifier,
            HandoffLoopGuard loopGuard,
            HandoffTargetResolver targetResolver,
            ControllerHandoffProperties handoffProperties) {
        this.properties = Objects.requireNonNull(versatileProperties, "versatileProperties");
        this.client = new VersatileHttpClient(this.properties);
        this.requestExtractor = new VersatileRequestExtractor(this.properties);
        this.handoffClassifier = handoffClassifier;
        this.loopGuard = loopGuard;
        this.targetResolver = targetResolver;
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
        RemoteToolResults remote = RemoteToolResults.parse(request);
        if (remote != null) {
            if (remote.failure() != null) {
                throw new IllegalStateException(remote.failure().errorCode()
                        + " remote invocation failed: " + remote.failure().detail());
            }
            if (!remote.hasNotInScopeEnvelope()) {
                // happy-path re-invoke：非流式终答直通；流式内容已由协调器投影
                return new QueryResponse(assistantResult(remote.joinedResults()),
                        request.getConversationId());
            }
        }
        List<QueryChunk> finalEvents = runHandoffChain(request, null,
                remote == null ? Set.of() : remote.bouncedTargets());
        return new QueryResponse(resolveQueryResult(request, finalEvents),
                request.getConversationId());
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
        RemoteToolResults remote = RemoteToolResults.parse(request);
        if (remote != null) {
            if (remote.failure() != null) {
                emitHandoffError(observer, remote.failure().errorCode(), remote.failure().detail());
                return;
            }
            if (!remote.hasNotInScopeEnvelope()) {
                if (request.isStream()) {
                    // 流式：内容已由协调器投影为 REMOTE_AGENT_OUTPUT，handler 只收尾
                    observer.onComplete();
                } else {
                    observer.onNext(new QueryChunk(QueryChunk.TYPE_CHUNK, remote.joinedResults()));
                    observer.onComplete();
                }
                log.info("Completed controller-handoff re-invoke with remote answer conversation_id={}",
                        request.getConversationId());
                return;
            }
            log.info("handoff remote not-in-scope envelope detected, re-running controller conversation_id={}",
                    request.getConversationId());
        }
        try {
            List<QueryChunk> finalEvents = runHandoffChain(request, observer,
                    remote == null ? Set.of() : remote.bouncedTargets());
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
                if (classification.outcome() == HandoffClassification.Outcome.IGNORED) {
                    return; // 识别命中但字段不全的回显/噪声帧：整行抑制，不透传基线（spec 2.2）
                }
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
     * 转调链驱动：识别 → 信号命中（上行 not-in-scope 应答）或产出单 item
     * {@code a2a_delegate} 中断（resume=true，出站调用移交 runtime 协调器，
     * 迁移设计稿 2）。{@code observer == null} 表示 query 收集模式：中断/信封
     * 作为返回值交由 {@link #resolveQueryResult} 归一。
     *
     * @param bouncedTargets 本链已弹回过的目标（re-invoke 重入时来自
     *        remoteToolResults 解析）；再转调同目标 → DUPLICATE_TARGET
     * @return 无转调命中时的基线终态事件；流式模式 {@code null} 表示 observer 已被驱动到终态
     */
    private List<QueryChunk> runHandoffChain(ServeRequest request, QueryStreamObserver observer,
            Set<String> bouncedTargets) {
        ControllerExecution execution = execute(request, observer);
        while (execution.handoffHit() != null) {
            HandoffClassification hit = execution.handoffHit();
            if (isSignalHandoff(hit)) {
                log.info("handoff not-in-scope signal emitted conversation_id={} type={} intent={} domain={}",
                        request.getConversationId(), hit.handoff().handoffType(),
                        hit.handoff().intentId(), hit.handoff().businessDomain());
                QueryChunk envelope = new QueryChunk(QueryChunk.TYPE_CHUNK,
                        HandoffSignals.notInScopeEnvelope(hit.handoff()));
                if (observer != null) {
                    observer.onNext(envelope);
                    observer.onComplete();
                    return null;
                }
                return List.of(envelope);
            }
            ResolvedTarget target;
            try {
                target = targetResolver.resolve(hit.handoff());
            } catch (HandoffTargetResolutionException ex) {
                emitHandoffError(observer, ex.getErrorCode(), ex.getMessage());
                return null;
            }
            if (bouncedTargets.contains(target.agentId())) {
                emitHandoffError(observer, "VERSATILE_HANDOFF_DUPLICATE_TARGET",
                        "target bounced in this chain: " + target.agentId());
                return null;
            }
            prepareDelegationMetadata(request);
            QueryChunk interrupt = handoffDelegateInterrupt(target, request);
            log.info("handoff delegate interrupt emitted conversation_id={} target={}",
                    request.getConversationId(), target.agentId());
            if (observer != null) {
                observer.onNext(interrupt);
                observer.onComplete();
                return null;
            }
            return List.of(interrupt);
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

    /**
     * 单 item a2a_delegate 中断（runtime 协调器消费契约）：toolCallId 编码出站目标
     * （{@code handoff:<agentId>:<uuid>}），re-invoke 时 handler 从 remoteToolResults
     * 键无状态解析弹回目标（迁移设计稿 3）。
     */
    private QueryChunk handoffDelegateInterrupt(ResolvedTarget target, ServeRequest request) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("_interrupt_kind", DELEGATE_INTERRUPT_KIND);
        context.put("agentName", target.agentId());
        context.put("resume", true);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "__interaction__");
        payload.put("toolCallId", "handoff:" + target.agentId() + ":" + UUID.randomUUID());
        payload.put("agentName", target.agentId());
        payload.put("message", request.lastUserQuery());
        payload.put("responseContent", "");
        payload.put("resume", true);
        payload.put("context", context);
        return new QueryChunk(QueryChunk.TYPE_INTERRUPT, payload);
    }

    /** 中断产出前把 trace 三键与透传键并入 request.metadata（协调器出站取自此，设计稿 4.2）。 */
    private void prepareDelegationMetadata(ServeRequest request) {
        Map<String, Object> metadata = request.getMetadata() == null
                ? new LinkedHashMap<>() : new LinkedHashMap<>(request.getMetadata());
        for (String key : handoffProperties.getForwardMetadataKeys()) {
            Object value = request.getMetadata() == null ? null : request.getMetadata().get(key);
            if (value != null) {
                metadata.put(key, value);
            }
        }
        if (request.getTenantId() != null) {
            metadata.put("tenantId", request.getTenantId());
        }
        if (request.getUserId() != null) {
            metadata.put("userId", request.getUserId());
        }
        if (request.getSpaceId() != null) {
            metadata.put("spaceId", request.getSpaceId());
        }
        metadata.putAll(loopGuard.outboundTraceMetadata(request));
        request.setMetadata(metadata);
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
        if (observer == null) {
            throw new IllegalStateException(json); // query 收集模式：错误以异常上抛
        }
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
