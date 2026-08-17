package com.openjiuwen.service.adapters.agentcore.ext.middleware.otel.egress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openjiuwen.service.app.controller.a2a.client.A2ARemoteAgentCardRegistry;
import com.openjiuwen.service.app.controller.a2a.client.RemoteAgentCaller;
import com.openjiuwen.service.app.controller.a2a.client.RemoteCall;
import com.openjiuwen.service.app.controller.a2a.client.RemoteCallOutcome;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class OtelRemoteAgentCallerDecoratorTest {

    private final InMemorySpanExporter exporter = InMemorySpanExporter.create();
    private final SdkTracerProvider provider = SdkTracerProvider.builder()
            .addSpanProcessor(SimpleSpanProcessor.create(exporter))
            .build();
    private final A2ARemoteAgentCardRegistry registry = new A2ARemoteAgentCardRegistry() {
        @Override
        public String resolveUrl(String name) {
            return "http://remote:9001/a2a";
        }
    };

    @AfterEach
    void cleanup() {
        EgressContextStash.remove("conv-1");
    }

    private OtelRemoteAgentCallerDecorator decorator(RemoteAgentCaller delegate) {
        return new OtelRemoteAgentCallerDecorator(delegate, provider.get("test"), registry);
    }

    private RemoteCall versatileCall() {
        return new RemoteCall("versatile-agent",
                "{\"query_intent\":\"理财推荐\",\"query_description\":\"推荐基金\",\"query\":\"推荐基金\",\"intent\":\"理财推荐\"}",
                "conv-1", null, Map.of());
    }

    @Test
    void versatileCall_producesVaSpanWithStashedParent() {
        io.opentelemetry.api.trace.Span parentSpan = provider.get("test").spanBuilder("chain.X").startSpan();
        EgressContextStash.put("conv-1", parentSpan.storeInContext(Context.root()));

        RemoteAgentCaller delegate = (call, observer) -> CompletableFuture.completedFuture(
                new RemoteCallOutcome("rt-1", null, "ok", "工作流结果", null));
        decorator(delegate).callOutcome(versatileCall(), null).join();
        parentSpan.end();

        assertThat(exporter.getFinishedSpanItems()).hasSize(2);
        SpanData va = exporter.getFinishedSpanItems().stream()
                .filter(s -> s.getName().equals("service.versatile_adapter")).findFirst().orElseThrow();
        SpanData chain = exporter.getFinishedSpanItems().stream()
                .filter(s -> s.getName().equals("chain.X")).findFirst().orElseThrow();
        assertThat(va.getKind()).isEqualTo(SpanKind.CLIENT);
        assertThat(va.getTraceId()).isEqualTo(chain.getTraceId());
        assertThat(va.getParentSpanId()).isEqualTo(chain.getSpanId());
        var attrs = va.getAttributes();
        assertThat(attrs.get(AttributeKey.stringKey("openjiuwen.va.query_intent"))).isEqualTo("理财推荐");
        assertThat(attrs.get(AttributeKey.stringKey("openjiuwen.va.query_description"))).isEqualTo("推荐基金");
        assertThat(attrs.get(AttributeKey.stringKey("openjiuwen.va.status"))).isEqualTo("completed");
        assertThat(attrs.get(AttributeKey.stringKey("openjiuwen.va.response_summary"))).isEqualTo("工作流结果");
        assertThat(attrs.get(AttributeKey.longKey("openjiuwen.va.elapsed_ms"))).isNotNull();
    }

    @Test
    void dispatchCall_producesDispatchSpanWithRegistryUrl() {
        EgressContextStash.put("conv-1", Context.root());
        RemoteAgentCaller delegate = (call, observer) -> CompletableFuture.completedFuture(
                new RemoteCallOutcome("rt-2", null, "ok", "ok", null));
        RemoteCall call = new RemoteCall("fund_agent", "查询基金", "conv-1", null, Map.of());
        decorator(delegate).callOutcome(call, null).join();

        SpanData span = exporter.getFinishedSpanItems().get(0);
        assertThat(span.getName()).isEqualTo("sub_agent.dispatch");
        var attrs = span.getAttributes();
        assertThat(attrs.get(AttributeKey.stringKey("openjiuwen.subagent.entity_id"))).isEqualTo("fund_agent");
        assertThat(attrs.get(AttributeKey.stringKey("openjiuwen.subagent.query"))).isEqualTo("查询基金");
        assertThat(attrs.get(AttributeKey.stringKey("openjiuwen.subagent.sub_agent_url")))
                .isEqualTo("http://remote:9001/a2a");
        assertThat(attrs.get(AttributeKey.stringKey("openjiuwen.subagent.context_id"))).isEqualTo("conv-1");
        assertThat(attrs.get(AttributeKey.stringKey("openjiuwen.subagent.sub_task_path")))
                .isEqualTo("[\"conv-1\",\"fund_agent\"]");
    }

    @Test
    void futureException_marksError() {
        EgressContextStash.put("conv-1", Context.root());
        RemoteAgentCaller delegate = (call, observer) -> {
            CompletableFuture<RemoteCallOutcome> f = new CompletableFuture<>();
            f.completeExceptionally(new IllegalStateException("remote down"));
            return f;
        };
        decorator(delegate).callOutcome(versatileCall(), null)
                .handle((o, e) -> null).join();
        SpanData span = exporter.getFinishedSpanItems().get(0);
        assertThat(span.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        assertThat(span.getAttributes().get(AttributeKey.stringKey("openjiuwen.va.status"))).isEqualTo("error");
    }

    @Test
    void delegateThrows_spanEndedAndRethrown() {
        EgressContextStash.put("conv-1", Context.root());
        RemoteAgentCaller delegate = (call, observer) -> {
            throw new IllegalStateException("boom");
        };
        assertThatThrownBy(() -> decorator(delegate).callOutcome(versatileCall(), null))
                .isInstanceOf(IllegalStateException.class);
        assertThat(exporter.getFinishedSpanItems()).hasSize(1);
        assertThat(exporter.getFinishedSpanItems().get(0).getStatus().getStatusCode())
                .isEqualTo(StatusCode.ERROR);
    }

    @Test
    void stash_prefixMatchForCombinedContextId() {
        Context ctx = Context.root();
        EgressContextStash.put("conv-1", ctx);
        assertThat(EgressContextStash.find("conv-1")).contains(ctx);
        assertThat(EgressContextStash.find("conv-1_batch-uuid_call_1")).contains(ctx);
        assertThat(EgressContextStash.findConversationId("conv-1_batch-uuid_call_1")).contains("conv-1");
        assertThat(EgressContextStash.find("other")).isEmpty();
        EgressContextStash.remove("conv-1");
        assertThat(EgressContextStash.find("conv-1")).isEmpty();
    }
}
