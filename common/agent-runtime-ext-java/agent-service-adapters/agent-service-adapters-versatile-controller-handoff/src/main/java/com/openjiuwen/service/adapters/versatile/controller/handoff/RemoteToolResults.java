/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.versatile.controller.handoff;

import com.openjiuwen.service.spec.dto.ServeRequest;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * re-invoke 标记解析：runtime 协调器在 remote 批 settle 后以 {@code resume=true} 重新
 * 进入 handler，请求 metadata 携带 {@code runtime.remoteToolResults}（toolCallId → 结果）。
 * 失败成员值为 {@code {"ok":false,"code":"REMOTE_*",...}} map；成功成员值为结果字符串
 * （可能是 {@link HandoffSignals} not-in-scope 信封）。toolCallId 由本模块编码为
 * {@code "handoff:<agentId>:<uuid>"}，据此无状态解析弹回目标（迁移设计稿 3）。
 *
 * @since 2026-08-20
 */
public final class RemoteToolResults {
    /** 协调器注入 remote 结果的 request metadata 键（RESERVED_METADATA 之一）。 */
    public static final String METADATA_KEY = "runtime.remoteToolResults";

    private static final String TOOL_CALL_ID_PREFIX = "handoff:";

    private final Map<String, Object> results;

    private RemoteToolResults(Map<String, Object> results) {
        this.results = results;
    }

    /** 无该 metadata 键或值非 map 时返回 {@code null}（普通首轮请求）。 */
    public static RemoteToolResults parse(ServeRequest request) {
        if (request.getMetadata() == null) {
            return null;
        }
        Object raw = request.getMetadata().get(METADATA_KEY);
        if (!(raw instanceof Map<?, ?> map)) {
            return null;
        }
        Map<String, Object> results = new java.util.LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() != null) {
                results.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        return new RemoteToolResults(results);
    }

    /** 任一成功成员携带 not-in-scope 信封（二级退回一级 upstream-signal）。 */
    public boolean hasNotInScopeEnvelope() {
        for (Object value : results.values()) {
            if (value instanceof String s && HandoffSignals.isNotInScope(s)) {
                return true;
            }
        }
        return false;
    }

    /** 从 toolCallId（{@code handoff:<agentId>:<uuid>}）解析的弹回目标集合。 */
    public Set<String> bouncedTargets() {
        Set<String> targets = new LinkedHashSet<>();
        for (String toolCallId : results.keySet()) {
            if (toolCallId != null && toolCallId.startsWith(TOOL_CALL_ID_PREFIX)) {
                String rest = toolCallId.substring(TOOL_CALL_ID_PREFIX.length());
                int sep = rest.indexOf(':');
                String target = sep > 0 ? rest.substring(0, sep) : rest;
                if (!target.isBlank()) {
                    targets.add(target);
                }
            }
        }
        return targets;
    }

    /** 首个失败成员（单 item 批）；errorCode 已映射为 {@code VERSATILE_HANDOFF_*}。 */
    public Failure failure() {
        for (Map.Entry<String, Object> entry : results.entrySet()) {
            if (entry.getValue() instanceof Map<?, ?> failure && Boolean.FALSE.equals(failure.get("ok"))) {
                String code = String.valueOf(failure.get("code"));
                return new Failure(mapErrorCode(code),
                        code + ": " + failure.get("message") + " toolCallId=" + entry.getKey());
            }
        }
        return null;
    }

    /** 成功字符串结果以换行拼接（单 item 批实际只有一个值）；信封是协议信号不计入。 */
    public String joinedResults() {
        StringBuilder sb = new StringBuilder();
        for (Object value : results.values()) {
            if (value instanceof String s && !s.isBlank() && !HandoffSignals.isNotInScope(s)) {
                if (!sb.isEmpty()) {
                    sb.append('\n');
                }
                sb.append(s);
            }
        }
        return sb.toString();
    }

    private static String mapErrorCode(String remoteCode) {
        return "REMOTE_TIMEOUT".equals(remoteCode)
                ? "VERSATILE_HANDOFF_TIMEOUT" : "VERSATILE_HANDOFF_TARGET_UNAVAILABLE";
    }

    /** 协调器 REMOTE_* 失败（已映射本模块错误码）。 */
    public record Failure(String errorCode, String detail) {
    }
}
