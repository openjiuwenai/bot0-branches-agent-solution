/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.versatile.controller.handoff;

import com.openjiuwen.service.adapters.versatile.autoconfigure.VersatileProperties;
import com.openjiuwen.service.adapters.versatile.controller.handoff.autoconfigure.ControllerHandoffProperties;
import com.openjiuwen.service.spec.dto.QueryChunk;
import com.openjiuwen.service.spec.dto.QueryResponse;
import com.openjiuwen.service.spec.spi.QueryStreamObserver;
import com.openjiuwen.service.spec.dto.ServeRequest;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

class ControllerHandoffAgentHandlerTest {

    static final class RecordingObserver implements QueryStreamObserver {
        final List<QueryChunk> chunks = new ArrayList<>();
        boolean completed;
        Throwable error;

        @Override public void onNext(QueryChunk chunk) { chunks.add(chunk); }
        @Override public void onError(Throwable error) { this.error = error; }
        @Override public void onComplete() { this.completed = true; }
    }

    private HttpServer server;
    private String controllerUrl;

    @BeforeEach
    void startController() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        controllerUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopController() {
        server.stop(0);
    }

    private void controllerResponds(String... sseLines) {
        server.createContext("/", exchange -> {
            byte[] body = String.join("\n", sseLines).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
    }

    private VersatileProperties versatileProperties() {
        VersatileProperties p = new VersatileProperties();
        p.setUrlTemplate(controllerUrl + "/v1/proj/agents/agent-1/conversations/{conversation_id}");
        p.setTimeout(Duration.ofSeconds(5));
        return p;
    }

    private ControllerHandoffProperties handoffProperties() {
        ControllerHandoffProperties p = new ControllerHandoffProperties();
        ControllerHandoffProperties.Classify c = new ControllerHandoffProperties.Classify();
        c.setFieldPath("/data/code");
        c.setFieldValue(List.of("14000"));
        p.setClassify(c);
        ControllerHandoffProperties.Fields f = p.getFields();
        f.setHandoffType("/data/handoff_type");
        f.setIntentId("/data/intent_id");
        f.setBusinessDomain("/data/domain");
        f.setTargetAgentId("/data/target_agent/id");
        p.setForwardMetadataKeys(List.of("customCtx"));
        p.getTarget().setAllowedAgents(List.of("agent_card_hotel", "agent_card_flight", "agent_card_l1"));
        p.getTarget().setIntentMapping(Map.of("intent_flight", "agent_card_flight"));
        return p;
    }

    private ControllerHandoffAgentHandler handler(ControllerHandoffProperties handoffProps) {
        return new ControllerHandoffAgentHandler(versatileProperties(),
                new IntentHandoffClassifier(handoffProps),
                new HandoffTargetResolver(handoffProps), handoffProps);
    }

    private ServeRequest request(String userQuery) {
        ServeRequest r = new ServeRequest();
        r.setConversationId("conv-1");
        r.setStream(true);
        r.setMetadata(new HashMap<>());
        r.setMessages(List.of(Map.of("role", "user", "content", userQuery)));
        return r;
    }

    /** 生产 message 报文形态的转调行（field 值随参数化）。 */
    private String handoffLine(String handoffType, String intentId, String domain,
            String targetAgentId) {
        return "{\"data\":{\"code\":14000,\"handoff_type\":\"" + handoffType
                + "\",\"intent_id\":\"" + intentId + "\",\"domain\":\"" + domain
                + "\",\"target_agent\":{\"id\":\"" + targetAgentId + "\"}}}";
    }

    @Test
    void normalAnswerPassesThroughWithoutRemoteCall() {
        controllerResponds(
                "{\"node_name\":\"custom_rsp_node\",\"custom_rsp_data\":{\"data\":{\"response_content\":\"本地答案\"}}}",
                "{\"event\":\"end\"}");
        RecordingObserver observer = new RecordingObserver();
        handler(handoffProperties()).streamQuery(request("订机票"), observer);
        assertThat(observer.completed).isTrue();
        assertThat(observer.error).isNull();
    }

    @Test
    void realExceptionStillMappedByBaseline() {
        controllerResponds(
                "{\"event\":\"exception\",\"data\":{\"code\":-1,\"text\":\"workflow failed\"}}",
                "{\"event\":\"end\"}");
        RecordingObserver observer = new RecordingObserver();
        handler(handoffProperties()).streamQuery(request("订机票"), observer);
        assertThat(observer.error).isNotNull(); // 走基线错误映射
    }

    @Test
    void handoffLineEmitsA2aDelegateInterruptInsteadOfOutboundCall() {
        controllerResponds(handoffLine("L1_TO_L2", "intent_flight", "flight", ""),
                "{\"event\":\"end\"}");
        RecordingObserver observer = new RecordingObserver();
        handler(handoffProperties()).streamQuery(request("订机票"), observer);
        assertThat(observer.completed).isTrue();
        assertThat(observer.error).isNull();
        assertThat(observer.chunks).hasSize(1);
        assertThat(observer.chunks.get(0).getType()).isEqualTo(QueryChunk.TYPE_INTERRUPT);
        Map<?, ?> payload = (Map<?, ?>) observer.chunks.get(0).getData();
        assertThat(payload.get("type")).isEqualTo("__interaction__");
        assertThat((String) payload.get("toolCallId")).startsWith("handoff:agent_card_flight:");
        assertThat(payload.get("agentName")).isEqualTo("agent_card_flight");
        assertThat(payload.get("message")).isEqualTo("订机票");
        assertThat(payload.get("resume")).isEqualTo(true);
        Map<?, ?> context = (Map<?, ?>) payload.get("context");
        assertThat(context.get("_interrupt_kind")).isEqualTo("a2a_delegate");
        assertThat(context.get("agentName")).isEqualTo("agent_card_flight");
        assertThat(context.get("resume")).isEqualTo(true);
    }

    @Test
    void handoffInterruptWritesForwardContextIntoRequestMetadata() {
        // 无跨请求轨迹体系（FEAT-002 循环保护由 re-invoke 轮的 toolCallId 无状态
        // 解析承担）：出站 metadata 只含透传键与执行上下文
        ControllerHandoffProperties hp = handoffProperties();
        controllerResponds(handoffLine("L1_TO_L2", "intent_flight", "flight", ""),
                "{\"event\":\"end\"}");
        ServeRequest req = request("订机票");
        req.setTenantId("t1");
        req.getMetadata().put("customCtx", "ctx-1");
        handler(hp).streamQuery(req, new RecordingObserver());
        assertThat(req.getMetadata().get("tenantId")).isEqualTo("t1");
        assertThat(req.getMetadata().get("customCtx")).isEqualTo("ctx-1");
        assertThat(req.getMetadata()).doesNotContainKeys(
                "handoffHopCount", "handoffRouteTrace", "sourceAgentId");
    }

    @Test
    void nonStreamQueryReturnsInterruptAsInterruptResult() {
        controllerResponds(handoffLine("L1_TO_L2", "intent_flight", "flight", ""),
                "{\"event\":\"end\"}");
        ServeRequest nonStream = request("订机票");
        nonStream.setStream(false);
        QueryResponse response = handler(handoffProperties()).query(nonStream);
        assertThat(response.getResult()).isInstanceOf(Map.class);
        Map<?, ?> result = (Map<?, ?>) response.getResult();
        assertThat(result.get("_interrupt")).isInstanceOf(Map.class);
        Map<?, ?> interrupt = (Map<?, ?>) result.get("_interrupt");
        assertThat(interrupt.get("message")).isEqualTo("订机票");
    }

    @Test
    void resolutionFailureStillProducesTypedError() {
        // 目标不在 allowed-agents：handoffLine 显式目标 agent_card_rogue
        controllerResponds(handoffLine("L1_TO_L2", "intent_flight", "flight", "agent_card_rogue"),
                "{\"event\":\"end\"}");
        RecordingObserver observer = new RecordingObserver();
        handler(handoffProperties()).streamQuery(request("订机票"), observer);
        assertThat(observer.error).isNotNull();
        assertThat(String.valueOf(observer.chunks.get(0).getData()))
                .contains("VERSATILE_HANDOFF_TARGET_NOT_ALLOWED");
    }

    @Test
    void incompleteHandoffFrameSuppressedAndBaselineCompletes() {
        // 识别命中但提取字段缺失（生产 SSE 的意图回显帧）：不报错不出站，
        // 且整行抑制——原始控制帧不透传给最终用户
        controllerResponds(
                "{\"data\":{\"code\":14000}}", // 命中识别但配置的提取字段缺失
                "{\"event\":\"end\"}");
        RecordingObserver observer = new RecordingObserver();
        handler(handoffProperties()).streamQuery(request("订机票"), observer);
        assertThat(observer.completed).isTrue();
        assertThat(observer.error).isNull();
        // 回显帧本身被抑制，不进用户流（end 等基线帧不受影响）
        assertThat(observer.chunks).noneMatch(c -> String.valueOf(c.getData()).contains("14000"));
    }

    @Test
    void reInvokeFailureFollowsBaselineErrorContractInQueryMode() {
        // 非流式 re-invoke 失败：不引入 REMOTE_* 分层错误码（客户端不识别），
        // 以基线 extractor 的 {"code","reason"} JSON 契约上抛（emitHandoffError 归一）
        ServeRequest r = request("订机票");
        r.setStream(false);
        r.getMetadata().put("runtime.remoteToolResults", Map.of(
                "handoff:agent_card_flight:abc",
                Map.of("ok", false, "code", "REMOTE_TIMEOUT", "message", "timed out after 3s")));
        IllegalStateException ex = catchThrowableOfType(() ->
                handler(handoffProperties()).query(r), IllegalStateException.class);
        assertThat(ex.getMessage()).startsWith("{\"code\":\"VERSATILE_HANDOFF_TIMEOUT\"");
        assertThat(ex.getMessage()).contains("\"reason\":\"REMOTE_TIMEOUT: timed out after 3s")
                .contains("handoff:agent_card_flight:abc");
    }

    @Test
    void reInvokeRoundIgnoresInertTraceLikeMetadataKeys() {
        // re-invoke（remoteToolResults 在场）驱动终答直通；metadata 上即便残留
        // trace 形状的键也只是惰性数据，无跨请求回环检测消费它们
        controllerResponds("{\"event\":\"end\"}");
        RecordingObserver observer = new RecordingObserver();
        ServeRequest r = request("订机票");
        r.getMetadata().put("handoffHopCount", 1);
        r.getMetadata().put("handoffRouteTrace", List.of("agent_card_l1"));
        r.getMetadata().put("sourceAgentId", "agent_card_l1");
        r.getMetadata().put("runtime.remoteToolResults",
                Map.of("handoff:agent_card_flight:abc", "二级答案"));
        handler(handoffProperties()).streamQuery(r, observer);
        assertThat(observer.completed).isTrue();
        assertThat(observer.error).isNull();
    }
}
