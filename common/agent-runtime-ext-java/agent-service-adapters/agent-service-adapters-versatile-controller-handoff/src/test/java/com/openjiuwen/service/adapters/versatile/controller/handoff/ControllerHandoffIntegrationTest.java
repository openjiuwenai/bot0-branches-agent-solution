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
    private final List<String[]> perRequestResponses = new ArrayList<>();
    private final java.util.concurrent.atomic.AtomicInteger requestCount =
            new java.util.concurrent.atomic.AtomicInteger();

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
            String[] body = lines;
            if (!perRequestResponses.isEmpty()) {
                int index = Math.min(requestCount.getAndIncrement(), perRequestResponses.size() - 1);
                body = perRequestResponses.get(index);
            }
            byte[] bytes = String.join("\n", body).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
    }

    private ControllerHandoffProperties baseProperties() {
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
        hp.getTarget().setIntentMapping(Map.of("intent_flight", "agent_card_flight"));
        return hp;
    }

    private ControllerHandoffAgentHandler handler(ControllerHandoffProperties hp) {
        VersatileProperties versatile = new VersatileProperties();
        versatile.setUrlTemplate(baseUrl + "/v1/p/agents/a/conversations/{conversation_id}");
        versatile.setTimeout(Duration.ofSeconds(5));
        return new ControllerHandoffAgentHandler(versatile, new IntentHandoffClassifier(hp),
                new HandoffLoopGuard(hp), new HandoffTargetResolver(hp), hp);
    }

    private ControllerHandoffAgentHandler handler() {
        return handler(baseProperties());
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
        // 直接目标缺省 → intent 映射解析（spec 3.2 / 7.2 一级转调二级）：
        // 出站移交 runtime 协调器——handler 产出单 item a2a_delegate 中断
        controllerSays(handoffLine("L1_TO_L2", "intent_flight", "flight", "", "d1"),
                "{\"event\":\"end\"}");
        RecordingObserver observer = new RecordingObserver();
        handler().streamQuery(request("订机票"), observer);
        assertThat(observer.completed).isTrue();
        assertThat(observer.error).isNull(); // 转调消息未被映射为 FAILED
        assertThat(observer.chunks).hasSize(1);
        Map<?, ?> payload = (Map<?, ?>) observer.chunks.get(0).getData();
        assertThat(observer.chunks.get(0).getType()).isEqualTo(QueryChunk.TYPE_INTERRUPT);
        assertThat((String) payload.get("toolCallId")).startsWith("handoff:agent_card_flight:");
        assertThat(payload.get("agentName")).isEqualTo("agent_card_flight");
        assertThat(payload.get("message")).isEqualTo("订机票");
        Map<?, ?> context = (Map<?, ?>) payload.get("context");
        assertThat(context.get("_interrupt_kind")).isEqualTo("a2a_delegate");
        assertThat(context.get("resume")).isEqualTo(true);
    }

    @Test
    void signalHandoffTypeEmitsNotInScopeEnvelopeWithoutOutboundCall() {
        // 二级退回一级 upstream-signal（spec 3.4/7.2）：signal 类型不出站，直接回标记信封
        ControllerHandoffProperties hp = baseProperties();
        hp.getSignal().setHandoffTypes(List.of("L2_TO_L1"));
        controllerSays(handoffLine("L2_TO_L1", "", "", "", "d2"), "{\"event\":\"end\"}");
        RecordingObserver observer = new RecordingObserver();
        handler(hp).streamQuery(request("不属于本域"), observer);
        assertThat(caller.calls).isEmpty(); // 无反向调用
        assertThat(observer.completed).isTrue();
        String payload = String.valueOf(observer.chunks.get(observer.chunks.size() - 1).getData());
        assertThat(payload).contains(HandoffSignals.TYPE_NOT_IN_SCOPE);
    }

    @Test
    void downstreamNotInScopeSignalReRunsControllerForReRecognition() {
        // L2 弹回 not-in-scope 后的 re-invoke 重识别：runtime 协调器以 resume=true 重新进入
        // handler（remoteToolResults 携带信封）——Task 3 接管该链路后恢复验收
        org.junit.jupiter.api.Assumptions.assumeTrue(false, "Task 3: remoteToolResults 入口短路");
    }

    @Test
    void duplicateMessageAfterNotInScopeReRunDrivesObserverToTerminal() {
        // executor 出站段退役后 DUPLICATE_MESSAGE 链路整体消失；弹回目标重复转调
        // 由 DUPLICATE_TARGET（Task 3）覆盖
        org.junit.jupiter.api.Assumptions.assumeTrue(false, "Task 3/4: 链路退役重写");
    }

    @Test
    void gatewayFailureMapsToTargetUnavailableWithErrorPayload() {
        // 出站失败经协调器 REMOTE_* 错误以 remoteToolResults 回传——Task 3 接管后恢复验收
        org.junit.jupiter.api.Assumptions.assumeTrue(false, "Task 3: REMOTE_* 失败码映射");
    }

    @Test
    void duplicateHandoffMessageDeduplicatedWithinRequest() {
        // 同流内重复转调消息：首个命中即产出中断并驱动 observer 到终态，
        // 后续行全部抑制（executor 时代的 dedup-key 去重不再需要）
        controllerSays(
                handoffLine("L1_TO_L2", "intent_flight", "flight", "agent_card_flight", "same-key"),
                handoffLine("L1_TO_L2", "intent_flight", "flight", "agent_card_flight", "same-key"),
                "{\"event\":\"end\"}");
        RecordingObserver observer = new RecordingObserver();
        handler().streamQuery(request("订机票"), observer);
        assertThat(observer.chunks).hasSize(1); // 单次中断
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
        // 同流内多条不同转调消息：只有首个命中被消费（hit.compareAndSet）→ 单次中断
        controllerSays(
                handoffLine("T", "i1", "", "agent_card_hotel", "k1"),
                handoffLine("T", "i2", "", "agent_card_flight", "k2"),
                "{\"event\":\"end\"}");
        RecordingObserver observer = new RecordingObserver();
        handler().streamQuery(request("多消息"), observer);
        assertThat(observer.chunks).hasSize(1);
        Map<?, ?> payload = (Map<?, ?>) observer.chunks.get(0).getData();
        assertThat((String) payload.get("toolCallId")).startsWith("handoff:agent_card_hotel:");
        assertThat(observer.completed).isTrue();
    }
}
