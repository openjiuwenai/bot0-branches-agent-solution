/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.versatile.orchestration.gateway;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Map;

/**
 * The minimal inbound payload a caller posts to the gateway: the EDPA {@code custom_data} value
 * ({@code {"inputs":{query,intent, ...fixed}}}) plus transport {@code headers}. The gateway rebuilds
 * the full EDPA envelope from {@code inputs} via {@link EdpaEnvelopeBuilder} and lifts {@code headers}
 * into A2A {@code metadata.headers} (whitelist-filtered) via {@link EdpaRequestTranslator}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EdpaInputsRequest(Map<String, Object> inputs, Map<String, Object> headers) {
    public EdpaInputsRequest {
        inputs = inputs == null ? Map.of() : inputs;
        headers = headers == null ? Map.of() : headers;
    }
}
