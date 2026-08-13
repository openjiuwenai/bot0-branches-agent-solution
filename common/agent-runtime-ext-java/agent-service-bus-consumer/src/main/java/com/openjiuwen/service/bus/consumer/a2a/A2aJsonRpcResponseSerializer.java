/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.bus.consumer.a2a;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.a2aproject.sdk.jsonrpc.common.json.JsonUtil;
import org.a2aproject.sdk.jsonrpc.common.wrappers.A2AMessage;
import org.a2aproject.sdk.jsonrpc.common.wrappers.SendMessageResponse;
import org.a2aproject.sdk.spec.EventKind;
import org.a2aproject.sdk.spec.StreamingEventKind;

/** Serializes standard A2A success responses owned by the Agent Bus adapter. */
public final class A2aJsonRpcResponseSerializer {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private A2aJsonRpcResponseSerializer() {
    }

    /** Serializes a complete {@code SendMessage} JSON-RPC response. */
    public static String sendMessage(Object requestId, EventKind result)
            throws org.a2aproject.sdk.jsonrpc.common.json.JsonProcessingException {
        return serialize(new SendMessageResponse(requestId, result));
    }

    /** Serializes one standard A2A streaming event as a JSON-RPC response frame. */
    public static String streamingEvent(Object requestId, StreamingEventKind event)
            throws org.a2aproject.sdk.jsonrpc.common.json.JsonProcessingException {
        String requestIdJson = GSON.toJson(requestId);
        return "{\"jsonrpc\":\"" + A2AMessage.JSONRPC_VERSION + "\",\"id\":" + requestIdJson
                + ",\"result\":" + serialize(event) + "}";
    }

    /** Serializes a {@code GetTask} JSON-RPC success response. */
    public static String queryResult(Object requestId, Object result)
            throws org.a2aproject.sdk.jsonrpc.common.json.JsonProcessingException {
        JsonElement resultElement = JsonParser.parseString(JsonUtil.toJson(result));
        JsonObject object = resultElement.getAsJsonObject();
        if (object.size() == 1) {
            String key = object.keySet().iterator().next();
            if ("task".equals(key) || "message".equals(key) || "statusUpdate".equals(key)
                    || "artifactUpdate".equals(key)) {
                resultElement = object.get(key);
            }
        }
        String idPart = requestId != null ? ",\"id\":" + GSON.toJson(requestId) : "";
        return "{\"jsonrpc\":\"2.0\"" + idPart + ",\"result\":" + GSON.toJson(resultElement) + "}";
    }

    /** Serializes an A2A SDK value without HTML-safe Unicode escaping. */
    public static String serialize(Object value)
            throws org.a2aproject.sdk.jsonrpc.common.json.JsonProcessingException {
        return GSON.toJson(JsonParser.parseString(JsonUtil.toJson(value)));
    }
}
