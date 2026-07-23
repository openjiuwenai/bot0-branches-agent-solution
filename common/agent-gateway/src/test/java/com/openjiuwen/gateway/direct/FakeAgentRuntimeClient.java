/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.gateway.direct;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

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

    /** @return the endpoint of the last invokeSync */
    public String lastEndpoint() {
        return lastEndpoint;
    }

    /** @return the body of the last invokeSync (after tenant injection) */
    public String lastBody() {
        return lastBody;
    }

    /** Clear recorded invocations (so tests asserting "no call" start clean). */
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
}
