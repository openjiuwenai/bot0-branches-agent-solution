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
 * Loop protection (spec 3.8/4.6): inbound route-trace validation via A2A
 * metadata-carried handoffHopCount/handoffRouteTrace/sourceAgentId, plus outbound
 * trace metadata construction for the next hop. Cross-request detection degrades
 * (debug) when the inbound metadata chain is not populated.
 *
 * <p>Trace semantics: every outbound hop appends <b>self</b> to the trace. An
 * instance rejects an inbound request when the trace contains itself — it was
 * already a routing target for this conversation and is being re-targeted
 * (spec 3.8). The 二级退回一级 journey never appears on the trace: it rides the
 * upstream not-in-scope signal ({@link HandoffSignals}) on the answer channel,
 * not a reverse invocation.
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
        ALLOW, LOOP_LIMIT, DUPLICATE_TARGET
    }

    /** Inbound route-trace validation; read from ServeRequest.metadata. */
    public GuardResult checkInbound(ServeRequest request) {
        ControllerHandoffProperties.LoopTraceMetadata keys = properties.getLoopTraceMetadata();
        Object hopRaw = request.getMetadata() == null ? null : request.getMetadata().get(keys.getHopCountKey());
        if (hopRaw == null) {
            // L1 部署下普通终端用户请求均无该元数据（仅 agent 间转调才携带），逐请求告警
            // 会产生全量日志噪音，降为 DEBUG
            log.debug("handoff route-trace metadata missing on inbound request conversation_id={} — "
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
     * 构造下一出站跳的跨请求循环 trace 三键：hop+1、trace 追加 self（去重）、
     * sourceAgentId 保留或初始化为 self。handler 产出 a2a_delegate 中断前把它并入
     * request.metadata；runtime 协调器 outboundMetadata 对非 RESERVED 键原样透传到
     * RemoteCall.metadata（迁移设计稿 4.2）。
     */
    public Map<String, Object> outboundTraceMetadata(ServeRequest request) {
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
        return metadata;
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
