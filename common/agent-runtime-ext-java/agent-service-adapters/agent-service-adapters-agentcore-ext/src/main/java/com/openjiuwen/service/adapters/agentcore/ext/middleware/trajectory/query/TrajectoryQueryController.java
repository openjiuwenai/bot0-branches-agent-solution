/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.ext.middleware.trajectory.query;

import com.openjiuwen.service.adapters.agentcore.ext.middleware.trajectory.audit.AuditSnapshotStore;
import com.openjiuwen.service.adapters.agentcore.ext.middleware.trajectory.store.RedisTrajectoryStore;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 全链路只读查询端点（独立管理面，不经 {@code /a2a} 北向暴露）。端点本身按部署侧既有
 * 运维面保护接入；租户作用域强制：audit 查询必须携带非空 tenantId（key 内嵌租户段，
 * 不匹配时结果为空的语义不替代显式校验——空 tenantId 直接 400）。
 *
 * @since 2026-08-26
 */
@RestController
@RequestMapping("/manage/trajectory")
public class TrajectoryQueryController {
    private final RedisTrajectoryStore store;
    private final AuditSnapshotStore snapshots;

    /**
     * Creates the controller.
     *
     * @param store     trajectory store
     * @param snapshots audit snapshot store
     */
    public TrajectoryQueryController(RedisTrajectoryStore store, AuditSnapshotStore snapshots) {
        this.store = store;
        this.snapshots = snapshots;
    }

    /**
     * Rebuilds the execution tree for a trace id (nodes + delegation edges).
     *
     * @param traceId W3C trace id
     * @return nodes and edges (record JSON passthrough)
     */
    @GetMapping("/runs")
    public ResponseEntity<Map<String, Object>> runs(@RequestParam String traceId) {
        if (traceId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "traceId is required"));
        }
        List<String> nodeKeys = store.scan(RedisTrajectoryStore.traceIndexPrefix(traceId) + "*");
        List<String> nodes = new ArrayList<>();
        List<String> edges = new ArrayList<>();
        for (String indexKey : nodeKeys) {
            String runId = RedisTrajectoryStore.decodeRunId(indexKey.substring(indexKey.lastIndexOf(':') + 1));
            store.getRecord(RedisTrajectoryStore.runKey(runId)).ifPresent(nodes::add);
            for (String edgeIndex : store.scan(RedisTrajectoryStore.parentIndexPrefix(runId) + "*")) {
                String childRunId = RedisTrajectoryStore.decodeRunId(
                        edgeIndex.substring(edgeIndex.lastIndexOf(':') + 1));
                store.getRecord(RedisTrajectoryStore.runEdgeKey(runId, childRunId)).ifPresent(edges::add);
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("traceId", traceId);
        result.put("nodes", nodes);
        result.put("edges", edges);
        return ResponseEntity.ok(result);
    }

    /**
     * Replays audit snapshots of a conversation in seq order (gap markers included).
     *
     * @param tenantId       tenant id (required; key space is tenant-scoped)
     * @param conversationId conversation id
     * @return ordered snapshot entries
     */
    @GetMapping("/audit")
    public ResponseEntity<Map<String, Object>> audit(@RequestParam String tenantId,
                                                     @RequestParam String conversationId) {
        if (tenantId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "tenantId is required"));
        }
        if (conversationId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "conversationId is required"));
        }
        List<Map<String, Object>> entries = new ArrayList<>();
        for (AuditSnapshotStore.ReplayEntry entry : snapshots.replay(tenantId, conversationId)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("seq", entry.seq());
            row.put("gap", entry.isGap());
            Optional.ofNullable(entry.json()).ifPresent(json -> row.put("snapshot", json));
            entries.add(row);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tenantId", tenantId);
        result.put("conversationId", conversationId);
        result.put("entries", entries);
        return ResponseEntity.ok(result);
    }
}
