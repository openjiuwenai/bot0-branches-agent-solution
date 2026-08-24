/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.versatile.controller.handoff;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.service.adapters.versatile.autoconfigure.VersatileProperties;
import com.openjiuwen.service.adapters.versatile.controller.handoff.autoconfigure.ControllerHandoffProperties;
import com.openjiuwen.service.spec.dto.QueryChunk;
import com.openjiuwen.service.spec.dto.ServeRequest;
import com.openjiuwen.service.spec.spi.QueryStreamObserver;

import com.sun.net.httpserver.HttpServer;

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

/**
 * Spec 7.2 场景旅程验收：一级转调二级（a2a_delegate 中断）、二级退回一级
 * （remoteToolResults 信封重识别）、异常区分、目标不可用（REMOTE_* 映射）、
 * 弹回目标重复转调保护；以及 7.4 masking（错误载荷不携带原始报文）。
 *
 * @since 2026-08-19
 */
class ControllerHandoffIntegrationTest {
    private HttpServer server;
    private String baseUrl;
    private final List<String[]> perRequestResponses = new ArrayList<>();
    private final java.util.concurrent.atomic.AtomicInteger requestCount =
            new java.util.concurrent.atomic.AtomicInteger();

    /** 流式观察者记录：chunk 序列 + 完成/错误终态。 */
    static final class RecordingObserver implements QueryStreamObserver {
        final List<QueryChunk> chunks = new ArrayList<>();
        boolean completed;
        Throwable error;

        @Override
        public void onNext(QueryChunk chunk) {
            chunks.add(chunk);
        }

        @Override
        public void onError(Throwable error) {
            this.error = error;
        }

        @Override
        public void onComplete() {
            this.completed = true;
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
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
        hp.getTarget().setAllowedAgents(List.of("agent_card_l1", "agent_card_hotel", "agent_card_flight"));
        hp.getTarget().setIntentMapping(Map.of("intent_flight", "agent_card_flight"));
        return hp;
    }

    private ControllerHandoffAgentHandler handler(ControllerHandoffProperties hp) {
        VersatileProperties versatile = new VersatileProperties();
        versatile.setUrlTemplate(baseUrl + "/v1/p/agents/a/conversations/{conversation_id}");
        versatile.setTimeout(Duration.ofSeconds(5));
        return new ControllerHandoffAgentHandler(versatile, new IntentHandoffClassifier(hp),
                new HandoffTargetResolver(hp), hp);
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
        assertThat(String.valueOf(payload.get("toolCallId"))).startsWith("handoff:agent_card_flight:");
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
        assertThat(observer.chunks).hasSize(1); // 无出站：仅信封一个 chunk
        assertThat(observer.completed).isTrue();
        String payload = String.valueOf(observer.chunks.get(observer.chunks.size() - 1).getData());
        assertThat(payload).contains(HandoffSignals.TYPE_NOT_IN_SCOPE);
    }

    @Test
    void downstreamNotInScopeSignalReRunsControllerForReRecognition() {
        // L2 弹回 not-in-scope 信封 → 协调器 resume=true re-invoke → handler 入口识别信封、
        // 抑制不透传并重跑控制器重新识别（upstream-signal 语义平移，迁移设计稿 3）
        perRequestResponses.add(new String[] {"{\"event\":\"end\"}"}); // 重识别后无转调
        controllerSays();
        RecordingObserver observer = new RecordingObserver();
        handler().streamQuery(resumeRequest(Map.of("handoff:agent_card_flight:xx",
                HandoffSignals.notInScopeEnvelope(new IntentHandoff("L2_TO_L1", null, null, null, null, "{}")))),
                observer);
        assertThat(requestCount.get()).isEqualTo(1); // 控制器重跑了一次
        assertThat(observer.completed).isTrue();
        String joined = observer.chunks.stream().map(c -> String.valueOf(c.getData()))
                .collect(java.util.stream.Collectors.joining());
        assertThat(joined).doesNotContain(HandoffSignals.TYPE_NOT_IN_SCOPE); // 信封不透传
    }

    @Test
    void reHandoffToBouncedTargetRejectedAsDuplicateTarget() {
        // re-invoke 重识别后控制器再次转调同一（已弹回）目标：DUPLICATE_TARGET 保护
        perRequestResponses.add(new String[] {
                handoffLine("L1_TO_L2", "intent_flight", "flight", "", "k1"),
                "{\"event\":\"end\"}"});
        controllerSays();
        RecordingObserver observer = new RecordingObserver();
        handler().streamQuery(resumeRequest(Map.of("handoff:agent_card_flight:xx",
                HandoffSignals.notInScopeEnvelope(new IntentHandoff("L2_TO_L1", null, null, null, null, "{}")))),
                observer);
        assertThat(observer.error).isNotNull();
        String payload = String.valueOf(observer.chunks.get(observer.chunks.size() - 1).getData());
        assertThat(payload).contains("VERSATILE_HANDOFF_DUPLICATE_TARGET");
    }

    @Test
    void remoteToolResultsWithoutEnvelopeCompleteStream() {
        // 流式 happy-path re-invoke：内容已由协调器投影为 REMOTE_AGENT_OUTPUT，handler 只收尾
        controllerSays("{\"event\":\"end\"}"); // 若控制器被调用会拿到 end
        RecordingObserver observer = new RecordingObserver();
        handler().streamQuery(resumeRequest(Map.of("handoff:agent_card_flight:abc", "二级答案")), observer);
        assertThat(observer.completed).isTrue();
        assertThat(observer.error).isNull();
        assertThat(observer.chunks).isEmpty();
        assertThat(requestCount.get()).isEqualTo(0); // 未重跑控制器
    }

    @Test
    void remoteToolResultsFailureDrivesTypedError() {
        Map<String, Object> failure = new java.util.LinkedHashMap<>();
        failure.put("ok", false);
        failure.put("code", "REMOTE_UNAVAILABLE");
        failure.put("message", "connection refused");
        controllerSays("{\"event\":\"end\"}");
        RecordingObserver observer = new RecordingObserver();
        handler().streamQuery(resumeRequest(Map.of("handoff:agent_card_flight:abc", failure)), observer);
        assertThat(observer.error).isNotNull();
        String payload = String.valueOf(observer.chunks.get(0).getData());
        assertThat(payload).contains("VERSATILE_HANDOFF_TARGET_UNAVAILABLE");
    }

    /**
     * re-invoke 请求：协调器 buildBatchResumeRequest 把 remote 结果注入该 metadata 键。
     *
     * @param results toolCallId → remote 结果
     * @return re-invoke 服务请求
     */
    private ServeRequest resumeRequest(Map<String, Object> results) {
        ServeRequest r = request("补充后的输入");
        r.getMetadata().put("runtime.remoteToolResults", results);
        return r;
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
        assertThat(String.valueOf(payload.get("toolCallId"))).startsWith("handoff:agent_card_hotel:");
        assertThat(observer.completed).isTrue();
    }
}
