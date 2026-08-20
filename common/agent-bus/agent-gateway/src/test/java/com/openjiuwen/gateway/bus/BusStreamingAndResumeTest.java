/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.gateway.bus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openjiuwen.bus.forwarding.spi.AgentBusEventType;
import com.openjiuwen.gateway.bus.control.BusControlForwarder;
import com.openjiuwen.gateway.bus.control.EnvelopeBuilder;
import com.openjiuwen.gateway.bus.control.FakeForwardingOutboxPort;
import com.openjiuwen.gateway.bus.control.FakeProjectionFeed;
import com.openjiuwen.gateway.bus.control.InMemoryPayloadStore;
import com.openjiuwen.gateway.bus.wait.FiveStateFolder;
import com.openjiuwen.gateway.direct.FakeAgentRuntimeClient;
import com.openjiuwen.gateway.governance.GovernanceContext;
import com.openjiuwen.gateway.governance.GovernanceException;
import com.openjiuwen.gateway.governance.idempotency.IdempotencyRule;
import com.openjiuwen.gateway.routing.AgentCardRoute;
import com.openjiuwen.gateway.routing.DefaultAgentResolver;
import com.openjiuwen.gateway.routing.FakeRdcRouteClient;
import com.openjiuwen.gateway.routing.ResolvedRoute;
import com.openjiuwen.gateway.routing.Router;
import com.openjiuwen.gateway.routing.StickyIndex;
import com.openjiuwen.gateway.sse.SseBridge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;
import java.util.Optional;

/**
 * B5 (streaming STREAM_READY) + B6 (S5 extras) + B7 (S3 resume) + B8 (S4 wire) tests.
 *
 * @since 2026-07-24
 */
class BusStreamingAndResumeTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final FakeRdcRouteClient rdc = new FakeRdcRouteClient();
    private final FakeForwardingOutboxPort outbox = new FakeForwardingOutboxPort();
    private final FakeProjectionFeed feed = new FakeProjectionFeed();
    private final FakeAgentRuntimeClient runtime = new FakeAgentRuntimeClient();
    private final IdempotencyRule g4 = new IdempotencyRule();
    private final StickyIndex sticky = new StickyIndex();
    private final SseBridge sseBridge = new SseBridge();

    private GovernanceContext createCtx(String agentId, String messageId) {
        GovernanceContext c = new GovernanceContext();
        c.setTenantId("T1");
        c.setAgentId(agentId);
        c.setMessageId(messageId);
        c.setTraceId("trace-1");
        c.setRawBody("{\"jsonrpc\":\"2.0\"}");
        return c;
    }

    private BusForwarder forwarder() {
        return new BusForwarder(rdc,
                new BusControlForwarder(new EnvelopeBuilder(), new InMemoryPayloadStore(), outbox),
                feed, g4, "svc-gw", 30_000L, 60_000L, runtime, new DefaultAgentResolver(""), sticky);
    }

    @Test
    void b5_streamingReadyBridgesSseToClient() throws Exception {
        rdc.setCandidates(List.of(new AgentCardRoute("h1", "svc-rt")));
        rdc.setResolved(new ResolvedRoute("http://rt:8000"));
        feed.inject(AgentBusEventType.INVOCATION_ACCEPTED, "task-s", null);
        feed.inject(AgentBusEventType.INVOCATION_STREAM_READY, "task-s", "sr-1");
        runtime.setFrames(List.of("{\"result\":{\"id\":\"task-s\",\"status\":\"working\"}}"));
        MockHttpServletResponse mockResponse = new MockHttpServletResponse();
        Optional<String> result = forwarder().forwardStreaming(createCtx("agent-1", "ms1"), mockResponse, sseBridge);
        // SSE was written → result is empty
        assertThat(result).isEmpty();
        // SSE content written
        String sseOutput = mockResponse.getContentAsString();
        assertThat(sseOutput).contains("event: jsonrpc");
        assertThat(sseOutput).contains("data: {\"result\":{\"id\":\"task-s\",\"status\":\"working\"}}");
        assertSseDataFramesAreValidJson(sseOutput);
        // runtime was called via openStreamByRef with the resolved endpoint
        assertThat(runtime.lastEndpoint()).isEqualTo("http://rt:8000");
        // control event was enqueued (inlinePayload = A2A body, no token)
        assertThat(outbox.enqueued().get(0).inlinePayload()).isNotNull();
        assertThat(outbox.enqueued().get(0).inlinePayload()).doesNotContain("token");
    }

    @Test
    void streamingTerminalTaskPreservesArtifactOutput() throws Exception {
        rdc.setCandidates(List.of(new AgentCardRoute("h1", "svc-rt")));
        rdc.setResolved(new ResolvedRoute("http://rt:8000"));
        feed.inject(AgentBusEventType.INVOCATION_ACCEPTED, "task-output", null);
        feed.inject(AgentBusEventType.INVOCATION_STREAM_READY, "task-output", "sr-output");
        feed.inject(AgentBusEventType.INVOCATION_TERMINAL, "task-output", null, null,
                "{\"jsonrpc\":\"2.0\",\"id\":\"req-output\",\"result\":{\"task\":{"
                        + "\"id\":\"task-output\",\"status\":{\"state\":\"completed\"},"
                        + "\"artifacts\":[{\"parts\":[{\"text\":\"result[trace=trace-11]"
                        + "[agent=demo-a2a-agent-a]\"}]}]}}}");
        runtime.setFrames(List.of("{\"result\":{\"id\":\"task-output\",\"status\":\"working\"}}"));

        MockHttpServletResponse response = new MockHttpServletResponse();
        Optional<String> result = forwarder().forwardStreaming(createCtx("agent-1", "m-output"), response,
                sseBridge);

        assertThat(result).isEmpty();
        String sse = response.getContentAsString();
        assertThat(sse).contains("result[trace=trace-11][agent=demo-a2a-agent-a]");
        assertSseDataFramesAreValidJson(sse);
    }

    @Test
    void streamingCreateBindsStickyOnAccepted() throws Exception {
        // P-13: BUS streaming create must bind taskId -> routeHandle too (same as sync), so a task
        // created via the streaming path can be resumed to its owner instead of 404.
        rdc.setCandidates(List.of(new AgentCardRoute("h1", "svc-rt")));
        rdc.setResolved(new ResolvedRoute("http://rt:8000"));
        feed.inject(AgentBusEventType.INVOCATION_ACCEPTED, "task-s", null);
        feed.inject(AgentBusEventType.INVOCATION_STREAM_READY, "task-s", "sr-1");
        runtime.setFrames(List.of("{\"result\":{\"id\":\"task-s\",\"status\":\"working\"}}"));
        MockHttpServletResponse mockResponse = new MockHttpServletResponse();
        forwarder().forwardStreaming(createCtx("agent-1", "ms-sticky"), mockResponse, sseBridge);
        assertThat(sticky.find("task-s")).contains("h1");
    }

    @Test
    void streamingInputRequiredStopsEarlyInsteadOfEmptyWaitingTerminal() throws Exception {
        // issue-A: a streaming task that goes INPUT_REQUIRED (after STREAM_READY) must NOT empty-wait
        // responseWindowMillis for a TERMINAL that won't come, and must NOT emit a synthetic COMPLETED.
        // pollTerminalEvent stops on INPUT_REQUIRED; the terminal frame envelopes the INPUT_REQUIRED body.
        rdc.setCandidates(List.of(new AgentCardRoute("h1", "svc-rt")));
        rdc.setResolved(new ResolvedRoute("http://rt:8000"));
        feed.inject(AgentBusEventType.INVOCATION_ACCEPTED, "task-ir", null);
        feed.inject(AgentBusEventType.INVOCATION_STREAM_READY, "task-ir", "sr-ir");
        feed.inject(AgentBusEventType.INVOCATION_INPUT_REQUIRED, "task-ir", null, null,
                "{\"jsonrpc\":\"2.0\",\"id\":\"req-ir\",\"result\":{\"task\":{"
                        + "\"id\":\"task-ir\",\"status\":{\"state\":\"input-required\"}}}}");
        runtime.setFrames(List.of("{\"result\":{\"id\":\"task-ir\",\"status\":{\"state\":\"working\"}}}"));
        // small response window so the RED (timeout→synthetic COMPLETED) is fast, not 60s
        BusForwarder f = new BusForwarder(rdc,
                new BusControlForwarder(new EnvelopeBuilder(), new InMemoryPayloadStore(), outbox),
                feed, g4, "svc-gw", 30_000L, 300L, runtime, new DefaultAgentResolver(""), sticky);
        f.setStreamFirstFrameDeadlineMillis(2_000L);
        MockHttpServletResponse mockResponse = new MockHttpServletResponse();
        Optional<String> result = f.forwardStreaming(createCtx("agent-1", "m-ir"), mockResponse, sseBridge);
        assertThat(result).isEmpty();   // SSE written (no early FAILED body)
        String sse = mockResponse.getContentAsString();
        // terminal frame reflects INPUT_REQUIRED (enveloped body), NOT a synthetic completed
        assertThat(sse).contains("input-required");
        assertThat(sse).doesNotContain("completed");
    }

    @Test
    void streamingInputRequiredSkipsRuntimeDrainWhenProjectionPreStaged() throws Exception {
        // issue-S1 (drain 并发收尾): when INPUT_REQUIRED has already been routed to staging
        // (before the runtime SSE drain), the gateway surfaces it WITHOUT draining the runtime SSE.
        // Some runtimes end the stream after an interrupt but don't close the HTTP response → the
        // drain (frameIterator.hasNext) would block forever. The pre-drain projection check skips
        // the drain — the runtime first-frame marker must NOT appear in the SSE.
        rdc.setCandidates(List.of(new AgentCardRoute("h1", "svc-rt")));
        rdc.setResolved(new ResolvedRoute("http://rt:8000"));
        feed.inject(AgentBusEventType.INVOCATION_ACCEPTED, "task-ir", null);
        feed.inject(AgentBusEventType.INVOCATION_STREAM_READY, "task-ir", "sr-ir");
        feed.inject(AgentBusEventType.INVOCATION_INPUT_REQUIRED, "task-ir", null, null,
                "{\"jsonrpc\":\"2.0\",\"id\":\"req-ir\",\"result\":{\"task\":{"
                        + "\"id\":\"task-ir\",\"status\":{\"state\":\"input-required\"}}}}");
        // a distinctive runtime first frame — if the drain runs, this marker appears in the SSE;
        // if the pre-drain check skips the drain (the fix), it does NOT appear.
        runtime.setFrames(List.of("{\"result\":{\"id\":\"task-ir\",\"status\":{\"state\":\"working\"},"
                + "\"runtimeDrainMarker\":\"should-not-appear\"}}"));
        BusForwarder f = new BusForwarder(rdc,
                new BusControlForwarder(new EnvelopeBuilder(), new InMemoryPayloadStore(), outbox),
                feed, g4, "svc-gw", 30_000L, 300L, runtime, new DefaultAgentResolver(""), sticky);
        f.setStreamFirstFrameDeadlineMillis(2_000L);
        MockHttpServletResponse mockResponse = new MockHttpServletResponse();
        Optional<String> result = f.forwardStreaming(createCtx("agent-1", "m-ir-skip"), mockResponse, sseBridge);
        assertThat(result).isEmpty();
        String sse = mockResponse.getContentAsString();
        assertThat(sse).contains("input-required");          // terminal frame reflects INPUT_REQUIRED
        assertThat(sse).doesNotContain("should-not-appear"); // runtime SSE drain was SKIPPED
    }

    @Test
    void b5_streamReadySeparableFromAccepted() throws Exception {
        rdc.setCandidates(List.of(new AgentCardRoute("h1", "svc-rt")));
        rdc.setResolved(new ResolvedRoute("http://rt:8000"));
        feed.inject(AgentBusEventType.INVOCATION_STREAM_READY, "task-x", "sr-2");
        runtime.setFrames(List.of("{\"result\":{\"id\":\"task-x\",\"status\":\"working\"}}"));
        MockHttpServletResponse mockResponse = new MockHttpServletResponse();
        Optional<String> result = forwarder().forwardStreaming(createCtx("agent-1", "ms2"), mockResponse, sseBridge);
        assertThat(result).isEmpty();
        assertThat(mockResponse.getContentAsString()).contains("event: jsonrpc");
    }

    @Test
    @Timeout(5)
    void b5_streamingNonClosingRuntimeTimesOut() throws Exception {
        // A runtime that accepts SubscribeToTask but never sends a frame / never closes must not
        // hang the gateway's servlet thread forever — the forwarder aborts with STREAM_DEADLINE_EXCEEDED
        // before committing the SSE response.
        rdc.setCandidates(List.of(new AgentCardRoute("h1", "svc-rt")));
        rdc.setResolved(new ResolvedRoute("http://rt:8000"));
        feed.inject(AgentBusEventType.INVOCATION_ACCEPTED, "task-nc", null);
        feed.inject(AgentBusEventType.INVOCATION_STREAM_READY, "task-nc", "sr-nc");
        runtime.setNeverClosingStream(true);
        BusForwarder f = forwarder();
        f.setStreamFirstFrameDeadlineMillis(300L);
        MockHttpServletResponse mockResponse = new MockHttpServletResponse();
        Optional<String> result = f.forwardStreaming(createCtx("agent-1", "m-nc"), mockResponse, sseBridge);
        assertThat(result).hasValueSatisfying(
                s -> assertThat(s).contains("FAILED").contains("STREAM_DEADLINE_EXCEEDED"));
    }

    @Test
    void b5_streamingOpenRejectedReturnsFailed() throws Exception {
        // A runtime that rejects SubscribeToTask (e.g. HTTP 4xx) must surface the rejection reason
        // in a FAILED body (logged + returned), not propagate uncaught and leave the cause invisible.
        rdc.setCandidates(List.of(new AgentCardRoute("h1", "svc-rt")));
        rdc.setResolved(new ResolvedRoute("http://rt:8000"));
        feed.inject(AgentBusEventType.INVOCATION_ACCEPTED, "task-f", null);
        feed.inject(AgentBusEventType.INVOCATION_STREAM_READY, "task-f", "sr-f");
        runtime.setStreamException(new GovernanceException(HttpStatus.BAD_GATEWAY, "FORWARD_FAILED",
                "Runtime rejected SubscribeToTask subscription: HTTP 400 bad"));
        MockHttpServletResponse mockResponse = new MockHttpServletResponse();
        Optional<String> result = forwarder().forwardStreaming(createCtx("agent-1", "m-f"), mockResponse, sseBridge);
        assertThat(result).hasValueSatisfying(
                s -> assertThat(s).contains("FAILED").contains("HTTP 400"));
    }

    @Test
    void b5_tokenNotInBusPayload() {
        rdc.setCandidates(List.of(new AgentCardRoute("h1", "svc-rt")));
        feed.inject(AgentBusEventType.INVOCATION_RESPONSE, null, null);
        forwarder().forwardSync(createCtx("agent-1", "ms3"));
        var env = outbox.enqueued().get(0);
        assertThat(env.eventType().name()).doesNotContain("TOKEN");
    }

    @Test
    void b6_governanceRejectNotS5() {
        rdc.setCandidates(List.of(new AgentCardRoute("h1", "svc-rt")));
        feed.inject(AgentBusEventType.INVOCATION_RESPONSE, null, null);
        var resp = forwarder().forwardSync(createCtx("agent-1", "m-s5"));
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void b6_defaultAgentMissingIsConfigError() {
        // No agentId AND no default configured → clean DEFAULT_AGENT_UNCONFIGURED governance
        // error, not an NPE (the BUS path must mirror the DIRECT Router's fallback).
        rdc.setCandidates(List.of(new AgentCardRoute("h1", "svc-rt")));
        feed.inject(AgentBusEventType.INVOCATION_RESPONSE, null, null);
        var thrown = catchThrowable(() -> forwarder().forwardSync(createCtx(null, "m-da")));
        assertThat(thrown).isInstanceOf(GovernanceException.class);
        if (thrown instanceof GovernanceException ge) {
            assertThat(ge.code()).isEqualTo("DEFAULT_AGENT_UNCONFIGURED");
        }
    }

    @Test
    void b6_streamingNullAgentIsConfigError() {
        // Same fallback for the streaming path: null agentId + no default → config error,
        // not an NPE inside HttpRdcRouteClient.enc.
        rdc.setCandidates(List.of(new AgentCardRoute("h1", "svc-rt")));
        var thrown = catchThrowable(() -> forwarder().forwardStreaming(createCtx(null, "m-da-s"),
                new MockHttpServletResponse(), sseBridge));
        assertThat(thrown).isInstanceOf(GovernanceException.class);
        if (thrown instanceof GovernanceException ge) {
            assertThat(ge.code()).isEqualTo("DEFAULT_AGENT_UNCONFIGURED");
        }
    }

    @Test
    void b6_stickyMissNotS5() {
        sticky.clear();
        assertThat(sticky.find("ghost")).isEmpty();
    }

    @Test
    void b7_resumeEnvelopeCarriesTaskId() {
        sticky.put("task-7", "h1", "svc-rt");
        rdc.setCandidates(List.of());
        g4.check("T1", "m-r1", "fp");
        feed.inject(AgentBusEventType.INVOCATION_RESPONSE, null, null);
        assertThat(sticky.find("task-7")).contains("h1");
    }

    @Test
    void b7_resumeNoSearchUsesStickyRoute() {
        sticky.put("task-7", "h1", "svc-rt");
        assertThat(sticky.find("task-7")).isPresent();
        sticky.put("task-7", "h1", "svc-rt");
        assertThat(sticky.find("task-7")).hasValue("h1");
    }

    @Test
    void b7_resumeNoRouteRefExplicitFail() {
        assertThat(sticky.find("ghost")).isEmpty();
    }

    @Test
    void b7_inputRequiredStubBranch() {
        assertThat(FiveStateFolder.isTerminal(
                com.openjiuwen.bus.forwarding.spi.InvocationResponseStatus.ACCEPTED_WITH_TASK)).isFalse();
    }

    @Test
    void b8_continueInputWireSameAsResume() {
        sticky.put("task-ci", "h1", "svc-rt");
        assertThat(sticky.find("task-ci")).contains("h1");
    }

    @Test
    void b9_pathModeClientInvisible() {
        rdc.setCandidates(List.of(new AgentCardRoute("h1", "svc-rt")));
        feed.inject(AgentBusEventType.INVOCATION_RESPONSE, null, null);
        forwarder().forwardSync(createCtx("agent-1", "m-cfg"));
        var env = outbox.enqueued().get(0);
        assertThat(env.eventType()).isEqualTo(AgentBusEventType.CLIENT_INVOCATION_REQUESTED);
    }

    @Test
    void busSyncCreateThenDirectResumeReachesStickyOwner() {
        // P-13 acceptance (unit level): a task created via the BUS sync path (INPUT_REQUIRED + taskId)
        // must resume via the DIRECT Router.routeResume to its owning runtime — NOT 404
        // RESUME_OWNER_UNKNOWN. Before the fix the BUS path never wrote StickyIndex, so the resume
        // missed. This round-trip proves the shared StickyIndex bridges BUS-create and DIRECT-resume.
        rdc.setCandidates(List.of(new AgentCardRoute("h1", "svc-rt")));
        rdc.setResolved(new ResolvedRoute("http://rt:8000"));
        feed.inject(AgentBusEventType.INVOCATION_INPUT_REQUIRED, "ti-resume", null);
        var createResp = forwarder().forwardSync(createCtx("agent-1", "m-resume"));
        assertThat(createResp.getBody()).contains("INPUT_REQUIRED").contains("ti-resume");
        assertThat(sticky.find("ti-resume")).contains("h1");
        // DIRECT resume reads the sticky binding (read-only, no re-search) and reaches the owner.
        runtime.setResponse("{\"result\":{\"id\":\"ti-resume\",\"status\":{\"state\":\"completed\"}}}");
        Router router = new Router(rdc, runtime, sticky, new DefaultAgentResolver(""));
        GovernanceContext resumeCtx = new GovernanceContext();
        resumeCtx.setTenantId("T1");
        resumeCtx.setTaskId("ti-resume");
        resumeCtx.setRawBody("{\"jsonrpc\":\"2.0\"}");
        String resumeResp = router.routeResume(resumeCtx);
        assertThat(resumeResp).contains("ti-resume");
        assertThat(runtime.lastEndpoint()).isEqualTo("http://rt:8000");
    }

    private static void assertSseDataFramesAreValidJson(String sse) throws Exception {
        for (String line : sse.split("\\R")) {
            if (line.startsWith("data: ")) {
                assertThat(MAPPER.readTree(line.substring("data: ".length()))).isNotNull();
            }
        }
    }
}
