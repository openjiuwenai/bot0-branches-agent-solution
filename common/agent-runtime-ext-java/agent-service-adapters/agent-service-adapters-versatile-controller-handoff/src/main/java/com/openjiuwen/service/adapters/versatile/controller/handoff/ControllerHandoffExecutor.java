/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.versatile.controller.handoff;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openjiuwen.service.adapters.versatile.controller.handoff.autoconfigure.ControllerHandoffProperties;
import com.openjiuwen.service.app.controller.a2a.client.RemoteAgentCaller;
import com.openjiuwen.service.app.controller.a2a.client.RemoteCall;
import com.openjiuwen.service.app.controller.a2a.client.RemoteCallOutcome;
import com.openjiuwen.service.spec.dto.QueryChunk;
import com.openjiuwen.service.spec.spi.QueryStreamObserver;
import com.openjiuwen.service.spec.dto.ServeRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Consumes an IntentHandoff: resolve target -> build RemoteCall -> invoke
 * RemoteAgentCaller.callOutcome(RemoteCall, EventObserver) -> normalize the
 * RemoteCallOutcome onto the current observer. Drives the observer to a terminal
 * itself and never emits a "COMPLETED" QueryChunk (spec 2.3/2.4/4.5).
 *
 * @since 2026-08-19
 */
public class ControllerHandoffExecutor {
    private static final Logger log = LoggerFactory.getLogger(ControllerHandoffExecutor.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RemoteAgentCaller remoteAgentCaller;
    private final HandoffTargetResolver targetResolver;
    private final DownstreamEventMapper eventMapper;
    private final HandoffLoopGuard loopGuard;
    private final ObjectProvider<CrossAgentResumePort> resumePortProvider;
    private final ControllerHandoffProperties config;

    public ControllerHandoffExecutor(RemoteAgentCaller remoteAgentCaller,
            HandoffTargetResolver targetResolver,
            DownstreamEventMapper eventMapper,
            HandoffLoopGuard loopGuard,
            ObjectProvider<CrossAgentResumePort> resumePortProvider,
            ControllerHandoffProperties config) {
        this.remoteAgentCaller = remoteAgentCaller;
        this.targetResolver = targetResolver;
        this.eventMapper = eventMapper;
        this.loopGuard = loopGuard;
        this.resumePortProvider = resumePortProvider;
        this.config = config;
    }

    public void execute(IntentHandoff handoff, ServeRequest currentRequest, QueryStreamObserver observer,
            RequestHandoffState state) {
        ResolvedTarget target;
        try {
            target = targetResolver.resolve(handoff);
        } catch (HandoffTargetResolutionException ex) {
            toHandoffError(observer, ex.getErrorCode(), ex.getMessage(), null);
            return;
        }
        String dedupKey = handoff.dedupKey() != null && !handoff.dedupKey().isBlank()
                ? handoff.dedupKey() : deriveDedupKey(handoff);
        HandoffLoopGuard.OutboundDecision decision =
                loopGuard.prepareOutbound(target.agentId(), dedupKey, currentRequest, state);
        log.info("handoff outbound target={} source={} type={} intent={} domain={} guard={}",
                target.agentId(), target.source(), handoff.handoffType(),
                handoff.intentId(), handoff.businessDomain(), decision.result());
        switch (decision.result()) {
            case DUPLICATE_MESSAGE -> {
                return; // 同一执行重复消息：跳过，不报错（spec 3.8）
            }
            case LOOP_LIMIT -> {
                toHandoffError(observer, "VERSATILE_HANDOFF_LOOP_LIMIT",
                        "max redirects exceeded target=" + target.agentId(), target.agentId());
                return;
            }
            case DUPLICATE_TARGET -> {
                toHandoffError(observer, "VERSATILE_HANDOFF_DUPLICATE_TARGET",
                        "target already routed in this execution: " + target.agentId(), target.agentId());
                return;
            }
            default -> { /* ALLOW */
            }
        }

        RemoteCall call = buildRemoteCall(currentRequest, target.agentId(), decision.metadata());
        DownstreamEventBridge bridge = new DownstreamEventBridge(observer);
        CompletableFuture<RemoteCallOutcome> future = remoteAgentCaller.callOutcome(call, bridge);
        bridge.bindFuture(future);
        RemoteCallOutcome outcome;
        try {
            outcome = config.getTimeout() == null
                    ? future.get()
                    : future.get(config.getTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException ex) {
            future.cancel(true);
            toHandoffError(observer, "VERSATILE_HANDOFF_TIMEOUT",
                    "downstream call timeout after " + config.getTimeout(), target.agentId());
            return;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            toHandoffError(observer, "VERSATILE_HANDOFF_TARGET_UNAVAILABLE",
                    "interrupted while awaiting downstream", target.agentId());
            return;
        } catch (CancellationException ex) {
            log.warn("downstream call cancelled conversation_id={}", currentRequest.getConversationId());
            return; // 上游取消：停止消费，不再驱动 observer
        } catch (ExecutionException ex) {
            toHandoffError(observer, "VERSATILE_HANDOFF_TARGET_UNAVAILABLE",
                    "downstream call failed: " + String.valueOf(ex.getCause()), target.agentId());
            return;
        }

        DownstreamEventMapper.MappedTerminal terminal = eventMapper.fromOutcome(outcome, target.agentId());
        switch (terminal.action()) {
            case COMPLETE -> {
                // 非流式调用没有增量事件，终答只存在于 outcome.result：作为业务内容下发。
                // 流式调用的内容由 bridge 事件承载，这里不重复下发（spec 2.4）。
                if (!call.isCallerStreaming()
                        && outcome.result() != null && !outcome.result().isBlank()) {
                    observer.onNext(new QueryChunk(QueryChunk.TYPE_CHUNK, outcome.result()));
                }
                observer.onComplete();
            }
            case INPUT_REQUIRED -> handleInputRequired(observer, currentRequest, target.agentId(), outcome);
            case ERROR -> toHandoffError(observer, terminal.errorCode(), terminal.detail(), target.agentId());
            default -> toHandoffError(observer, "VERSATILE_HANDOFF_RESULT_INVALID",
                    "unmapped terminal action", target.agentId());
        }
    }

    private void handleInputRequired(QueryStreamObserver observer, ServeRequest request, String targetAgentId,
            RemoteCallOutcome outcome) {
        CrossAgentResumePort port = resumePortProvider == null ? null : resumePortProvider.getIfAvailable();
        boolean resumable = config.getCrossAgentResume().isEnabled()
                && port != null
                && outcome.remoteTaskId() != null
                && port.saveResumeAssociation(request.getConversationId(), targetAgentId,
                        outcome.remoteTaskId(), targetAgentId);
        if (!resumable) {
            toHandoffError(observer, "VERSATILE_HANDOFF_CROSS_AGENT_RESUME_UNSUPPORTED",
                    "downstream requires input but cross-agent resume is unavailable"
                            + " (enabled=" + config.getCrossAgentResume().isEnabled()
                            + ", port=" + (port != null) + ", remoteTaskId=" + outcome.remoteTaskId() + ")",
                    targetAgentId);
            return;
        }
        Map<String, Object> interrupt = new LinkedHashMap<>();
        interrupt.put("message", outcome.inputPrompt() != null ? outcome.inputPrompt()
                : "Remote agent requires input");
        Map<String, Object> association = new LinkedHashMap<>();
        association.put("targetAgentId", targetAgentId);
        association.put("remoteTaskId", outcome.remoteTaskId());
        interrupt.put("_interrupt", association);
        observer.onNext(new QueryChunk(QueryChunk.TYPE_INTERRUPT, interrupt));
        observer.onComplete();
    }

    private RemoteCall buildRemoteCall(ServeRequest request, String targetAgentId,
            Map<String, Object> traceMetadata) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        for (String key : config.getForwardMetadataKeys()) {
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
        metadata.putAll(traceMetadata); // 路由轨迹键优先级最高，不可被透传键覆盖（spec 2.3）
        return new RemoteCall(targetAgentId, request.lastUserQuery(), request.getConversationId(),
                null, metadata, null, request.isStream());
    }

    private void toHandoffError(QueryStreamObserver observer, String code, String detail, String targetAgentId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("code", code);
        payload.put("reason", detail == null ? "" : detail);
        if (targetAgentId != null) {
            payload.put("target", targetAgentId);
        }
        String json;
        try {
            json = MAPPER.writeValueAsString(payload);
        } catch (Exception ex) {
            json = "{\"code\":\"" + code + "\"}";
        }
        log.warn("handoff failed code={} target={} detail={}", code, targetAgentId, detail);
        observer.onNext(new QueryChunk(QueryChunk.TYPE_ERROR, json));
        observer.onError(new IllegalStateException(json));
    }

    private static String deriveDedupKey(IntentHandoff handoff) {
        String basis = handoff.handoffType() + '|' + handoff.intentId() + '|'
                + handoff.businessDomain() + '|' + handoff.targetAgentId();
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(basis.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                sb.append(String.format("%02x", hash[i]));
            }
            return sb.toString();
        } catch (Exception ex) {
            return basis;
        }
    }
}
