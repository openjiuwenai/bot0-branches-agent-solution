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
import com.openjiuwen.service.spec.dto.QueryResponse;
import com.openjiuwen.service.spec.spi.QueryStreamObserver;
import com.openjiuwen.service.spec.dto.ServeRequest;

import com.sun.net.httpserver.HttpServer;
import org.a2aproject.sdk.spec.TaskState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

class ControllerHandoffAgentHandlerTest {

    static final class RecordingObserver implements QueryStreamObserver {
        final List<QueryChunk> chunks = new ArrayList<>();
        boolean completed;
        Throwable error;

        @Override public void onNext(QueryChunk chunk) { chunks.add(chunk); }
        @Override public void onError(Throwable error) { this.error = error; }
        @Override public void onComplete() { this.completed = true; }
    }

    static class FakeCaller implements RemoteAgentCaller {
        RemoteCall lastCall;
        RemoteCallOutcome outcome = new RemoteCallOutcome("rt-1", TaskState.TASK_STATE_COMPLETED,
                "COMPLETED", "下游答案", null, null);

        @Override
        public CompletableFuture<RemoteCallOutcome> callOutcome(RemoteCall call, EventObserver eventObserver) {
            lastCall = call;
            return CompletableFuture.completedFuture(outcome);
        }
    }

    public static final class EmptyProvider<T> implements ObjectProvider<T> {
        @Override public T getObject() { throw new IllegalStateException("none"); }
        @Override public T getObject(Object... args) { return null; }
        @Override public T getIfAvailable() { return null; }
        @Override public T getIfUnique() { return null; }
        @Override public Iterator<T> iterator() { return List.<T>of().iterator(); }
    }

    public static final class SingleProvider<T> implements ObjectProvider<T> {
        private final T value;
        public SingleProvider(T value) { this.value = value; }
        @Override public T getObject() { return value; }
        @Override public T getObject(Object... args) { return value; }
        @Override public T getIfAvailable() { return value; }
        @Override public T getIfUnique() { return value; }
        @Override public Iterator<T> iterator() { return List.of(value).iterator(); }
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
        p.setSelfAgentId("agent_card_l1");
        p.getTarget().setAllowedAgents(List.of("agent_card_hotel", "agent_card_l1"));
        p.getTarget().setFixedL1Entry("agent_card_l1");
        return p;
    }

    private ControllerHandoffAgentHandler handler(FakeCaller caller, ControllerHandoffProperties handoffProps) {
        return new ControllerHandoffAgentHandler(versatileProperties(),
                new IntentHandoffClassifier(handoffProps), new HandoffLoopGuard(handoffProps),
                new SingleProvider<>(new ControllerHandoffExecutor(caller,
                        new HandoffTargetResolver(handoffProps), new DownstreamEventMapper(),
                        new HandoffLoopGuard(handoffProps), new EmptyProvider<>(), handoffProps)),
                handoffProps);
    }

    private ServeRequest request() {
        ServeRequest r = new ServeRequest();
        r.setConversationId("conv-1");
        r.setStream(true);
        r.setMetadata(new HashMap<>());
        r.setMessages(List.of(Map.of("role", "user", "content", "订机票")));
        return r;
    }

    @Test
    void normalAnswerPassesThroughWithoutRemoteCall() {
        controllerResponds(
                "{\"node_name\":\"custom_rsp_node\",\"custom_rsp_data\":{\"data\":{\"response_content\":\"本地答案\"}}}",
                "{\"event\":\"end\"}");
        FakeCaller caller = new FakeCaller();
        RecordingObserver observer = new RecordingObserver();
        handler(caller, handoffProperties()).streamQuery(request(), observer);
        assertThat(caller.lastCall).isNull(); // 未命中转调不发起外部调用
        assertThat(observer.completed).isTrue();
        assertThat(observer.error).isNull();
    }

    @Test
    void realExceptionStillMappedByBaseline() {
        controllerResponds(
                "{\"event\":\"exception\",\"data\":{\"code\":-1,\"text\":\"workflow failed\"}}",
                "{\"event\":\"end\"}");
        FakeCaller caller = new FakeCaller();
        RecordingObserver observer = new RecordingObserver();
        handler(caller, handoffProperties()).streamQuery(request(), observer);
        assertThat(caller.lastCall).isNull(); // 真正异常不误判为转调
        assertThat(observer.error).isNotNull(); // 走基线错误映射
    }

    @Test
    void handoffLineTriggersRemoteCallAndDrivesTerminal() {
        controllerResponds(
                "{\"data\":{\"code\":14000,\"handoff_type\":\"L1_TO_L2\",\"intent_id\":\"intent_flight\","
                        + "\"domain\":\"flight\",\"target_agent\":{\"id\":\"agent_card_hotel\"}}}",
                "{\"event\":\"end\"}");
        FakeCaller caller = new FakeCaller();
        RecordingObserver observer = new RecordingObserver();
        handler(caller, handoffProperties()).streamQuery(request(), observer);
        assertThat(caller.lastCall).isNotNull();
        assertThat(caller.lastCall.agentName()).isEqualTo("agent_card_hotel");
        assertThat(caller.lastCall.message()).isEqualTo("订机票");
        assertThat(caller.lastCall.taskId()).isNull();
        assertThat(observer.completed).isTrue(); // COMPLETED 经 onComplete 隐式表达
        assertThat(observer.error).isNull();
        assertThat(observer.chunks).isEmpty(); // 不产生 COMPLETED chunk；转调消息不外泄
    }

    @Test
    void contractViolationProducesTypedError() {
        controllerResponds(
                "{\"data\":{\"code\":14000}}", // 命中识别但配置的提取字段缺失
                "{\"event\":\"end\"}");
        FakeCaller caller = new FakeCaller();
        RecordingObserver observer = new RecordingObserver();
        handler(caller, handoffProperties()).streamQuery(request(), observer);
        assertThat(caller.lastCall).isNull();
        assertThat(String.valueOf(observer.chunks.get(0).getData()))
                .contains("VERSATILE_HANDOFF_MESSAGE_CONTRACT");
        assertThat(observer.error).isNotNull();
    }

    @Test
    void missingExecutorYieldsCallerUnavailable() {
        controllerResponds(
                "{\"data\":{\"code\":14000,\"handoff_type\":\"L1_TO_L2\",\"intent_id\":\"i\","
                        + "\"domain\":\"flight\",\"target_agent\":{\"id\":\"agent_card_hotel\"}}}",
                "{\"event\":\"end\"}");
        ControllerHandoffProperties hp = handoffProperties();
        ControllerHandoffAgentHandler handler = new ControllerHandoffAgentHandler(versatileProperties(),
                new IntentHandoffClassifier(hp), new HandoffLoopGuard(hp),
                new EmptyProvider<>(), hp);
        RecordingObserver observer = new RecordingObserver();
        handler.streamQuery(request(), observer);
        assertThat(String.valueOf(observer.chunks.get(0).getData()))
                .contains("VERSATILE_HANDOFF_CALLER_UNAVAILABLE");
        assertThat(observer.error).isNotNull();
    }

    @Test
    void inboundRouteTraceLoopRejectedBeforeControllerCall() {
        controllerResponds("{\"data\":{\"code\":14000}}", "{\"event\":\"end\"}");
        FakeCaller caller = new FakeCaller();
        RecordingObserver observer = new RecordingObserver();
        ControllerHandoffAgentHandler h = handler(caller, handoffProperties());
        ServeRequest r = request();
        r.getMetadata().put("handoffHopCount", 2);
        r.getMetadata().put("handoffRouteTrace", List.of("agent_card_l1", "agent_card_hotel"));
        h.streamQuery(r, observer);
        assertThat(caller.lastCall).isNull();
        assertThat(String.valueOf(observer.chunks.get(0).getData()))
                .contains("VERSATILE_HANDOFF_DUPLICATE_TARGET");
        assertThat(observer.error).isNotNull();
    }

    @Test
    void nonStreamQueryAggregatesHandoffAnswer() {
        controllerResponds(
                "{\"data\":{\"code\":14000,\"handoff_type\":\"L1_TO_L2\",\"intent_id\":\"intent_flight\","
                        + "\"domain\":\"flight\",\"target_agent\":{\"id\":\"agent_card_hotel\"}}}",
                "{\"event\":\"end\"}");
        FakeCaller caller = new FakeCaller();
        // 非流式调用：无增量事件，终答经 outcome.result 归一下发
        ServeRequest nonStream = request();
        nonStream.setStream(false);
        QueryResponse response = handler(caller, handoffProperties()).query(nonStream);
        assertThat(caller.lastCall).isNotNull();
        assertThat(String.valueOf(((Map<?, ?>) response.getResult()).get("content")))
                .isEqualTo("下游答案");
    }
}
