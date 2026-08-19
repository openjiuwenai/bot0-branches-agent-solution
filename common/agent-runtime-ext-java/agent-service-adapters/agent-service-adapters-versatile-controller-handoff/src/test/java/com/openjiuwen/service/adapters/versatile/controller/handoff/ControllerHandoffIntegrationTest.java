/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.versatile.controller.handoff;

import com.openjiuwen.service.adapters.versatile.autoconfigure.VersatileProperties;
import com.openjiuwen.service.adapters.versatile.controller.handoff.autoconfigure.ControllerHandoffProperties;
import com.openjiuwen.service.app.controller.a2a.client.RemoteAgentCaller;
import com.openjiuwen.service.app.controller.a2a.client.RemoteCall;
import com.openjiuwen.service.app.controller.a2a.client.RemoteCallOutcome;
import com.openjiuwen.service.spec.dto.QueryChunk;
import com.openjiuwen.service.spec.spi.QueryStreamObserver;
import com.openjiuwen.service.spec.dto.ServeRequest;

import com.sun.net.httpserver.HttpServer;
import org.a2aproject.sdk.spec.TaskState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spec 7.2 场景旅程验收：一级转调二级、二级退回一级、异常区分、目标不可用、
 * 重复去重、单请求循环保护；以及 7.4 masking（错误载荷不携带原始报文）。
 */
class ControllerHandoffIntegrationTest {

    static final class RecordingObserver implements QueryStreamObserver {
        final List<QueryChunk> chunks = new ArrayList<>();
        boolean completed;
        Throwable error;

        @Override public void onNext(QueryChunk chunk) { chunks.add(chunk); }
        @Override public void onError(Throwable error) { this.error = error; }
        @Override public void onComplete() { this.completed = true; }
    }

    static class FakeCaller implements RemoteAgentCaller {
        final List<RemoteCall> calls = new ArrayList<>();
        RemoteCallOutcome outcome = new RemoteCallOutcome("rt-1", TaskState.TASK_STATE_COMPLETED,
                "COMPLETED", "二级答案", null, null);
        RuntimeException failure;

        @Override
        public CompletableFuture<RemoteCallOutcome> callOutcome(RemoteCall call, EventObserver observer) {
            calls.add(call);
            if (failure != null) {
                CompletableFuture<RemoteCallOutcome> f = new CompletableFuture<>();
                f.completeExceptionally(failure);
                return f;
            }
            return CompletableFuture.completedFuture(outcome);
        }
    }

    private HttpServer server;
    private String baseUrl;
    private FakeCaller caller;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        caller = new FakeCaller();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private void controllerSays(String... lines) {
        server.createContext("/", exchange -> {
            byte[] body = String.join("\n", lines).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
    }

    private ControllerHandoffAgentHandler handler() {
        VersatileProperties versatile = new VersatileProperties();
        versatile.setUrlTemplate(baseUrl + "/v1/p/agents/a/conversations/{conversation_id}");
        versatile.setTimeout(Duration.ofSeconds(5));
        ControllerHandoffProperties hp = new ControllerHandoffProperties();
        ControllerHandoffProperties.Classify c = new ControllerHandoffProperties.Classify();
        c.setFieldPath("/data/code");
        c.setFieldValue(List.of("14000"));
        hp.setClassify(c);
        ControllerHandoffProperties.Fields f = hp.getFields();
        f.setHandoffType("/data/handoff_type");
        f.setIntentId("/data/intent_id");
        f.setBusinessDomain("/data/domain");
        f.setTargetAgentId("/data/target_agent/id");
        f.setDedupKey("/data/dedup_key");
        hp.setSelfAgentId("agent_card_l1");
        hp.getTarget().setAllowedAgents(List.of("agent_card_l1", "agent_card_hotel", "agent_card_flight"));
        hp.getTarget().setFixedL1Entry("agent_card_l1");
        hp.getTarget().setIntentMapping(Map.of("intent_flight", "agent_card_flight"));
        ControllerHandoffExecutor executor = new ControllerHandoffExecutor(caller,
                new HandoffTargetResolver(hp), new DownstreamEventMapper(), new HandoffLoopGuard(hp),
                new ControllerHandoffAgentHandlerTest.EmptyProvider<>(), hp);
        return new ControllerHandoffAgentHandler(versatile, new IntentHandoffClassifier(hp),
                new HandoffLoopGuard(hp),
                new ControllerHandoffAgentHandlerTest.SingleProvider<>(executor), hp);
    }

    private ServeRequest request(String content) {
        ServeRequest r = new ServeRequest();
        r.setConversationId("conv-i");
        r.setStream(true);
        r.setTenantId("t1");
        r.setMetadata(new HashMap<>());
        r.setMessages(List.of(Map.of("role", "user", "content", content)));
        return r;
    }

    private String handoffLine(String type, String intent, String domain, String target, String dedup) {
        return "{\"data\":{\"code\":14000,\"handoff_type\":\"" + type + "\",\"intent_id\":\"" + intent
                + "\",\"domain\":\"" + domain + "\",\"target_agent\":{\"id\":\"" + target
                + "\"},\"dedup_key\":\"" + dedup + "\"}}";
    }

    @Test
    void l1HandsOffToL2ByIntentMappingAndNormalizesResult() {
        // 直接目标缺省 → intent 映射解析（spec 3.2 / 7.2 一级转调二级）
        controllerSays(handoffLine("L1_TO_L2", "intent_flight", "flight", "", "d1"),
                "{\"event\":\"end\"}");
        RecordingObserver observer = new RecordingObserver();
        handler().streamQuery(request("订机票"), observer);
        assertThat(caller.calls).hasSize(1);
        assertThat(caller.calls.get(0).agentName()).isEqualTo("agent_card_flight");
        assertThat(caller.calls.get(0).contextId()).isEqualTo("conv-i"); // 会话连续性
        assertThat(observer.completed).isTrue();
        assertThat(observer.error).isNull(); // 转调消息未被映射为 FAILED
    }

    @Test
    void l2FallsBackToFixedL1Entry() {
        // 二级退回一级（spec 3.4 / 7.2）
        controllerSays(handoffLine("L2_TO_L1", "", "", "", "d2"), "{\"event\":\"end\"}");
        RecordingObserver observer = new RecordingObserver();
        handler().streamQuery(request("不属于本域"), observer);
        assertThat(caller.calls).hasSize(1);
        assertThat(caller.calls.get(0).agentName()).isEqualTo("agent_card_l1");
        assertThat(caller.calls.get(0).message()).isEqualTo("不属于本域"); // 传递当前用户输入
        assertThat(observer.completed).isTrue();
    }

    @Test
    void gatewayFailureMapsToTargetUnavailableWithErrorPayload() {
        controllerSays(handoffLine("L1_TO_L2", "intent_flight", "flight", "agent_card_flight", "d3"),
                "{\"event\":\"end\"}");
        caller.failure = new RuntimeException("gateway 502");
        RecordingObserver observer = new RecordingObserver();
        handler().streamQuery(request("订机票"), observer);
        assertThat(observer.completed).isFalse(); // 不返回空 COMPLETED
        String payload = String.valueOf(observer.chunks.get(0).getData());
        assertThat(payload).contains("VERSATILE_HANDOFF_TARGET_UNAVAILABLE").contains("agent_card_flight");
        assertThat(observer.error).isNotNull();
    }

    @Test
    void duplicateHandoffMessageDeduplicatedWithinRequest() {
        controllerSays(
                handoffLine("L1_TO_L2", "intent_flight", "flight", "agent_card_flight", "same-key"),
                handoffLine("L1_TO_L2", "intent_flight", "flight", "agent_card_flight", "same-key"),
                "{\"event\":\"end\"}");
        RecordingObserver observer = new RecordingObserver();
        handler().streamQuery(request("订机票"), observer);
        assertThat(caller.calls).hasSize(1); // 同请求去重
        assertThat(observer.completed).isTrue();
    }

    @Test
    void errorPayloadNeverCarriesRawControllerMessage() {
        // masking（spec 7.4）：错误载荷不含原始报文/用户内容
        controllerSays(handoffLine("L1_TO_L2", "intent_flight", "flight", "agent_card_rogue", "d5"),
                "{\"event\":\"end\"}");
        RecordingObserver observer = new RecordingObserver();
        handler().streamQuery(request("我的工号是 990011 请保密"), observer);
        String payload = String.valueOf(observer.chunks.get(0).getData());
        assertThat(payload).contains("VERSATILE_HANDOFF_TARGET_NOT_ALLOWED");
        assertThat(payload).doesNotContain("990011").doesNotContain("handoff_type");
    }

    @Test
    void multipleDistinctHandoffLinesOnlyFirstConsumed() {
        // 同流内多条不同转调消息：只有首个命中被消费（hit.compareAndSet）；
        // LOOP_LIMIT 的运行时验证由 ControllerHandoffExecutorTest.loopLimitProducesTypedError 单元层覆盖
        controllerSays(
                handoffLine("T", "i1", "", "agent_card_hotel", "k1"),
                handoffLine("T", "i2", "", "agent_card_flight", "k2"),
                "{\"event\":\"end\"}");
        RecordingObserver observer = new RecordingObserver();
        handler().streamQuery(request("多消息"), observer);
        assertThat(caller.calls).hasSize(1); // 只有首个命中被消费
        assertThat(caller.calls.get(0).agentName()).isEqualTo("agent_card_hotel");
        assertThat(observer.completed).isTrue();
    }
}
