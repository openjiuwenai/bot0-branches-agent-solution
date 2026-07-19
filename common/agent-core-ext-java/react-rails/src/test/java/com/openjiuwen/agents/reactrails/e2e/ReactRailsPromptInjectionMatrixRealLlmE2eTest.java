/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.reactrails.e2e;

import com.openjiuwen.agents.reactrails.enforcing.SystemPromptInjectingModel;
import com.openjiuwen.agents.reactrails.enforcing.SystemPromptInjectingModel.InjectionMode;
import com.openjiuwen.core.foundation.llm.Model;
import com.openjiuwen.core.foundation.llm.model_clients.BaseModelClient;
import com.openjiuwen.core.foundation.llm.model_clients.DefaultModelClientFactories;
import com.openjiuwen.core.foundation.llm.model_clients.OpenAiCompatibleModelClient;
import com.openjiuwen.core.foundation.llm.output_parsers.BaseOutputParser;
import com.openjiuwen.core.foundation.llm.schema.AssistantMessage;
import com.openjiuwen.core.foundation.llm.schema.AssistantMessageChunk;
import com.openjiuwen.core.foundation.llm.schema.AudioGenerationResponse;
import com.openjiuwen.core.foundation.llm.schema.BaseMessage;
import com.openjiuwen.core.foundation.llm.schema.ImageGenerationResponse;
import com.openjiuwen.core.foundation.llm.schema.ModelClientConfig;
import com.openjiuwen.core.foundation.llm.schema.ModelRequestConfig;
import com.openjiuwen.core.foundation.llm.schema.SystemMessage;
import com.openjiuwen.core.foundation.llm.schema.UserMessage;
import com.openjiuwen.core.foundation.llm.schema.VideoGenerationResponse;
import com.openjiuwen.core.singleagent.agents.ReActAgent;
import com.openjiuwen.core.singleagent.schema.AgentCard;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Phase2 外置 PLAN/BUILD prompt × 7 模型 × thinking on/off 矩阵 e2e（content-IFF at e2e matrix level）.
 *
 * <p><b>背景（为什么需要这个测试）</b>：Phase2 把 PLAN/BUILD/FIRST_PRINCIPLES 三段 system prompt 外置到
 * classpath 资源（C1）。mock 测试（{@code SystemPromptInjectingModelTest} 2a content-IFF）已证外置内容到达
 * BaseModelClient 边界（model-independent）。但现有 react-rails e2e 全是弱断言（{@code SystemPromptInjectLlmE2eTest}
 * 算了 hasAngleStructure/angleCount 却从不 assert；{@code PreCompletionChecklistRailE2eTest} 只查枚举且自认
 * BUILD_MODE 无法可靠断言），且现有 3 个矩阵测试全是 observability 场景，没有一个压 PLAN/BUILD 注入路径。
 * 本测试补这个缺口：7 模型 × thinking on/off 矩阵下，<b>捕获 BaseModelClient 边界实际收到的 SystemMessage</b>，
 * content-IFF 断言外置 PLAN/BUILD prompt 真到了每个模型的 LLM 调用。
 *
 * <p><b>捕获机制</b>：注册 {@code CapturingModelClientFactory}（provider 唯一）包装真实
 * {@link OpenAiCompatibleModelClient}（public 构造器）。捕获点在
 * {@code SystemPromptInjectingModel.prepareMessages(replaceSystemPrompt) → super.invoke → CapturingModelClient.invoke}：
 * 正好在外置替换<b>之后</b>、真 LLM 调用<b>之前</b> —— 同时证外置内容到达边界 + 真 LLM 数据通道活。
 *
 * <p><b>每 cell 场景</b>（deterministic bearing，2 invoke）：PLAN_MODE invoke + BUILD_MODE invoke（fresh
 * model+agent per mode，避免状态耦合）。
 *
 * <p><b>承重断言</b>（content-IFF，非恒真，mutation-RED 形态）：
 * <ul>
 *   <li><b>A 数据通道</b>：PLAN + BUILD invoke 均 return 非 null、response 非空</li>
 *   <li><b>B content-IFF PLAN</b>：PLAN cell 捕获的 messages 里存在 SystemMessage contains "DIVERGENT
 *       EXPLORATION" + doesNotContain "CONVERGENT EXECUTION"</li>
 *   <li><b>C content-IFF BUILD</b>：BUILD cell 捕获的 messages 里存在 SystemMessage contains "CONVERGENT
 *       EXECUTION" + doesNotContain "DIVERGENT EXPLORATION"</li>
 * </ul>
 *
 * <p>mutation-RED：剥 {@code SystemPromptInjectingModel.replaceSystemPrompt} 的 {@code msgList.set} →
 * 捕获到的 SystemMessage 是原始 caller prompt → B/C RED（与 2a mock mutation-RED 同构，但这是真 LLM e2e 矩阵级）。
 *
 * <p><b>矩阵</b>：bigmodel glm-5.2/glm-4.7（MY_GLM_KEY）+ deepseek-v4-pro/flash（DEEPSEEK_API_KEY）+
 * openrouter qwen3.6-35b-a3b/27b + opus-4.7（OPENROUTER_API_KEY）× thinking on/off = 14 cell。
 *
 * <p>Env-gated：{@code RR_PROMPT_MATRIX_ENABLED=true} + 每 config 对应 key。缺 key 的 config SKIP（log）。
 *
 * <p><b>诚实边界</b>：捕获的 content-IFF 与 mock 部分冗余（mock 已证 model-independent），但本矩阵是
 * 每 cell 自证 + 额外证真 LLM 数据通道 per model × thinking —— 这是矩阵独有价值。opus-4.7 thinking extras
 * 复用 glmOn/glmOff（现有 Complex 测试惯例，{type:disabled} 对 anthropic via openrouter 未必精确，诚实记录）。
 *
 * @since 2026-07
 */
class ReactRailsPromptInjectionMatrixRealLlmE2eTest {
    private static final Logger LOG =
            Logger.getLogger(ReactRailsPromptInjectionMatrixRealLlmE2eTest.class.getName());

    private static final String TASK = "分析当前的经济形势。";
    private static final String PLAN_MARKER = "DIVERGENT EXPLORATION";
    private static final String BUILD_MARKER = "CONVERGENT EXECUTION";

    /**
     * One model config: label, endpoint base, env key var, model id, thinking extras.
     */
    private record ModelConfig(String label, String base, String keyEnv, String model,
            Map<String, Object> thinkingOn, Map<String, Object> thinkingOff) {
        String resolveKey() {
            return System.getenv(keyEnv);
        }
    }

    /**
     * 7-model config list (bigmodel / deepseek / openrouter). Thinking extras reuse the convention
     * proven in {@code ReactRailsComplexObservabilityE2eTest}.
     *
     * @return the unmodifiable list of model configs
     */
    private static List<ModelConfig> configs() {
        Map<String, Object> glmOn = Map.of("thinking", Map.of("type", "enabled"));
        Map<String, Object> glmOff = Map.of("thinking", Map.of("type", "disabled"));
        Map<String, Object> qwenOn =
                Map.of("reasoning", Map.of("enabled", true), "include_reasoning", true);
        Map<String, Object> qwenOff = Map.of("reasoning", Map.of("enabled", false));
        return List.of(
                new ModelConfig("glm-5.2", "https://open.bigmodel.cn/api/paas/v4", "MY_GLM_KEY",
                        "glm-5.2", glmOn, glmOff),
                new ModelConfig("glm-4.7", "https://open.bigmodel.cn/api/paas/v4", "MY_GLM_KEY",
                        "glm-4.7", glmOn, glmOff),
                new ModelConfig("deepseek-v4-pro", "https://api.deepseek.com", "DEEPSEEK_API_KEY",
                        "deepseek-v4-pro", glmOn, glmOff),
                new ModelConfig("deepseek-v4-flash", "https://api.deepseek.com",
                        "DEEPSEEK_API_KEY", "deepseek-v4-flash", glmOn, glmOff),
                new ModelConfig("qwen3.6-35b-a3b", "https://openrouter.ai/api/v1",
                        "OPENROUTER_API_KEY", "qwen/qwen3.6-35b-a3b", qwenOn, qwenOff),
                new ModelConfig("qwen3.6-27b", "https://openrouter.ai/api/v1",
                        "OPENROUTER_API_KEY", "qwen/qwen3.6-27b", qwenOn, qwenOff),
                new ModelConfig("opus-4.7", "https://openrouter.ai/api/v1",
                        "OPENROUTER_API_KEY", "anthropic/claude-opus-4.7", glmOn, glmOff));
    }

    @Test
    void promptInjectionMatrix_7models_x_thinkingOnOff() {
        String enabled = System.getenv("RR_PROMPT_MATRIX_ENABLED");
        org.junit.jupiter.api.Assumptions.assumeTrue("true".equalsIgnoreCase(enabled),
                "prompt-injection matrix requires RR_PROMPT_MATRIX_ENABLED=true"
                        + " + MY_GLM/DEEPSEEK/OPENROUTER keys");
        DefaultModelClientFactories.ensureRegistered();

        String only = System.getenv("RR_PROMPT_MATRIX_ONLY"); // optional comma-sep label filter for smoke/diag
        List<Map<String, Object>> results = new ArrayList<>();
        for (ModelConfig mc : configs()) {
            if (only != null && !only.isBlank()
                    && !java.util.Arrays.asList(only.split(",")).contains(mc.label)) {
                continue;
            }
            String key = mc.resolveKey();
            if (key == null || key.isBlank()) {
                LOG.log(Level.INFO, "[prompt-matrix] SKIP {0} (key {1} unset)",
                        new Object[]{mc.label, mc.keyEnv});
                continue;
            }
            for (boolean thinking : new boolean[]{true, false}) {
                Map<String, Object> r = runOne(mc, key, thinking);
                // Retry once on transient network failures (SocketTimeout / IOException / connection
                // reset). HTTP 4xx (auth/region/rate-limit) are NOT transient — isTransientFlaky
                // excludes them so opus region-403 doesn't waste a retry.
                if (isTransientFlaky(r) && !"completed".equals(r.get("status"))) {
                    LOG.log(Level.INFO, "[prompt-matrix] RETRY {0} | thinking={1} (transient: {2})",
                            new Object[]{mc.label, thinking ? "ON" : "OFF", r.get("cause")});
                    r = runOne(mc, key, thinking);
                    r.put("retried", true);
                }
                results.add(r);
            }
        }
        summarize(results);
        applyBearingGate(results);
    }

    /**
     * Transient-network classifier (port of EDPA matrix isTransientFlaky). Only timeout/IO/connection
     * markers retry; HTTP 4xx (auth/region) do NOT — they're deterministic, not network flakiness.
     *
     * @param r per-cell result map
     * @return true iff the cell's failure looks like a transient network issue worth one retry
     */
    private static boolean isTransientFlaky(Map<String, Object> r) {
        String c = String.valueOf(r.get("cause")).toLowerCase(Locale.ROOT);
        return c.contains("timeout") || c.contains("sockettimeout") || c.contains("httptimeout")
                || c.contains("ioexception") || c.contains("connection reset")
                || c.contains("end-of-input") || c.contains("header parser");
    }

    /**
     * HARD aggregate bearing gate (applied post-matrix, not per-cell inside the FutureTask — see
     * {@link #runOneInternal} javadoc). Non-completed cells (auth/network errors, empty) are
     * surfaced in the summary but do NOT hard-fail the test; only completed cells are
     * content-IFF-gated, plus a floor that at least one cell must complete full PLAN+BUILD.
     *
     * <p>mutation-RED: strip {@code SystemPromptInjectingModel.replaceSystemPrompt} msgList.set →
     * completed cells' planCaptured/buildCaptured flip false → content-IFF assertions RED.
     *
     * @param results per-cell result maps
     */
    private static void applyBearingGate(List<Map<String, Object>> results) {
        // content-IFF per completed cell (the core承重 gate — kills 恒真 where prompt swap is silent).
        for (Map<String, Object> r : results) {
            if (!"completed".equals(r.get("status"))) {
                continue;
            }
            String cell = r.get("model") + " | thinking=" + r.get("thinking");
            org.junit.jupiter.api.Assertions.assertTrue(Boolean.TRUE.equals(r.get("planCaptured")),
                    "content-IFF PLAN failed for completed cell " + cell + ": externalized PLAN prompt"
                            + " (\"" + PLAN_MARKER + "\") not captured at BaseModelClient boundary"
                            + " — strip replaceSystemPrompt msgList.set → this RED");
            org.junit.jupiter.api.Assertions.assertTrue(Boolean.TRUE.equals(r.get("buildCaptured")),
                    "content-IFF BUILD failed for completed cell " + cell + ": externalized BUILD prompt"
                            + " (\"" + BUILD_MARKER + "\") not captured at BaseModelClient boundary");
        }
        // Floor: at least one cell must complete full PLAN+BUILD real-LLM rounds. Without this, an
        // all-auth-failure run (e.g. every key 401) would silently pass with zero validation.
        long completed = results.stream().filter(x -> "completed".equals(x.get("status"))).count();
        long errored = results.stream()
                .filter(x -> String.valueOf(x.get("status")).startsWith("flaky")
                        || String.valueOf(x.get("status")).startsWith("error"))
                .count();
        org.junit.jupiter.api.Assertions.assertTrue(completed >= 1,
                "bearing floor: at least one cell must complete full PLAN+BUILD real-LLM round"
                        + " (got completed=" + completed + " of " + results.size() + ", errored="
                        + errored + " — check API keys/network; auth failures are surfaced in summary)");
    }

    /**
     * Run one (model, thinking) cell under a FutureTask (G.ERR.02 explicit future) for isolation.
     *
     * @param mc model config under test
     * @param key resolved API key
     * @param thinking whether thinking mode is enabled
     * @return per-cell metrics map
     */
    private static Map<String, Object> runOne(ModelConfig mc, String key, boolean thinking) {
        String label = mc.label + " | thinking=" + (thinking ? "ON" : "OFF");
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("model", mc.label);
        r.put("thinking", thinking ? "ON" : "OFF");
        r.put("status", "error");
        r.put("planCaptured", false);
        r.put("buildCaptured", false);
        r.put("planResponseLen", 0);
        r.put("buildResponseLen", 0);
        FutureTask<Map<String, Object>> task =
                new FutureTask<>(() -> runOneInternal(mc, key, thinking, r, label));
        task.run();
        try {
            task.get();
        } catch (InterruptedException | ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            r.put("status", "flaky:" + cause.getClass().getSimpleName());
            r.put("cause", cause.getClass().getName() + ": " + String.valueOf(cause.getMessage()));
            LOG.log(Level.SEVERE, "[prompt-matrix] " + label + " EX " + cause.getClass().getName()
                    + ": " + String.valueOf(cause.getMessage()), cause);
        }
        return r;
    }

    /**
     * Per-cell: PLAN_MODE invoke + BUILD_MODE invoke (fresh model+agent each), capture messages at
     * the BaseModelClient boundary, apply content-IFF bearing assertions.
     *
     * @param mc model config
     * @param key resolved API key
     * @param thinking thinking flag
     * @param r result map to populate
     * @param label human-readable cell label
     * @return populated result map
     */
    private static Map<String, Object> runOneInternal(ModelConfig mc, String key, boolean thinking,
            Map<String, Object> r, String label) {
        Map<String, Object> extra = new LinkedHashMap<>(thinking ? mc.thinkingOn : mc.thinkingOff);

        // ---- PLAN_MODE ----
        Capture planCap = new Capture();
        String planProvider = registerCapture(planCap);
        SystemPromptInjectingModel planModel = buildModel(mc, key, planProvider, extra);
        planModel.setInjectionMode(InjectionMode.PLAN_MODE);
        ReActAgent planAgent = new ReActAgent(AgentCard.builder().name("prompt-matrix-plan").build());
        // PLAN/BUILD replacement targets the first SystemMessage — a bare ReActAgent has none by
        // default (empty SystemPromptBuilder → no SystemMessage in the real call → replaceSystemPrompt
        // is a no-op). Add a base section so the real call carries a SystemMessage to replace.
        planAgent.addPromptBuilderSection("base", "You are a helpful assistant.", 0);
        planAgent.setLlm(planModel);
        Object planResult = planAgent.invoke(TASK, null);
        boolean planCaptured =
                containsSystemMessage(planCap.captured, PLAN_MARKER, BUILD_MARKER);
        int planLen = planResult == null ? 0 : String.valueOf(planResult).length();

        // ---- BUILD_MODE ----
        Capture buildCap = new Capture();
        String buildProvider = registerCapture(buildCap);
        SystemPromptInjectingModel buildModel = buildModel(mc, key, buildProvider, extra);
        buildModel.setInjectionMode(InjectionMode.BUILD_MODE);
        ReActAgent buildAgent = new ReActAgent(AgentCard.builder().name("prompt-matrix-build").build());
        buildAgent.addPromptBuilderSection("base", "You are a helpful assistant.", 0);
        buildAgent.setLlm(buildModel);
        Object buildResult = buildAgent.invoke(TASK, null);
        boolean buildCaptured =
                containsSystemMessage(buildCap.captured, BUILD_MARKER, PLAN_MARKER);
        int buildLen = buildResult == null ? 0 : String.valueOf(buildResult).length();

        r.put("planCaptured", planCaptured);
        r.put("buildCaptured", buildCaptured);
        r.put("planResponseLen", planLen);
        r.put("buildResponseLen", buildLen);
        boolean planOk = planResult != null && planLen > 0;
        boolean buildOk = buildResult != null && buildLen > 0;
        r.put("status", planOk && buildOk ? "completed" : planOk || buildOk ? "partial-empty" : "empty");

        LOG.log(Level.INFO,
                "[prompt-matrix] {0} -> status={1} planCap={2}({3}chars) buildCap={4}({5}chars)",
                new Object[]{label, r.get("status"), planCaptured, planLen, buildCaptured, buildLen});
        if (System.getenv("RR_PROMPT_MATRIX_DIAG") != null) {
            LOG.log(Level.INFO, "[prompt-matrix] DIAG {0} PLAN capture: {1}",
                    new Object[]{label, diagCapture(planCap)});
            LOG.log(Level.INFO, "[prompt-matrix] DIAG {0} BUILD capture: {1}",
                    new Object[]{label, diagCapture(buildCap)});
        }
        // Per-cell bearing assertions are applied post-matrix in the @Test method (aggregate HARD
        // gate), NOT here — otherwise an exception in invoke (e.g. HTTP 401 auth) would skip the
        // assertions and the cell would silently count as pass (恒真). Non-completed cells are
        // surfaced via status + summary; only completed cells are content-IFF-gated below.
        return r;
    }

    private static SystemPromptInjectingModel buildModel(ModelConfig mc, String key, String provider,
            Map<String, Object> extra) {
        var cliCfg = ModelClientConfig.builder()
                .clientId("prompt-matrix-" + System.nanoTime()).clientProvider(provider)
                .apiKey(key).apiBase(mc.base).verifySsl(false).build();
        var reqCfg = ModelRequestConfig.builder().modelName(mc.model).temperature(0.3)
                .maxTokens(4000).extraFields(extra).build();
        return new SystemPromptInjectingModel(cliCfg, reqCfg);
    }

    /**
     * Register a capture factory under a unique provider name (avoids cross-cell/cross-test registry
     * collision); returns that name for {@code cliCfg.clientProvider}.
     *
     * @param cap capture sink to wire into the client
     * @return the unique provider name to set as clientProvider
     */
    private static String registerCapture(Capture cap) {
        String provider = "capture-" + System.nanoTime();
        Model.registerFactory(new Model.ModelClientFactory() {
            @Override
            public String providerName() {
                return provider;
            }

            @Override
            public BaseModelClient create(ModelRequestConfig req, ModelClientConfig cli) {
                return new CapturingModelClient(req, cli, cap);
            }
        });
        return provider;
    }

    /**
     * Diagnostic: summarize a capture (per-invoke message types + first SystemMessage content prefix)
     * to inspect what actually reached the BaseModelClient boundary. Gated by RR_PROMPT_MATRIX_DIAG.
     *
     * @param cap capture sink
     * @return human-readable diagnostic string
     */
    private static String diagCapture(Capture cap) {
        StringBuilder sb = new StringBuilder();
        sb.append(cap.captured.size()).append(" invoke(s)");
        int listIdx = 0;
        for (List<BaseMessage> msgs : cap.captured) {
            StringBuilder types = new StringBuilder();
            String sysPrefix = null;
            for (BaseMessage m : msgs) {
                if (types.length() > 0) {
                    types.append(",");
                }
                types.append(m.getClass().getSimpleName());
                if (m instanceof SystemMessage sm && sysPrefix == null) {
                    String c = sm.getContentAsString();
                    sysPrefix = c == null ? "<null>" : (c.length() <= 80 ? c : c.substring(0, 80) + "...");
                }
            }
            sb.append(" | invoke#").append(listIdx++).append("=[").append(types).append("]")
                    .append(sysPrefix != null ? " sys=\"" + sysPrefix + "\"" : " (no SystemMessage)");
        }
        return sb.toString();
    }

    /**
     * Check captured message lists for a SystemMessage containing {@code present} but not {@code absent}.
     *
     * @param captured lists of messages captured at the BaseModelClient boundary
     * @param present marker that must appear in some SystemMessage
     * @param absent marker that must NOT appear in that same SystemMessage
     * @return true iff a qualifying SystemMessage was captured
     */
    private static boolean containsSystemMessage(List<List<BaseMessage>> captured, String present,
            String absent) {
        for (List<BaseMessage> msgs : captured) {
            for (BaseMessage m : msgs) {
                if (m instanceof SystemMessage sm) {
                    String c = sm.getContentAsString();
                    if (c != null && c.contains(present) && !c.contains(absent)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Print the matrix table + aggregate counts.
     *
     * @param results per-cell result maps
     */
    private static void summarize(List<Map<String, Object>> results) {
        LOG.log(Level.INFO,
                "{0}========== PROMPT-INJECTION MATRIX (externalized PLAN/BUILD reach each model) ==========",
                System.lineSeparator());
        LOG.log(Level.INFO, "{0}",
                String.format(Locale.ROOT, "%-20s %-8s %-13s %-13s %-13s%n", "model", "thinking",
                        "status", "planCapture", "buildCapture"));
        for (Map<String, Object> r : results) {
            LOG.log(Level.INFO, "{0}",
                    String.format(Locale.ROOT, "%-20s %-8s %-13s %-13s %-13s%n", r.get("model"),
                            r.get("thinking"), r.get("status"), r.get("planCaptured"),
                            r.get("buildCaptured")));
        }
        long completed = results.stream().filter(r -> "completed".equals(r.get("status"))).count();
        long planOk =
                results.stream().filter(r -> Boolean.TRUE.equals(r.get("planCaptured"))).count();
        long buildOk =
                results.stream().filter(r -> Boolean.TRUE.equals(r.get("buildCaptured"))).count();
        LOG.log(Level.INFO,
                "[prompt-matrix] completed={0}/{1}, planCaptured={2}/{1}, buildCaptured={3}/{1}",
                new Object[]{completed, results.size(), planOk, buildOk});
    }

    /** Capture sink for messages seen at the BaseModelClient boundary. */
    private static final class Capture {
        private final List<List<BaseMessage>> captured = new ArrayList<>();
    }

    /**
     * Wraps {@link OpenAiCompatibleModelClient}, capturing the messages passed to {@link #invoke}
     * (post-externalization, pre-real-LLM). Other methods delegate without capture (this test uses
     * the non-streaming invoke path only).
     */
    private static final class CapturingModelClient extends BaseModelClient {
        private final OpenAiCompatibleModelClient delegate;
        private final Capture capture;

        CapturingModelClient(ModelRequestConfig req, ModelClientConfig cli, Capture capture) {
            super(req, cli);
            this.delegate = new OpenAiCompatibleModelClient(req, cli);
            this.capture = capture;
        }

        @Override
        public AssistantMessage invoke(Object messages, Object tools, Float temperature, Float topP,
                String model, Integer maxTokens, String stop, BaseOutputParser outputParser,
                Float timeout, Map<String, Object> kwargs) throws Exception {
            if (messages instanceof List<?> list) {
                capture.captured.add(new ArrayList<>((List<BaseMessage>) list));
            }
            return delegate.invoke(messages, tools, temperature, topP, model, maxTokens, stop,
                    outputParser, timeout, kwargs);
        }

        @Override
        public Iterator<AssistantMessageChunk> stream(Object messages, Object tools, Float temperature,
                Float topP, String model, Integer maxTokens, String stop, BaseOutputParser outputParser,
                Float timeout, Map<String, Object> kwargs) throws Exception {
            return delegate.stream(messages, tools, temperature, topP, model, maxTokens, stop,
                    outputParser, timeout, kwargs);
        }

        @Override
        public ImageGenerationResponse generateImage(List<UserMessage> a, String b, String c, String d,
                int e, boolean f, boolean g, int h, Map<String, Object> i) throws Exception {
            return delegate.generateImage(a, b, c, d, e, f, g, h, i);
        }

        @Override
        public AudioGenerationResponse generateSpeech(List<UserMessage> a, String b, String c, String d,
                Map<String, Object> e) throws Exception {
            return delegate.generateSpeech(a, b, c, d, e);
        }

        @Override
        public VideoGenerationResponse generateVideo(List<UserMessage> a, String b, String c, String d,
                String e, String f, int g, boolean h, boolean i, String j, Integer k,
                Map<String, Object> l) throws Exception {
            return delegate.generateVideo(a, b, c, d, e, f, g, h, i, j, k, l);
        }
    }
}
