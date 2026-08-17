/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.ext.middleware.otel.egress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openjiuwen.core.runner.drunner.remoteclient.RemoteClient;
import com.openjiuwen.core.runner.drunner.remoteclient.RemoteClientConfig;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;

import org.junit.jupiter.api.Test;

import java.util.Iterator;
import java.util.Map;

/**
 * OtelRemoteClientDecoratorFactory 出站装饰器的单元测试。
 */
class OtelRemoteClientDecoratorFactoryTest {

    private final InMemorySpanExporter exporter = InMemorySpanExporter.create();

    private OtelRemoteClientDecoratorFactory factory() {
        SdkTracerProvider provider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        return new OtelRemoteClientDecoratorFactory(provider.get("test"));
    }

    private com.openjiuwen.core.session.Session sessionOf(String id) {
        return new com.openjiuwen.core.session.Session() {
            @Override
            public String getSessionId() {
                return id;
            }

            @Override
            public Object getState(String key) {
                return null;
            }

            @Override
            public void updateState(Map<String, Object> state) {
            }
        };
    }

    private RemoteClientConfig config() {
        RemoteClientConfig config = new RemoteClientConfig();
        config.setId("fund_agent");
        config.setName("基金理财 Agent");
        return config;
    }

    private RemoteClient okClient() {
        return new RemoteClient() {
            @Override
            public void start() {
            }

            @Override
            public void stop() {
            }

            @Override
            public boolean isStarted() {
                return true;
            }

            @Override
            public Object invoke(Map<String, Object> inputs, Double timeoutSeconds) {
                return Map.of("answer", "推荐稳健型基金");
            }

            @Override
            public Iterator<Object> stream(Map<String, Object> inputs, Double timeoutSeconds) {
                return java.util.List.<Object>of("chunk").iterator();
            }
        };
    }

    @Test
    void invoke_producesDispatchSpanWithAttributes() throws Exception {
        RemoteClient decorated = factory().decorate(config(), okClient(), null);
        Object result = decorated.invoke(Map.of("query", "推荐基金"), 10.0);
        assertThat(result).isNotNull();
        assertThat(exporter.getFinishedSpanItems()).hasSize(1);
        SpanData span = exporter.getFinishedSpanItems().get(0);
        assertThat(span.getName()).isEqualTo("sub_agent.dispatch");
        assertThat(span.getKind()).isEqualTo(SpanKind.CLIENT);
        var attrs = span.getAttributes();
        assertThat(attrs.get(AttributeKey.stringKey("openjiuwen.subagent.entity_id"))).isEqualTo("fund_agent");
        assertThat(attrs.get(AttributeKey.stringKey("openjiuwen.subagent.entity_name"))).isEqualTo("基金理财 Agent");
        assertThat(attrs.get(AttributeKey.stringKey("openjiuwen.subagent.query"))).contains("推荐基金");
        assertThat(attrs.get(AttributeKey.stringKey("openjiuwen.subagent.status"))).isEqualTo("completed");
        assertThat(attrs.get(AttributeKey.longKey("openjiuwen.subagent.elapsed_ms"))).isNotNull();
    }

    @Test
    void invoke_exception_marksError() {
        RemoteClient failing = new RemoteClient() {
            @Override
            public void start() {
            }

            @Override
            public void stop() {
            }

            @Override
            public boolean isStarted() {
                return true;
            }

            @Override
            public Object invoke(Map<String, Object> inputs, Double timeoutSeconds) throws Exception {
                throw new IllegalStateException("downstream down");
            }

            @Override
            public Iterator<Object> stream(Map<String, Object> inputs, Double timeoutSeconds) {
                throw new UnsupportedOperationException();
            }
        };
        RemoteClient decorated = factory().decorate(config(), failing, null);
        assertThatThrownBy(() -> decorated.invoke(Map.of(), 1.0))
                .isInstanceOf(IllegalStateException.class);
        SpanData span = exporter.getFinishedSpanItems().get(0);
        assertThat(span.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        assertThat(span.getAttributes().get(AttributeKey.stringKey("openjiuwen.subagent.status")))
                .isEqualTo("error");
    }

    @Test
    void dispatchSpan_carriesUrlContextIdAndTaskPath() throws Exception {
        RemoteClientConfig cfg = config();
        cfg.setUrl("http://10.0.0.1:9001/a2a");
        com.openjiuwen.core.session.SessionContextHolder.setCurrentSession(sessionOf("conv-d1"));
        try {
            RemoteClient decorated = factory().decorate(cfg, okClient(), null);
            decorated.invoke(Map.of("query", "推荐基金"), 10.0);
        } finally {
            com.openjiuwen.core.session.SessionContextHolder.setCurrentSession(null);
        }
        SpanData span = exporter.getFinishedSpanItems().get(0);
        var attrs = span.getAttributes();
        assertThat(attrs.get(AttributeKey.stringKey("openjiuwen.subagent.sub_agent_url")))
                .isEqualTo("http://10.0.0.1:9001/a2a");
        assertThat(attrs.get(AttributeKey.stringKey("openjiuwen.subagent.context_id")))
                .isEqualTo("conv-d1-sub-fund_agent");
        assertThat(attrs.get(AttributeKey.stringKey("openjiuwen.subagent.sub_task_path")))
                .isEqualTo("[\"conv-d1\",\"fund_agent\"]");
    }

    @Test
    void versatileTarget_producesVaSpanWithIntent() throws Exception {
        RemoteClientConfig vaConfig = new RemoteClientConfig();
        vaConfig.setId("versatile-agent");
        vaConfig.setName("versatile-agent");
        RemoteClient decorated = factory().decorate(vaConfig, okClient(), null);
        decorated.invoke(Map.of("query_intent", "理财推荐", "query_description", "推荐理财产品"), 10.0);
        SpanData span = exporter.getFinishedSpanItems().get(0);
        assertThat(span.getName()).isEqualTo("service.versatile_adapter");
        var attrs = span.getAttributes();
        assertThat(attrs.get(AttributeKey.stringKey("openjiuwen.va.query_intent"))).isEqualTo("理财推荐");
        assertThat(attrs.get(AttributeKey.stringKey("openjiuwen.va.query_description"))).isEqualTo("推荐理财产品");
        assertThat(attrs.get(AttributeKey.stringKey("openjiuwen.va.status"))).isEqualTo("completed");
        assertThat(attrs.get(AttributeKey.longKey("openjiuwen.va.elapsed_ms"))).isNotNull();
    }

    @Test
    void lifecycleMethods_delegateWithoutSpans() {
        RemoteClient decorated = factory().decorate(config(), okClient(), null);
        decorated.start();
        assertThat(decorated.isStarted()).isTrue();
        decorated.stop();
        assertThat(exporter.getFinishedSpanItems()).isEmpty();
    }

    @Test
    void stream_spanEndsOnlyAfterIteratorExhausted() throws Exception {
        // DFX-001 测试组 BUG-3 回归：span 生命周期跟随迭代器，覆盖实际流传输
        RemoteClient decorated = factory().decorate(config(), okClient(), null);
        Iterator<Object> iter = decorated.stream(Map.of("query", "test"), 10.0);
        assertThat(exporter.getFinishedSpanItems()).isEmpty();
        assertThat(iter.hasNext()).isTrue();
        assertThat(iter.next()).isEqualTo("chunk");
        assertThat(exporter.getFinishedSpanItems()).isEmpty();
        assertThat(iter.hasNext()).isFalse();
        // 重复 hasNext 不得重复 end
        assertThat(iter.hasNext()).isFalse();
        assertThat(exporter.getFinishedSpanItems()).hasSize(1);
        SpanData span = exporter.getFinishedSpanItems().get(0);
        assertThat(span.getAttributes().get(AttributeKey.stringKey("openjiuwen.subagent.status")))
                .isEqualTo("completed");
        assertThat(span.getAttributes().get(AttributeKey.longKey("openjiuwen.subagent.elapsed_ms")))
                .isNotNull();
    }

    @Test
    void stream_midIterationError_marksErrorAndEndsOnce() throws Exception {
        RemoteClient failing = new RemoteClient() {
            @Override
            public void start() {
            }

            @Override
            public void stop() {
            }

            @Override
            public boolean isStarted() {
                return true;
            }

            @Override
            public Object invoke(Map<String, Object> inputs, Double timeoutSeconds) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Iterator<Object> stream(Map<String, Object> inputs, Double timeoutSeconds) {
                return new Iterator<>() {
                    private int count;

                    @Override
                    public boolean hasNext() {
                        return count < 2;
                    }

                    @Override
                    public Object next() {
                        count++;
                        if (count == 2) {
                            throw new IllegalStateException("stream broken");
                        }
                        return "chunk1";
                    }
                };
            }
        };
        RemoteClient decorated = factory().decorate(config(), failing, null);
        Iterator<Object> iter = decorated.stream(Map.of(), 10.0);
        assertThat(iter.next()).isEqualTo("chunk1");
        assertThatThrownBy(iter::next).isInstanceOf(IllegalStateException.class);
        // 异常后再 hasNext 不得二次 end
        assertThat(iter.hasNext()).isFalse();
        assertThat(exporter.getFinishedSpanItems()).hasSize(1);
        SpanData span = exporter.getFinishedSpanItems().get(0);
        assertThat(span.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
        assertThat(span.getAttributes().get(AttributeKey.stringKey("openjiuwen.subagent.status")))
                .isEqualTo("error");
    }

    @Test
    void stream_delegateThrows_spanEndsWithError() {
        RemoteClient failing = new RemoteClient() {
            @Override
            public void start() {
            }

            @Override
            public void stop() {
            }

            @Override
            public boolean isStarted() {
                return true;
            }

            @Override
            public Object invoke(Map<String, Object> inputs, Double timeoutSeconds) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Iterator<Object> stream(Map<String, Object> inputs, Double timeoutSeconds) {
                throw new IllegalStateException("connect refused");
            }
        };
        RemoteClient decorated = factory().decorate(config(), failing, null);
        assertThatThrownBy(() -> decorated.stream(Map.of(), 1.0))
                .isInstanceOf(IllegalStateException.class);
        SpanData span = exporter.getFinishedSpanItems().get(0);
        assertThat(span.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
    }
}
