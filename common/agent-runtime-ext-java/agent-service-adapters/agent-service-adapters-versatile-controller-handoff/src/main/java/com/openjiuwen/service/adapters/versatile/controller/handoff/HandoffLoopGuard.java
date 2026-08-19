/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.versatile.controller.handoff;

import com.openjiuwen.service.adapters.versatile.controller.handoff.autoconfigure.ControllerHandoffProperties;
import com.openjiuwen.service.spec.dto.ServeRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Two-layer loop protection (spec 3.8/4.6): per-request dedup + redirect counting
 * + repeated-target detection; cross-request detection via A2A metadata-carried
 * handoffHopCount/handoffRouteTrace/sourceAgentId. Cross-request detection
 * degrades (warn) when the inbound metadata chain is not populated.
 *
 * <p>Trace semantics: every outbound hop appends <b>self</b> to the trace. When an
 * instance receives a ServeRequest whose trace already contains itself, it is the
 * L2 &rarr; L1 &rarr; same-L2 (or any revisit) loop and is rejected.
 *
 * @since 2026-08-19
 */
public class HandoffLoopGuard {
    private static final Logger log = LoggerFactory.getLogger(HandoffLoopGuard.class);

    private final ControllerHandoffProperties properties;

    public HandoffLoopGuard(ControllerHandoffProperties properties) {
        this.properties = properties;
    }

    public enum GuardResult {
        ALLOW, LOOP_LIMIT, DUPLICATE_TARGET, DUPLICATE_MESSAGE
    }

    public record OutboundDecision(GuardResult result, Map<String, Object> metadata) {
        static OutboundDecision allow(Map<String, Object> metadata) {
            return new OutboundDecision(GuardResult.ALLOW, metadata);
        }
    }

    /** Inbound route-trace validation; read from ServeRequest.metadata. */
    public GuardResult checkInbound(ServeRequest request) {
        ControllerHandoffProperties.LoopTraceMetadata keys = properties.getLoopTraceMetadata();
        Object hopRaw = request.getMetadata() == null ? null : request.getMetadata().get(keys.getHopCountKey());
        if (hopRaw == null) {
            log.warn("handoff route-trace metadata missing on inbound request conversation_id={} — "
                    + "cross-request loop detection degraded to per-request scope",
                    request.getConversationId());
            return GuardResult.ALLOW;
        }
        int hop = toInt(hopRaw, 0);
        if (hop > properties.getLoop().getMaxRouteTraceHops()) {
            return GuardResult.LOOP_LIMIT;
        }
        String self = properties.getSelfAgentId();
        if (self != null && !self.isBlank()) {
            List<String> trace = toStringList(request.getMetadata().get(keys.getRouteTraceKey()));
            if (trace.contains(self)) {
                log.warn("handoff route trace revisits this agent conversation_id={} hop={} trace={}",
                        request.getConversationId(), hop, trace);
                return GuardResult.DUPLICATE_TARGET;
            }
        }
        return GuardResult.ALLOW;
    }

    /**
     * Same-request dedup/count/target checks plus outbound trace metadata construction
     * (hop+1, self appended to trace, sourceAgentId preserved or initialized).
     */
    public OutboundDecision prepareOutbound(String targetAgentId, String dedupKey, ServeRequest request,
            RequestHandoffState state) {
        if (!state.dedupKeys().add(dedupKey)) {
            log.info("handoff duplicate message skipped conversation_id={} dedup_key={}",
                    request.getConversationId(), dedupKey);
            return new OutboundDecision(GuardResult.DUPLICATE_MESSAGE, Map.of());
        }
        if (state.redirectCount() + 1 > properties.getLoop().getMaxRedirects()) {
            return new OutboundDecision(GuardResult.LOOP_LIMIT, Map.of());
        }
        if (properties.getLoop().isDuplicateTargetDetection() && state.targets().contains(targetAgentId)) {
            return new OutboundDecision(GuardResult.DUPLICATE_TARGET, Map.of());
        }
        state.incrementRedirect();
        state.targets().add(targetAgentId);

        ControllerHandoffProperties.LoopTraceMetadata keys = properties.getLoopTraceMetadata();
        Map<String, Object> inbound = request.getMetadata() == null ? Map.of() : request.getMetadata();
        List<String> trace = new ArrayList<>(toStringList(inbound.get(keys.getRouteTraceKey())));
        String self = properties.getSelfAgentId();
        if (self != null && !self.isBlank() && !trace.contains(self)) {
            trace.add(self);
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(keys.getHopCountKey(), toInt(inbound.get(keys.getHopCountKey()), 0) + 1);
        metadata.put(keys.getRouteTraceKey(), trace);
        Object source = inbound.get(keys.getSourceAgentKey());
        metadata.put(keys.getSourceAgentKey(), source != null ? source : self);
        return OutboundDecision.allow(metadata);
    }

    private static int toInt(Object value, int fallback) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        if (value instanceof String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private static List<String> toStringList(Object value) {
        if (value instanceof List<?> list) {
            List<String> result = new ArrayList<>();
            for (Object item : list) {
                if (item != null) {
                    result.add(String.valueOf(item));
                }
            }
            return result;
        }
        return new ArrayList<>();
    }
}
