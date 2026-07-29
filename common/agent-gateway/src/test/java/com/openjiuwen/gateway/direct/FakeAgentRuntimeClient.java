/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.gateway.direct;

import com.openjiuwen.gateway.governance.GovernanceException;

import java.util.ArrayList;
import java.util.List;
import java.util.Spliterators;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * Test double for {@link AgentRuntimeClient}. Returns a canned response body and
 * records the endpoint + body it was invoked with, so tests can assert the
 * forwarded tenant injection and target endpoint.
 *
 * @since 0.1.0
 */
public class FakeAgentRuntimeClient implements AgentRuntimeClient {
    private String response = "{}";
    private List<String> frames = new ArrayList<>();
    private String lastEndpoint;
    private String lastBody;
    private boolean neverClosingStream;
    private GovernanceException streamException;

    /**
     * Configure the canned response body returned by invokeSync.
     *
     * @param response canned JSON-RPC response body
     */
    public void setResponse(String response) {
        this.response = response;
    }

    /**
     * Configure the frames returned by openStream.
     *
     * @param frames SSE data payloads to stream
     */
    public void setFrames(List<String> frames) {
        this.frames = frames;
    }

    /**
     * When set, {@code openStreamByRef} returns a stream whose iterator blocks forever
     * (interruptible) — simulates a runtime that accepts SubscribeToTask but never sends a
     * frame and never closes (e.g. an already-terminal task whose subscription hangs).
     *
     * @param neverClosingStream when {@code true}, the stream never produces a frame
     */
    public void setNeverClosingStream(boolean neverClosingStream) {
        this.neverClosingStream = neverClosingStream;
    }

    /**
     * When set, {@code openStreamByRef} throws this exception — simulates a runtime that rejects
     * the SubscribeToTask subscription (e.g. HTTP 4xx from the A2A framework).
     *
     * @param streamException the exception to throw, or {@code null} to clear
     */
    public void setStreamException(GovernanceException streamException) {
        this.streamException = streamException;
    }

    /**
     * Return the endpoint recorded from the last invokeSync/openStream call.
     *
     * @return the endpoint of the last invokeSync
     */
    public String lastEndpoint() {
        return lastEndpoint;
    }

    /**
     * Return the body recorded from the last invokeSync/openStream call.
     *
     * @return the body of the last invokeSync (after tenant injection)
     */
    public String lastBody() {
        return lastBody;
    }

    /**
     * Clear recorded invocations (so tests asserting "no call" start clean).
     */
    public void reset() {
        this.lastEndpoint = null;
        this.lastBody = null;
    }

    @Override
    public String invokeSync(String endpointUrl, String jsonRpcBody) {
        this.lastEndpoint = endpointUrl;
        this.lastBody = jsonRpcBody;
        return response;
    }

    @Override
    public Stream<String> openStream(String endpointUrl, String jsonRpcBody) {
        this.lastEndpoint = endpointUrl;
        this.lastBody = jsonRpcBody;
        return new ArrayList<>(frames).stream();
    }

    @Override
    public Stream<String> openStreamByRef(String endpointUrl, String streamRef, String taskId, String tenantId) {
        this.lastEndpoint = endpointUrl;
        this.lastBody = "SubscribeToRef:" + streamRef + ":" + taskId;
        if (streamException != null) {
            throw streamException;
        }
        if (neverClosingStream) {
            // Iterator that blocks on hasNext until interrupted (simulates a non-closing runtime).
            var blocking = new java.util.Iterator<String>() {
                @Override
                public boolean hasNext() {
                    try {
                        Thread.sleep(Long.MAX_VALUE);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return false;
                }

                @Override
                public String next() {
                    return null;
                }
            };
            return StreamSupport.stream(Spliterators.spliteratorUnknownSize(blocking, 0), false);
        }
        return new ArrayList<>(frames).stream();
    }
}
