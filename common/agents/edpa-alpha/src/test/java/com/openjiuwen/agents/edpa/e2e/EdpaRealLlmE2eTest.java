/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.edpa.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.agents.edpa.explore.ExploreBudget;
import com.openjiuwen.agents.edpa.explore.ExploreToolRegistrar;
import com.openjiuwen.agents.edpa.explore.Explorer;
import com.openjiuwen.agents.edpa.explore.LlmExplorer;
import com.openjiuwen.agents.edpa.rail.UserInputCaptureRail;
import com.openjiuwen.agents.edpa.util.LlmResponseExtractor;
import com.openjiuwen.agents.reactrails.enforcing.ToolCallingEnforcingModel;
import com.openjiuwen.agents.reactrails.replan.ReplanRail;
import com.openjiuwen.agents.reactrails.replan.ReplanTool;
import com.openjiuwen.agents.reactrails.selfheal.RootCauseRail;
import com.openjiuwen.agents.reactrails.verification.CriteriaReplanBridgeRail;
import com.openjiuwen.agents.reactrails.verification.RuleBasedCriteriaVerifier;
import com.openjiuwen.core.foundation.llm.model_clients.DefaultModelClientFactories;
import com.openjiuwen.core.foundation.llm.schema.ModelClientConfig;
import com.openjiuwen.core.foundation.llm.schema.ModelRequestConfig;
import com.openjiuwen.core.foundation.tool.Tool;
import com.openjiuwen.core.foundation.tool.ToolCard;
import com.openjiuwen.core.runner.Runner;
import com.openjiuwen.core.singleagent.agents.ReActAgent;
import com.openjiuwen.core.singleagent.schema.AgentCard;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Real-LLM e2e test for EDPA-alpha Species E (tool-driven Explore).
 *
 * <p>Tests the GEPA-converged architecture: LLM actively calls the
 * {@code explore} tool (not rail-driven pushSteering). Verifies:
 * <ul>
 *   <li>ExploreTool is visible to the LLM (via ExploreToolRegistrar dual registration)</li>
 *   <li>LLM calls explore tool → Explorer.explore() fires → findings return as tool result</li>
 *   <li>UserInputCaptureRail fills the supplier before LLM calls explore</li>
 *   <li>Full E→D→P→A loop produces non-empty result</li>
 * </ul>
 *
 * <p>Env-gated: skips when OPENJIUWEN_API_KEY / OPENJIUWEN_BASE_URL not set.
 *
 * @since 2026-07
 */
class EdpaRealLlmE2eTest {
    private static final Logger LOG = Logger.getLogger(EdpaRealLlmE2eTest.class.getName());

    /**
     * Immutable bundle of the Explorer-side LLM HTTP endpoint parameters.
     *
     * <p>Grouped so that {@link #callExplorerLlm(String, ExplorerEndpoint, AtomicInteger)} stays within the
     * CodeCheck G.MET.01 parameter-count limit (<= 5); the four transport-level fields travel together.
     *
     * @param base the API base URL
     * @param path the completions path appended to {@code base}
     * @param modelName the model name to request
     * @param key the bearer API key
     * @since 2026-07
     */
    private record ExplorerEndpoint(String base, String path, String modelName, String key) {
    }

    /**
     * Drives the EDPA-alpha Species E tool-driven Explore loop against a real LLM and asserts the data channel.
     *
     * <p>Registers the {@code market_data} tool and the LLM-backed Explorer, wires the
     * UserInputCaptureRail / CriteriaReplanBridgeRail / ReplanRail / RootCauseRail, then invokes the agent
     * and asserts a non-null, non-empty result plus captured user input. Soft-observes (via logs) whether
     * the LLM actually called the {@code explore} tool.
     *
     * <p>Skipped via JUnit assumptions when {@code OPENJIUWEN_API_KEY} / {@code OPENJIUWEN_BASE_URL} are unset.
     */
    @Test
    void edpaToolDrivenExplore_llmCallsExploreTool() {
        String key = System.getenv("OPENJIUWEN_API_KEY");
        String base = System.getenv("OPENJIUWEN_BASE_URL");
        org.junit.jupiter.api.Assumptions.assumeTrue(key != null && !key.isBlank() && base != null && !base.isBlank(),
                "OPENJIUWEN_API_KEY / OPENJIUWEN_BASE_URL not set, skip EDPA e2e");

        String modelName = System.getenv().getOrDefault("OPENJIUWEN_MODEL", "deepseek-v4-flash");
        DefaultModelClientFactories.ensureRegistered();

        AtomicInteger exploreCallCount = new AtomicInteger(0);
        AtomicReference<String> userInputRef = new AtomicReference<>();
        ReActAgent agent = buildWiredAgent(key, base, modelName, exploreCallCount, userInputRef);

        Object result = agent.invoke(buildExplorePrompt(), null);

        logResultPreview(result, userInputRef.get(), exploreCallCount.get());

        assertThat(result).as("EDPA tool-driven loop must produce a non-null result").isNotNull();
        assertThat(String.valueOf(result)).as("EDPA output must be non-empty (data channel proof)").isNotEmpty();
        assertThat(userInputRef.get()).as("UserInputCaptureRail must have captured the user query").isNotNull()
                .isNotEmpty();
        assertThat(exploreCallCount.get())
                .as("LLM must dispatch the explore tool at least once (Species E tool-driven Explore proof)")
                .isGreaterThan(0);
    }

    /**
     * Builds and wires the EDPA-alpha Species E agent (model + tools + rails + Explorer).
     *
     * @param key the bearer API key
     * @param base the API base URL
     * @param modelName the model name to request
     * @param exploreCallCount shared counter incremented per Explorer LLM call
     * @param userInputRef reference filled by the UserInputCaptureRail
     * @return the fully wired ReAct agent
     */
    private static ReActAgent buildWiredAgent(String key, String base, String modelName,
            AtomicInteger exploreCallCount, AtomicReference<String> userInputRef) {
        var cliCfg = ModelClientConfig.builder().clientId("edpa-e2e-" + System.nanoTime()).clientProvider("OpenAI")
                .apiKey(key).apiBase(base).verifySsl(false).build();
        var reqCfg = ModelRequestConfig.builder().modelName(modelName).temperature(0.3).topP(0.9).maxTokens(500)
                .build();

        ToolCallingEnforcingModel model = new ToolCallingEnforcingModel(cliCfg, reqCfg);

        ReActAgent agent = new ReActAgent(AgentCard.builder().name("edpa-e2e").build());
        agent.setLlm(model);

        registerMarketDataTool(agent);

        String explorerPath = System.getenv().getOrDefault("OPENJIUWEN_COMPLETIONS_PATH", "/chat/completions");
        ExplorerEndpoint endpoint = new ExplorerEndpoint(base, explorerPath, modelName, key);
        Explorer explorer = new LlmExplorer(prompt -> callExplorerLlm(prompt, endpoint, exploreCallCount),
                ExploreBudget.DEFAULT);

        agent.registerRail(new UserInputCaptureRail(userInputRef));
        ExploreToolRegistrar.registerOnto(agent, explorer, ExploreBudget.DEFAULT, () -> userInputRef.get());

        ReplanRail sharedCounter = new ReplanRail(2);
        agent.registerRail(new CriteriaReplanBridgeRail(new RuleBasedCriteriaVerifier(),
                List.of("分析", "建议"), sharedCounter));

        agent.registerRail(new ReplanRail(2));
        ReplanTool.registerOnto(agent);

        agent.registerRail(new RootCauseRail());
        return agent;
    }

    /**
     * Builds the user prompt that drives the EDPA tool-driven Explore loop.
     *
     * @return the Chinese-language prompt instructing the LLM to explore, fetch data, then advise
     */
    private static String buildExplorePrompt() {
        return "分析当前的经济形势。先调用 explore 工具探索可用的分析角度，"
                + "然后调用 market_data 工具获取数据，最后给出投资建议。";
    }

    /**
     * Builds the {@code market_data} stub tool and registers it on the agent's ability manager and global runner.
     *
     * @param agent the ReAct agent whose ability manager will expose the tool card
     */
    private static void registerMarketDataTool(ReActAgent agent) {
        ToolCard card = ToolCard.builder().id("market_data").name("market_data")
                .description("获取市场数据。参数：indicator（指标名，如 GDP/CPI/利率）。")
                .inputParams(Map.of("type", "object", "properties",
                        Map.of("indicator", Map.of("type", "string", "description", "经济指标名")), "required",
                        List.of("indicator")))
                .build();

        Tool tool = new Tool(card) {
            @Override
            public ToolCard getCard() {
                return card;
            }

            @Override
            public Object invoke(Map<String, Object> args, Map<String, Object> kwargs) {
                String indicator = args != null ? String.valueOf(args.getOrDefault("indicator", "GDP")) : "GDP";
                return Map.of("indicator", indicator, "value", "稳中有降", "trend", "谨慎乐观");
            }

            @Override
            public Iterator<Object> stream(Map<String, Object> args, Map<String, Object> kwargs) {
                return List.<Object>of(invoke(args, kwargs)).iterator();
            }
        };

        agent.getAbilityManager().add(card);
        Runner.resourceMgr().addTool(tool, null);
    }

    /**
     * Calls the Explorer-side LLM over HTTP chat-completions and returns the extracted content.
     *
     * <p>Increments the shared explore-call counter, logs each invocation, and on non-200 returns an empty
     * string. Checked exceptions from the HTTP layer are wrapped into a runtime exception.
     *
     * @param prompt the prompt to send to the Explorer LLM
     * @param endpoint the bundled Explorer HTTP endpoint (base URL, path, model name, API key)
     * @param exploreCallCount shared counter incremented per Explorer LLM call
     * @return the extracted LLM content, or empty string on non-200 status
     */
    private static String callExplorerLlm(String prompt, ExplorerEndpoint endpoint,
            AtomicInteger exploreCallCount) {
        exploreCallCount.incrementAndGet();
        LOG.log(Level.INFO, "[edpa-e2e] Explorer LLM called (count={0})", exploreCallCount.get());
        try {
            String body = buildExplorerBody(prompt, endpoint.modelName());
            HttpRequest httpReq = HttpRequest.newBuilder().uri(URI.create(endpoint.base() + endpoint.path()))
                    .header("Authorization", "Bearer " + endpoint.key()).header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(60)).POST(HttpRequest.BodyPublishers.ofString(body)).build();
            HttpResponse<String> httpResp = HttpClient.newHttpClient().send(httpReq,
                    HttpResponse.BodyHandlers.ofString());
            if (httpResp.statusCode() != 200) {
                LOG.log(Level.INFO, "[edpa-e2e] Explorer HTTP {0}", httpResp.statusCode());
                return "";
            }
            String content = LlmResponseExtractor.extractContent(httpResp.body());
            LOG.log(Level.INFO, "[edpa-e2e] Explorer response len={0}", content.length());
            return content;
        } catch (RuntimeException | Error e) {
            // CodeCheck G.ERR.02: e2e 容错 — record and rethrow so the soft-observe loop can log the failure
            LOG.log(Level.INFO, "[edpa-e2e] Explorer exception: {0}", e.getMessage());
            throw e;
        } catch (IOException | InterruptedException e) {
            // CodeCheck: test 容错 catch-all — wrap checked HTTP exceptions for the Explorer lambda
            throw new IllegalStateException("Explorer LLM call failed: " + e.getMessage(), e);
        }
    }

    /**
     * Builds the JSON request body for the Explorer LLM call, escaping the prompt for safe embedding.
     *
     * @param prompt the raw prompt text
     * @param modelName the model name to request
     * @return the JSON request body string
     */
    private static String buildExplorerBody(String prompt, String modelName) {
        String escapedPrompt = prompt.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
                .replace("\r", "");
        return "{\"model\":\"" + modelName + "\",\"messages\":[{\"role\":\"user\",\"content\":\"" + escapedPrompt
                + "\"}],\"temperature\":0.3,\"max_tokens\":500,\"top_p\":0.9}";
    }

    /**
     * Logs the result type, length, preview, captured user input, and explore-call count for soft-observation.
     *
     * @param result the agent invocation result
     * @param userInput the captured user input (may be null)
     * @param exploreCallCount the number of Explorer LLM calls observed
     */
    private static void logResultPreview(Object result, String userInput, int exploreCallCount) {
        String output = String.valueOf(result);
        LOG.log(Level.INFO, "[edpa-e2e] result type: {0}", result.getClass().getName());
        LOG.log(Level.INFO, "[edpa-e2e] output length: {0}", output.length());
        LOG.log(Level.INFO, "[edpa-e2e] output preview: {0}", output.substring(0, Math.min(300, output.length())));
        LOG.log(Level.INFO, "[edpa-e2e] userInputRef: {0}", userInput);
        LOG.log(Level.INFO, "[edpa-e2e] exploreCallCount: {0}", exploreCallCount);
        String verdict = exploreCallCount > 0
                ? "[edpa-e2e] ✅ LLM called explore tool — Species E tool-driven Explore verified"
                : "[edpa-e2e] ⚠️ LLM did not call explore tool (soft-observe: LLM behavior non-deterministic)";
        LOG.log(Level.INFO, "{0}", verdict);
    }
}
