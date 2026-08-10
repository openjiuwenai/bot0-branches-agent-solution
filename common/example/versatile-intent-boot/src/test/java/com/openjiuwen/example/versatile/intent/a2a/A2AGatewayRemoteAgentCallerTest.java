/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.versatile.intent.a2a;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.openjiuwen.service.app.controller.a2a.client.RemoteAgentCaller.EventObserver;
import com.openjiuwen.service.app.controller.a2a.client.RemoteCall;
import com.openjiuwen.service.app.controller.a2a.client.RemoteCallOutcome;

import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.AgentInterface;
import org.a2aproject.sdk.spec.TaskArtifactUpdateEvent;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TaskStatusUpdateEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * Unit tests for {@link A2AGatewayRemoteAgentCaller}, covering header
 * propagation, ephemeral card construction, and JSON-RPC {@code message/send}
 * dispatch against a WireMock-hosted A2A Gateway.
 *
 * @since 0.1.0
 */
class A2AGatewayRemoteAgentCallerTest {
    private A2AGatewayProperties props;
    private A2AGatewayCardResolver resolver;
    private final Map<String, String> upstreamHeaders = new HashMap<>();

    @BeforeEach
    void setUp() {
        props = new A2AGatewayProperties();
        props.setEnabled(true);
        props.setBaseUrl("https://gateway.example.com");
        props.setToken("tok-abc");
        props.setVersionNode("v1.2.3");
        resolver = new A2AGatewayCardResolver(props);
        upstreamHeaders.clear();
    }

    @AfterEach
    void tearDown() {
        upstreamHeaders.clear();
    }

    /**
     * Builds a caller that resolves upstream headers from the in-test map
     * instead of {@link org.springframework.web.context.request.RequestContextHolder},
     * so unit tests can exercise trace propagation without a servlet scope.
     *
     * @return a caller wired to the test's upstream header map
     */
    private A2AGatewayRemoteAgentCaller newCaller() {
        return new A2AGatewayRemoteAgentCaller(props, resolver) {
            @Override
            Optional<String> resolveUpstreamHeader(String name) {
                return Optional.ofNullable(upstreamHeaders.get(name));
            }
        };
    }

    @Test
    void buildHeadersIncludesTokenUserIdVersionNodeAndTrace() {
        upstreamHeaders.put("X-B3-TraceId", "trace-xyz");
        upstreamHeaders.put("X-B3-SpanId", "span-001");
        upstreamHeaders.put("X-B3-Sampled", "1");
        upstreamHeaders.put("X-Biz-Tag", "hotel-flow");

        A2AGatewayRemoteAgentCaller caller = newCaller();
        Map<String, String> headers = caller.buildHeaders("u-42");

        assertThat(headers).containsEntry("token", "tok-abc");
        assertThat(headers).containsEntry("userId", "u-42");
        assertThat(headers).containsEntry("versionNode", "v1.2.3");
        assertThat(headers).containsEntry("X-B3-TraceId", "trace-xyz");
        assertThat(headers).containsEntry("X-B3-Sampled", "1");
        assertThat(headers).containsEntry("X-Biz-Tag", "hotel-flow");
        // ParentSpanId mirrors the upstream SpanId
        assertThat(headers).containsEntry("X-B3-ParentSpanId", "span-001");
        // Outbound SpanId is freshly generated (UUID hex, 32 chars, no dashes)
        String outboundSpanId = headers.get("X-B3-SpanId");
        assertThat(outboundSpanId).isNotBlank();
        assertThat(outboundSpanId).hasSize(32);
        assertThat(outboundSpanId).doesNotContain("-");
        assertThat(outboundSpanId).isNotEqualTo("span-001");
    }

    @Test
    void buildHeadersOmitsUserIdAndTraceWhenAbsent() {
        props.setVersionNode(null);
        A2AGatewayRemoteAgentCaller caller = newCaller();

        Map<String, String> headers = caller.buildHeaders(null);

        assertThat(headers).containsEntry("token", "tok-abc");
        assertThat(headers).doesNotContainKey("userId");
        assertThat(headers).doesNotContainKey("versionNode");
        // No upstream request bound → no propagated trace headers, and no parent span
        assertThat(headers).doesNotContainKey("X-B3-TraceId");
        assertThat(headers).doesNotContainKey("X-B3-ParentSpanId");
        assertThat(headers).doesNotContainKey("X-B3-Sampled");
        assertThat(headers).doesNotContainKey("X-Biz-Tag");
        // Outbound SpanId is always generated, even without an upstream request
        assertThat(headers).containsKey("X-B3-SpanId");
    }

    @Test
    void buildHeadersOmitsParentSpanIdWhenUpstreamSpanIdAbsent() {
        upstreamHeaders.put("X-B3-TraceId", "trace-xyz");
        // upstream X-B3-SpanId intentionally absent

        A2AGatewayRemoteAgentCaller caller = newCaller();
        Map<String, String> headers = caller.buildHeaders("u-42");

        assertThat(headers).containsEntry("X-B3-TraceId", "trace-xyz");
        assertThat(headers).doesNotContainKey("X-B3-ParentSpanId");
        assertThat(headers).containsKey("X-B3-SpanId");
    }

    @Test
    void buildEphemeralCardUsesUppercaseJsonrpcProtocolBinding() {
        A2AGatewayRemoteAgentCaller caller = new A2AGatewayRemoteAgentCaller(props, resolver);
        AgentCard card = caller.buildEphemeralCard("agent_card_L2_hotel",
                "https://gateway.example.com/a2a/agent_card_L2_hotel", true);

        assertThat(card.supportedInterfaces()).hasSize(1);
        AgentInterface iface = card.supportedInterfaces().get(0);
        assertThat(iface.protocolBinding()).isEqualTo("JSONRPC");
        assertThat(iface.url()).isEqualTo("https://gateway.example.com/a2a/agent_card_L2_hotel");
    }

    @Test
    void callOutcomePostsToGatewayA2AUrlWithRequiredHeaders() throws Exception {
        WireMockServer wireMock = new WireMockServer(options().dynamicPort());
        try {
            wireMock.start();
            int port = wireMock.port();
            props.setBaseUrl("http://localhost:" + port);
            props.setStreaming(false);
            A2AGatewayRemoteAgentCaller caller = new A2AGatewayRemoteAgentCaller(props, resolver);

            wireMock.stubFor(post(urlPathMatching("/a2a/.*"))
                    .willReturn(aResponse()
                            .withHeader("Content-Type", "application/json")
                            .withBody("{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"result\":{\"task\":{"
                                    + "\"id\":\"task-1\","
                                    + "\"contextId\":\"ctx-1\","
                                    + "\"status\":{\"state\":\"TASK_STATE_COMPLETED\"},"
                                    + "\"artifacts\":[{\"artifactId\":\"art-1\",\"parts\":[{\"text\":\"酒店预订成功：上海今晚五星\","
                                    + "\"metadata\":{},\"filename\":\"\",\"mediaType\":\"\"}]}]"
                                    + "}}}")));

            RemoteCall call = new RemoteCall("agent_card_L2_hotel", "订酒店", "c-1", null,
                    Map.of("userId", "u-42"));
            CapturingObserver observer = new CapturingObserver();
            RemoteCallOutcome outcome = caller.callOutcome(call, observer)
                    .get(10, TimeUnit.SECONDS);

            wireMock.verify(postRequestedFor(urlPathMatching("/a2a/agent_card_L2_hotel"))
                    .withHeader("token", equalTo("tok-abc"))
                    .withHeader("userId", equalTo("u-42"))
                    .withHeader("versionNode", equalTo("v1.2.3"))
                    .withHeader("Content-Type", equalTo("application/json")));

            assertThat(outcome.remoteState()).isEqualTo(TaskState.TASK_STATE_COMPLETED);
            assertThat(outcome.result()).isEqualTo("酒店预订成功：上海今晚五星");
            assertThat(observer.artifacts).singleElement().satisfies(update -> {
                assertThat(update.taskId()).isEqualTo("task-1");
                assertThat(update.artifact().artifactId()).isEqualTo("art-1");
                assertThat(update.append()).isFalse();
                assertThat(update.lastChunk()).isTrue();
            });
            assertThat(observer.statuses).extracting(update -> update.status().state())
                    .containsExactly(TaskState.TASK_STATE_COMPLETED);
        } finally {
            wireMock.stop();
        }
    }

    @Test
    void callOutcomeSendsJsonRpcMessageSendEnvelope() throws Exception {
        WireMockServer wireMock = new WireMockServer(options().dynamicPort());
        try {
            wireMock.start();
            int port = wireMock.port();
            props.setBaseUrl("http://localhost:" + port);
            props.setStreaming(false);
            A2AGatewayRemoteAgentCaller caller = new A2AGatewayRemoteAgentCaller(props, resolver);

            wireMock.stubFor(post(urlPathMatching("/a2a/.*"))
                    .willReturn(aResponse()
                            .withHeader("Content-Type", "application/json")
                            .withBody("{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"result\":{\"task\":{"
                                    + "\"id\":\"task-1\","
                                    + "\"contextId\":\"ctx-1\","
                                    + "\"status\":{\"state\":\"TASK_STATE_COMPLETED\"}"
                                    + "}}}")));

            RemoteCall call = new RemoteCall("agent_card_L2_hotel", "订酒店", "c-1", null,
                    Map.of("trace", "abc"));
            CapturingObserver observer = new CapturingObserver();
            caller.callOutcome(call, observer).get(10, TimeUnit.SECONDS);

            // The SDK generates UUIDs for id/messageId/contextId; assert only on stable fields.
            wireMock.verify(postRequestedFor(urlPathMatching("/a2a/agent_card_L2_hotel"))
                    .withRequestBody(containing("\"method\":\"SendMessage\""))
                    .withRequestBody(containing("\"role\":\"ROLE_USER\""))
                    .withRequestBody(containing("\"text\":\"订酒店\""))
                    .withRequestBody(containing("\"metadata\":{\"trace\":\"abc\"}")));
        } finally {
            wireMock.stop();
        }
    }

    @Test
    void callOutcomeCompletesWithInputRequiredForInterruptedTask() throws Exception {
        WireMockServer wireMock = new WireMockServer(options().dynamicPort());
        try {
            wireMock.start();
            int port = wireMock.port();
            props.setBaseUrl("http://localhost:" + port);
            props.setStreaming(false);
            A2AGatewayRemoteAgentCaller caller = new A2AGatewayRemoteAgentCaller(props, resolver);

            wireMock.stubFor(post(urlPathMatching("/a2a/.*"))
                    .willReturn(aResponse()
                            .withHeader("Content-Type", "application/json")
                            .withBody("{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"result\":{\"task\":{"
                                    + "\"id\":\"task-99\","
                                    + "\"contextId\":\"ctx-1\","
                                    + "\"status\":{\"state\":\"TASK_STATE_INPUT_REQUIRED\",\"message\":{"
                                    + "\"messageId\":\"msg-1\",\"contextId\":\"ctx-1\",\"taskId\":\"task-99\","
                                    + "\"role\":\"ROLE_AGENT\",\"parts\":[{\"text\":\"请提供入住日期\","
                                    + "\"metadata\":{},\"filename\":\"\",\"mediaType\":\"\"}],"
                                    + "\"metadata\":{},\"extensions\":[],\"referenceTaskIds\":[]}}"
                                    + "}}}")));

            RemoteCall call = new RemoteCall("agent_card_L2_hotel", "订酒店", "c-1", null, null);
            CapturingObserver observer = new CapturingObserver();
            RemoteCallOutcome outcome = caller.callOutcome(call, observer)
                    .get(10, TimeUnit.SECONDS);

            assertThat(outcome.remoteState()).isEqualTo(TaskState.TASK_STATE_INPUT_REQUIRED);
            assertThat(outcome.inputPrompt()).isEqualTo("请提供入住日期");
            assertThat(outcome.remoteTaskId()).isEqualTo("task-99");
            assertThat(observer.statuses).singleElement().satisfies(update -> {
                assertThat(update.status().state()).isEqualTo(TaskState.TASK_STATE_INPUT_REQUIRED);
                assertThat(update.status().message()).isNotNull();
            });
        } finally {
            wireMock.stop();
        }
    }

    @Test
    void callOutcomeFailsFastWhenTokenMissing() {
        props.setToken(null);
        A2AGatewayRemoteAgentCaller caller = new A2AGatewayRemoteAgentCaller(props, resolver);

        RemoteCall call = new RemoteCall("agent_card_L2_hotel", "hi", "c-1", null, null);
        CapturingObserver observer = new CapturingObserver();
        CompletableFuture<RemoteCallOutcome> future = caller.callOutcome(call, observer);

        assertThatThrownBy(() -> future.get(10, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasMessageContaining("token is not configured");
    }

    private static final class CapturingObserver implements EventObserver {
        final List<TaskStatusUpdateEvent> statuses = new ArrayList<>();
        final List<TaskArtifactUpdateEvent> artifacts = new ArrayList<>();

        @Override
        public void onStatus(TaskStatusUpdateEvent event) {
            statuses.add(event);
        }

        @Override
        public void onArtifact(TaskArtifactUpdateEvent event) {
            artifacts.add(event);
        }
    }

    @Test
    void callOutcomePreservesIntentEnvelopeForClientSideToolCalling() throws Exception {
        WireMockServer wireMock = new WireMockServer(options().dynamicPort());
        try {
            wireMock.start();
            props.setBaseUrl("http://localhost:" + wireMock.port());
            props.setStreaming(false);
            A2AGatewayRemoteAgentCaller caller = new A2AGatewayRemoteAgentCaller(props, resolver);
            String envelope = "{\"type\":\"answer\",\"payload\":{\"output\":\"酒店预订成功\"},"
                    + "\"intent_id\":\"intent-hotel\"}";
            String escapedEnvelope = envelope.replace("\\", "\\\\").replace("\"", "\\\"");
            wireMock.stubFor(post(urlPathMatching("/a2a/.*"))
                    .willReturn(aResponse().withHeader("Content-Type", "application/json")
                            .withBody("{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"result\":{\"task\":{"
                                    + "\"id\":\"task-1\",\"contextId\":\"ctx-1\","
                                    + "\"status\":{\"state\":\"TASK_STATE_COMPLETED\"},"
                                    + "\"artifacts\":[{\"artifactId\":\"art-1\",\"parts\":[{\"text\":\""
                                    + escapedEnvelope
                                    + "\",\"metadata\":{},\"filename\":\"\",\"mediaType\":\"\"}]}]}}}")));

            RemoteCallOutcome outcome = caller.callOutcome(
                    new RemoteCall("agent_card_L2_hotel", "订酒店", "c-1", null, Map.of()),
                    new CapturingObserver()).get(10, TimeUnit.SECONDS);

            assertThat(outcome.result()).isEqualTo(envelope);
        } finally {
            wireMock.stop();
        }
    }

    @Test
    void completedTaskWithoutArtifactsUsesStatusMessage() throws Exception {
        WireMockServer wireMock = new WireMockServer(options().dynamicPort());
        try {
            wireMock.start();
            props.setBaseUrl("http://localhost:" + wireMock.port());
            props.setStreaming(false);
            A2AGatewayRemoteAgentCaller caller = new A2AGatewayRemoteAgentCaller(props, resolver);
            wireMock.stubFor(post(urlPathMatching("/a2a/.*"))
                    .willReturn(aResponse().withHeader("Content-Type", "application/json")
                            .withBody("{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"result\":{\"task\":{"
                                    + "\"id\":\"task-1\",\"contextId\":\"ctx-1\","
                                    + "\"status\":{\"state\":\"TASK_STATE_COMPLETED\",\"message\":{"
                                    + "\"messageId\":\"msg-1\",\"contextId\":\"ctx-1\","
                                    + "\"taskId\":\"task-1\",\"role\":\"ROLE_AGENT\","
                                    + "\"parts\":[{\"text\":\"status result\",\"metadata\":{},"
                                    + "\"filename\":\"\",\"mediaType\":\"\"}],\"metadata\":{},"
                                    + "\"extensions\":[],\"referenceTaskIds\":[]}},\"artifacts\":[]}}}")));

            RemoteCallOutcome outcome = caller.callOutcome(
                    new RemoteCall("status-agent", "hello", "ctx-1", null, Map.of()), new CapturingObserver())
                    .get(10, TimeUnit.SECONDS);

            assertThat(outcome.remoteState()).isEqualTo(TaskState.TASK_STATE_COMPLETED);
            assertThat(outcome.result()).isEqualTo("status result");
        } finally {
            wireMock.stop();
        }
    }

    @Test
    void failedTaskStatusMessageOverridesEarlierArtifact() throws Exception {
        WireMockServer wireMock = new WireMockServer(options().dynamicPort());
        try {
            wireMock.start();
            props.setBaseUrl("http://localhost:" + wireMock.port());
            props.setStreaming(false);
            A2AGatewayRemoteAgentCaller caller = new A2AGatewayRemoteAgentCaller(props, resolver);
            wireMock.stubFor(post(urlPathMatching("/a2a/.*"))
                    .willReturn(aResponse().withHeader("Content-Type", "application/json")
                            .withBody("{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"result\":{\"task\":{"
                                    + "\"id\":\"task-1\",\"contextId\":\"ctx-1\","
                                    + "\"status\":{\"state\":\"TASK_STATE_FAILED\",\"message\":{"
                                    + "\"messageId\":\"msg-1\",\"contextId\":\"ctx-1\","
                                    + "\"taskId\":\"task-1\",\"role\":\"ROLE_AGENT\","
                                    + "\"parts\":[{\"text\":\"declined\",\"metadata\":{},"
                                    + "\"filename\":\"\",\"mediaType\":\"\"}],\"metadata\":{},"
                                    + "\"extensions\":[],\"referenceTaskIds\":[]}},"
                                    + "\"artifacts\":[{\"artifactId\":\"art-1\","
                                    + "\"parts\":[{\"text\":\"premature\",\"metadata\":{},"
                                    + "\"filename\":\"\",\"mediaType\":\"\"}]}]}}}")));

            RemoteCallOutcome outcome = caller.callOutcome(
                    new RemoteCall("failed-agent", "hello", "ctx-1", null, Map.of()), new CapturingObserver())
                    .get(10, TimeUnit.SECONDS);

            assertThat(outcome.remoteState()).isEqualTo(TaskState.TASK_STATE_FAILED);
            assertThat(outcome.result()).isEqualTo("declined");
        } finally {
            wireMock.stop();
        }
    }

    @Test
    void callOutcomeAcceptsTasklessMessageWithoutPublishingSyntheticTaskEvents() throws Exception {
        WireMockServer wireMock = new WireMockServer(options().dynamicPort());
        try {
            wireMock.start();
            props.setBaseUrl("http://localhost:" + wireMock.port());
            props.setStreaming(false);
            A2AGatewayRemoteAgentCaller caller = new A2AGatewayRemoteAgentCaller(props, resolver);
            wireMock.stubFor(post(urlPathMatching("/a2a/.*"))
                    .willReturn(aResponse().withHeader("Content-Type", "application/json")
                            .withBody("{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"result\":{\"message\":{"
                                    + "\"messageId\":\"message-1\",\"contextId\":\"context-1\","
                                    + "\"role\":\"ROLE_AGENT\",\"parts\":[{\"text\":\"hello \","
                                    + "\"metadata\":{},\"filename\":\"\",\"mediaType\":\"\"},{"
                                    + "\"text\":\"world\",\"metadata\":{},\"filename\":\"\","
                                    + "\"mediaType\":\"\"}],\"metadata\":{},\"extensions\":[],"
                                    + "\"referenceTaskIds\":[]}}}")));
            CapturingObserver observer = new CapturingObserver();

            RemoteCallOutcome outcome = caller.callOutcome(
                    new RemoteCall("message-agent", "hello", "conversation-1", null, Map.of()), observer)
                    .get(10, TimeUnit.SECONDS);

            assertThat(outcome.remoteState()).isEqualTo(TaskState.TASK_STATE_COMPLETED);
            assertThat(outcome.remoteTaskId()).isNull();
            assertThat(outcome.result()).isEqualTo("hello world");
            assertThat(observer.statuses).isEmpty();
            assertThat(observer.artifacts).isEmpty();
        } finally {
            wireMock.stop();
        }
    }
}
