/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.bus.consumer.a2a;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.a2aproject.sdk.jsonrpc.common.json.JsonProcessingException;
import org.a2aproject.sdk.jsonrpc.common.json.JsonUtil;
import org.a2aproject.sdk.jsonrpc.common.wrappers.A2AMessage;
import org.a2aproject.sdk.jsonrpc.common.wrappers.SendMessageResponse;
import org.a2aproject.sdk.spec.EventKind;
import org.a2aproject.sdk.spec.StreamingEventKind;

/**
 * Serializes standard A2A success responses owned by the Agent Bus adapter.
 *
 * @since 2026-08-13
 */
public final class A2aJsonRpcResponseSerializer {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private A2aJsonRpcResponseSerializer() {
    }

    /**
     * Serializes a complete {@code SendMessage} JSON-RPC response.
     *
     * @param requestId JSON-RPC request identifier
     * @param result A2A event returned by the Runtime
     * @return complete JSON-RPC response
     * @throws JsonProcessingException if the A2A value cannot be serialized
     */
    public static String sendMessage(Object requestId, EventKind result)
            throws JsonProcessingException {
        return serialize(new SendMessageResponse(requestId, result));
    }

    /**
     * Serializes one standard A2A streaming event as a JSON-RPC response frame.
     *
     * @param requestId JSON-RPC request identifier
     * @param event streaming event emitted by the Runtime
     * @return complete JSON-RPC streaming response frame
     * @throws JsonProcessingException if the A2A event cannot be serialized
     */
    public static String streamingEvent(Object requestId, StreamingEventKind event)
            throws JsonProcessingException {
        String requestIdJson = GSON.toJson(requestId);
        return "{\"jsonrpc\":\"" + A2AMessage.JSONRPC_VERSION + "\",\"id\":" + requestIdJson
                + ",\"result\":" + serialize(event) + "}";
    }

    /**
     * Serializes a {@code GetTask} JSON-RPC success response.
     *
     * @param requestId JSON-RPC request identifier
     * @param result Task query result returned by the Runtime
     * @return complete JSON-RPC response with any SDK discriminator wrapper removed
     * @throws JsonProcessingException if the Task query result cannot be serialized
     */
    public static String queryResult(Object requestId, Object result)
            throws JsonProcessingException {
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

    /**
     * Serializes an A2A SDK value without HTML-safe Unicode escaping.
     *
     * @param value A2A SDK value to serialize
     * @return JSON representation of the value
     * @throws JsonProcessingException if the value cannot be serialized
     */
    public static String serialize(Object value)
            throws JsonProcessingException {
        return GSON.toJson(JsonParser.parseString(JsonUtil.toJson(value)));
    }
}
