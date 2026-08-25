/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.versatile.controller.handoff;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.service.spec.dto.ServeRequest;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * RemoteToolResults 解析验收：not-in-scope 信封、弹回目标、REMOTE_* 错误码映射
 * 与成功结果拼接（re-invoke 轮 metadata 契约，迁移设计稿 3）。
 *
 * @since 2026-08-20
 */
class RemoteToolResultsTest {
    @Test
    void parsesEnvelopeBouncedTargetAndFailure() {
        ServeRequest request = new ServeRequest();
        Map<String, Object> metadata = new HashMap<>();
        Map<String, Object> results = new LinkedHashMap<>();
        results.put("handoff:agent_card_l2:abc",
                HandoffSignals.notInScopeEnvelope(new IntentHandoff("L2_TO_L1", null, null, null, null, "{}")));
        metadata.put("runtime.remoteToolResults", results);
        request.setMetadata(metadata);
        RemoteToolResults parsed = RemoteToolResults.parse(request).orElseThrow();
        assertThat(parsed.hasNotInScopeEnvelope()).isTrue();
        assertThat(parsed.bouncedTargets()).containsExactly("agent_card_l2");
        assertThat(parsed.failure()).isEmpty();
        assertThat(parsed.joinedResults()).isEmpty();
    }

    @Test
    void mapsRemoteFailureCodesToHandoffErrors() {
        ServeRequest request = new ServeRequest();
        Map<String, Object> metadata = new HashMap<>();
        Map<String, Object> results = new LinkedHashMap<>();
        Map<String, Object> failure = new LinkedHashMap<>();
        failure.put("ok", false);
        failure.put("code", "REMOTE_TIMEOUT");
        failure.put("message", "Remote invocation timed out");
        failure.put("remoteAgentId", "agent_card_l2");
        results.put("handoff:agent_card_l2:abc", failure);
        metadata.put("runtime.remoteToolResults", results);
        request.setMetadata(metadata);
        RemoteToolResults parsed = RemoteToolResults.parse(request).orElseThrow();
        assertThat(parsed.failure()).isPresent();
        assertThat(parsed.failure().orElseThrow().errorCode()).isEqualTo("VERSATILE_HANDOFF_TIMEOUT");
        assertThat(parsed.failure().orElseThrow().detail()).contains("REMOTE_TIMEOUT");
    }

    @Test
    void mapsOtherRemoteFailureCodesToTargetUnavailable() {
        ServeRequest request = new ServeRequest();
        Map<String, Object> metadata = new HashMap<>();
        Map<String, Object> results = new LinkedHashMap<>();
        Map<String, Object> failure = new LinkedHashMap<>();
        failure.put("ok", false);
        failure.put("code", "REMOTE_UNAVAILABLE");
        failure.put("message", "connection refused");
        results.put("handoff:agent_card_dead:abc", failure);
        metadata.put("runtime.remoteToolResults", results);
        request.setMetadata(metadata);
        RemoteToolResults parsed = RemoteToolResults.parse(request).orElseThrow();
        assertThat(parsed.failure()).isPresent();
        assertThat(parsed.failure().orElseThrow().errorCode())
                .isEqualTo("VERSATILE_HANDOFF_TARGET_UNAVAILABLE");
    }

    @Test
    void joinsSuccessfulStringResults() {
        ServeRequest request = new ServeRequest();
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("runtime.remoteToolResults",
                Map.of("handoff:agent_card_l2:abc", "二级本域业务答案"));
        request.setMetadata(metadata);
        assertThat(RemoteToolResults.parse(request).orElseThrow().joinedResults())
                .isEqualTo("二级本域业务答案");
    }

    @Test
    void absentOrNonMapMetadataYieldsEmpty() {
        assertThat(RemoteToolResults.parse(new ServeRequest())).isEmpty();
        ServeRequest request = new ServeRequest();
        request.setMetadata(Map.of("runtime.remoteToolResults", "not-a-map"));
        assertThat(RemoteToolResults.parse(request)).isEmpty();
    }
}
