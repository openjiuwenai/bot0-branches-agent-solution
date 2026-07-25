/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.versatile.intent.routecache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Verifies parsing of {@code a2a_delegate} payloads and synthetic payload
 * construction in {@link A2aDelegatePayload}.
 *
 * @since 2026-07-25
 */
class A2aDelegatePayloadTest {
    @Test
    void fromResultMapExtractsAgentNameAndResponseContent() {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("_interrupt_kind", "a2a_delegate");
        context.put("agentName", "agent_card_layer2_hotel");
        context.put("resume", false);
        Map<String, Object> interrupt = new LinkedHashMap<>();
        interrupt.put("agentName", "agent_card_layer2_hotel");
        interrupt.put("responseContent", "L1 output");
        interrupt.put("resume", false);
        interrupt.put("context", context);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("_interrupt", interrupt);

        Optional<A2aDelegatePayload.Parsed> parsed = A2aDelegatePayload.fromResultMap(result);
        assertTrue(parsed.isPresent());
        assertEquals("agent_card_layer2_hotel", parsed.get().agentName());
        assertEquals("L1 output", parsed.get().responseContent());
    }

    @Test
    void fromResultMapReturnsEmptyWhenNoInterrupt() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("role", "assistant");
        result.put("content", "plain answer");
        assertTrue(A2aDelegatePayload.fromResultMap(result).isEmpty());
    }

    @Test
    void fromResultMapReturnsEmptyWhenInterruptIsNotA2aDelegate() {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("_interrupt_kind", "user_input");
        Map<String, Object> interrupt = new LinkedHashMap<>();
        interrupt.put("context", context);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("_interrupt", interrupt);
        assertTrue(A2aDelegatePayload.fromResultMap(result).isEmpty());
    }

    @Test
    void fromChunkDataExtractsFromA2aDelegateChunk() {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("_interrupt_kind", "a2a_delegate");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("agentName", "agentX");
        payload.put("responseContent", "rc");
        payload.put("context", context);
        Optional<A2aDelegatePayload.Parsed> parsed = A2aDelegatePayload.fromChunkData(payload);
        assertTrue(parsed.isPresent());
        assertEquals("agentX", parsed.get().agentName());
    }

    @Test
    void fromChunkDataReturnsEmptyForNonMapData() {
        assertTrue(A2aDelegatePayload.fromChunkData("string").isEmpty());
        assertTrue(A2aDelegatePayload.fromChunkData(null).isEmpty());
    }

    @Test
    void fromChunkDataReturnsEmptyWhenContextMissing() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("agentName", "agentX");
        assertTrue(A2aDelegatePayload.fromChunkData(payload).isEmpty());
    }

    @Test
    void buildSyntheticPayloadHasAllRequiredFields() {
        Map<String, Object> payload = A2aDelegatePayload.buildSyntheticPayload(
                "agent_card_layer2_hotel", "", "我要订酒店", true);
        // type + toolCallId are required by A2AEnabledServeOrchestrator.isCoordinatorInterrupt
        assertEquals("__interaction__", payload.get("type"));
        Object toolCallIdObj = payload.get("toolCallId");
        assertInstanceOf(String.class, toolCallIdObj);
        assertTrue(toolCallIdObj instanceof String,
                "toolCallId must be a String before downcasting");
        String toolCallId = (String) toolCallIdObj;
        assertTrue(toolCallId.startsWith("versatile-delegate-"),
                "toolCallId must follow the versatile-delegate-<uuid> shape");
        assertEquals("agent_card_layer2_hotel", payload.get("agentName"));
        assertEquals("", payload.get("responseContent"));
        assertEquals(false, payload.get("resume"));
        assertEquals("我要订酒店", payload.get("message"));
        assertEquals("sse", payload.get("_stream_mode"));
        Object contextObj = payload.get("context");
        assertInstanceOf(Map.class, contextObj);
        @SuppressWarnings("unchecked")
        Map<String, Object> ctx = (Map<String, Object>) contextObj;
        assertEquals("a2a_delegate", ctx.get("_interrupt_kind"));
        assertEquals("agent_card_layer2_hotel", ctx.get("agentName"));
        assertEquals(false, ctx.get("resume"));
    }

    @Test
    void buildSyntheticPayloadGeneratesFreshToolCallIdPerCall() {
        Map<String, Object> a = A2aDelegatePayload.buildSyntheticPayload("agentX", "", "q", false);
        Map<String, Object> b = A2aDelegatePayload.buildSyntheticPayload("agentX", "", "q", false);
        assertNotEquals(a.get("toolCallId"), b.get("toolCallId"),
                "toolCallId must be a fresh UUID per call so the orchestrator can correlate batches");
    }

    @Test
    void buildSyntheticPayloadNonStreamSetsEmptyStreamMode() {
        Map<String, Object> payload = A2aDelegatePayload.buildSyntheticPayload(
                "agentX", "", "q", false);
        assertEquals("", payload.get("_stream_mode"));
    }
}
