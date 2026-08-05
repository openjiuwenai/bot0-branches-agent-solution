/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.pev.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.agents.pev.agent.PEVAgent;
import com.openjiuwen.agents.pev.agent.PevComponents;
import com.openjiuwen.agents.pev.observability.PevTrace;
import com.openjiuwen.core.session.stream.StreamMode;
import com.openjiuwen.core.singleagent.schema.AgentCard;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Real-LLM e2e for the {@code stream()} path (vs {@link StraightThroughE2eTest}'s {@code invoke()} path).
 *
 * <p><b>Honest boundary (PEVAgent.stream)</b>: {@code stream} is a degraded stub —
 * {@code List.of(invoke(input, session)).iterator()} (one chunk = the full invoke result); real
 * streaming needs the Executor to emit a {@code Flux} of node results (documented in PEVAgent javadoc).
 * So this test does NOT exercise real incremental streaming; it confirms the stream() code path
 * works end-to-end over a real LLM (no crash) and — the new value with MR !68 — that the
 * {@link PevTrace} observability fires through the stream path (stream → invoke → emitTrace).
 *
 * <p>Soft assertions (real-LLM e2e, 铁律① honesty split): output non-empty + contains the tool
 * artifact. The hard control-flow断言 live in mock tests.
 *
 * <p>Env required: {@code OPENJIUWEN_API_KEY} / {@code OPENJIUWEN_BASE_URL} / {@code OPENJIUWEN_MODEL}.
 * Skipped via {@link org.junit.jupiter.api.Assumptions#assumeTrue} when absent.
 */
class PevStreamingE2eTest {
    private static final LlmClient LLM = new LlmClient();

    @Test
    void streamPathProducesChunkAndFiresTraceOverRealLlm() {
        org.junit.jupiter.api.Assumptions
                .assumeTrue(LlmClient.envPresent(), "OPENJIUWEN_API_KEY 未设，跳过真 LLM e2e");

        String task = "查询案件 CLM-2026-REDUCE 的状态、基础信息与定责结论。";

        Map<String, String> toolDescriptions = new LinkedHashMap<>();
        toolDescriptions.put("getClaimInfo", ClaimTools.descriptions().get("getClaimInfo"));
        PevComponents.Planner planner = new LlmPlanner(LLM, toolDescriptions);

        Map<String, Function<Map<String, Object>, String>> tools = new LinkedHashMap<>();
        tools.put("getClaimInfo", ClaimTools.all().get("getClaimInfo"));
        PevComponents.Executor executor = new ToolBackedExecutor(LLM, tools);

        PevComponents.Verifier verifier = new LlmVerifier(LLM);

        List<PevTrace> traces = new ArrayList<>();
        PEVAgent agent = new PEVAgent(AgentCard.builder().build(), planner, executor, verifier, traces::add);

        // stream path (degraded stub: one chunk = invoke result; modes ignored today)
        Iterator<Object> chunks = agent.stream(task, null, List.of(StreamMode.OUTPUT));
        List<String> collected = new ArrayList<>();
        chunks.forEachRemaining(c -> collected.add(String.valueOf(c)));

        // Soft断言: stream produced at least one chunk + carries the tool artifact.
        assertThat(collected).as("stream() must produce at least one chunk over real LLM").isNotEmpty();
        String output = String.join("", collected);
        assertThat(output).as("streamed output must be non-empty").isNotEmpty();
        assertThat(output).as("streamed output must carry the getClaimInfo tool artifact")
                .containsAnyOf("CLM-2026-REDUCE", "REDUCE", "liability", "责任", "案件");

        // New observability-via-stream check (MR !68): the trace must fire through the stream path
        // (stream → invoke → emitTrace). Proves the observability SPI is reachable from both entry points.
        assertThat(traces).as("PevTrace must fire through the stream path (stream delegates to invoke → emitTrace)")
                .hasSize(1);
        assertThat(traces.get(0).phases())
                .as("trace phases must be non-empty (stream path exercised the state machine)")
                .isNotEmpty();
    }
}
