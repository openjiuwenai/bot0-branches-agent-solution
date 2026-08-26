/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.ext.middleware.trajectory.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.openjiuwen.service.adapters.agentcore.ext.middleware.trajectory.audit.AuditSnapshotStore;
import com.openjiuwen.service.adapters.agentcore.ext.middleware.trajectory.store.AsyncTrajectoryWriter;
import com.openjiuwen.service.adapters.agentcore.ext.middleware.trajectory.store.RedisTrajectoryStore;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * TrajectoryQueryController 的单元测试：runs 执行树重建（索引段解码）、audit 回放、参数校验。
 */
class TrajectoryQueryControllerTest {
    private final RedisTrajectoryStore store = mock(RedisTrajectoryStore.class);
    private final AuditSnapshotStore snapshots = new AuditSnapshotStore(store, mock(AsyncTrajectoryWriter.class));
    private final TrajectoryQueryController controller = new TrajectoryQueryController(store, snapshots);

    @Test
    void runsRebuildsTreeWithDecodedSegments() {
        String traceId = "1cd0e9b4ebc1bc708de6aae571b089ce";
        String traceIndex = RedisTrajectoryStore.traceIndexKey(traceId, "task-1#1");
        when(store.scan(RedisTrajectoryStore.traceIndexPrefix(traceId) + "*")).thenReturn(List.of(traceIndex));
        when(store.getRecord(RedisTrajectoryStore.runKey("task-1#1"))).thenReturn(Optional.of("node1"));
        String parentIndex = RedisTrajectoryStore.parentIndexKey("task-1#1", "remote-9");
        when(store.scan(RedisTrajectoryStore.parentIndexPrefix("task-1#1") + "*"))
                .thenReturn(List.of(parentIndex));
        when(store.getRecord(RedisTrajectoryStore.runEdgeKey("task-1#1", "remote-9")))
                .thenReturn(Optional.of("edge1"));
        ResponseEntity<Map<String, Object>> response = controller.runs(traceId);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("nodes")).isEqualTo(List.of("node1"));
        assertThat(response.getBody().get("edges")).isEqualTo(List.of("edge1"));
    }

    @Test
    void auditRequiresTenantAndConversation() {
        assertThat(controller.audit("", "c").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(controller.audit("t", "").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(controller.runs(" ").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void auditReplaysEntries() {
        when(store.getRecord(anyString())).thenReturn(Optional.empty());
        ResponseEntity<Map<String, Object>> response = controller.audit("t", "c");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("entries")).isEqualTo(List.of());
    }
}
