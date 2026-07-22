/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.gateway.direct;

/**
 * Test double for {@link AgentRuntimeClient}. Returns a canned response body and
 * records the endpoint + body it was invoked with, so tests can assert the
 * forwarded tenant injection and target endpoint.
 *
 * @since 0.1.0
 */
public class FakeAgentRuntimeClient implements AgentRuntimeClient {
    private String response = "{}";
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

    /** @return the endpoint of the last invokeSync */
    public String lastEndpoint() {
        return lastEndpoint;
    }

    /** @return the body of the last invokeSync (after tenant injection) */
    public String lastBody() {
        return lastBody;
    }

    @Override
    public String invokeSync(String endpointUrl, String jsonRpcBody) {
        this.lastEndpoint = endpointUrl;
        this.lastBody = jsonRpcBody;
        return response;
    }
}
