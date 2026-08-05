/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.skillhub;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.service.adapters.agentcore.ext.middleware.skillhub.SkillHubManager;
import com.openjiuwen.service.spec.spi.AgentHandler;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * End-to-end test through the Spring HTTP endpoint: real SkillHub service +
 * real DeepSeek LLM. Proves the full SkillHub middleware chain works via the
 * runtime {@link AgentHandler} boundary:
 * <ol>
 *   <li>SkillHubManager.start() fetches skills from swarmskills.openjiuwen.com</li>
 *   <li>Provider downloads zip → SHA-256 → extracts to dirs with SKILL.md</li>
 *   <li>On first query, JiuwenCoreAgentExtHandler triggers manager.register(agent)</li>
 *   <li>SkillHubInstaller calls BaseAgent.registerSkill(dir.toString())</li>
 *   <li>Agent-core loads SKILL.md into the system prompt</li>
 *   <li>LLM receives the skill-augmented prompt and references a real skill name</li>
 * </ol>
 *
 * <p><b>Strict assertion</b>: the response must contain at least one real skill
 * name parsed from the SkillHubManager's registeredList (from the downloaded
 * SKILL.md front matter). This catches the "silent no-op" failure mode where
 * sysOperationId is null and SkillHub registration is silently skipped — in
 * that case the LLM would respond with generic capabilities only.
 *
 * <p>Guarded by {@code deepseek.api.key}:
 * <pre>
 *   mvn test -Dtest=SkillHubAgentLifecycleRealLlmTest "-Ddeepseek.api.key=sk-xxx"
 * </pre>
 */
@Tag("integration")
@SpringBootTest(classes = SkillHubRuntimeDemoApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfSystemProperty(named = "deepseek.api.key", matches = "sk-.*")
class SkillHubAgentLifecycleRealLlmTest {
    private static final String USER_ID = "skillhub-e2e-user";

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private AgentHandler agentHandler;

    @Autowired
    private SkillHubManager skillHubManager;

    private final ObjectMapper mapper = new ObjectMapper();

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        // Real DeepSeek via OpenAI-compatible provider
        registry.add("openjiuwen.service.llm.provider", () -> "OpenAI");
        registry.add("openjiuwen.service.llm.api-key", () -> System.getProperty("deepseek.api.key"));
        registry.add("openjiuwen.service.llm.api-base",
            () -> System.getProperty("deepseek.api.base", "https://api.deepseek.com"));
        registry.add("openjiuwen.service.llm.model-name",
            () -> System.getProperty("deepseek.model.name", "deepseek-chat"));
        registry.add("openjiuwen.service.llm.auto-discover", () -> "false");
        registry.add("openjiuwen.service.llm.temperature", () -> "0.2");
        registry.add("openjiuwen.service.llm.max-iterations", () -> "6");

        // SkillHub against real swarmskills.openjiuwen.com.
        // auth-type and encrypted-token are overridable via system properties so
        // the bearer / system-token authentication branches can be exercised
        // against the real service. Default: anonymous (empty token).
        registry.add("openjiuwen.service.middleware.skillhub.enabled", () -> "true");
        registry.add("openjiuwen.service.middleware.skillhub.endpoint",
            () -> System.getProperty("skillhub.endpoint", "https://swarmskills.openjiuwen.com"));
        registry.add("openjiuwen.service.middleware.skillhub.auth-type",
            () -> System.getProperty("skillhub.auth-type", "bearer"));
        registry.add("openjiuwen.service.middleware.skillhub.encrypted-token",
            () -> System.getProperty("skillhub.encrypted-token", ""));
    }

    @AfterAll
    void cleanup() {
        if (agentHandler != null) {
            agentHandler.stop();
        }
    }

    @Test
    void llmResponseReferencesARealRegisteredSkillName() throws Exception {
        // Trigger the first query so JiuwenCoreAgentExtHandler.installBeforeRun()
        // calls skillHubManager.register(agent) on the request thread.
        ResponseEntity<String> response = postQuery(
            "skillhub-e2e-strict", "你有哪些可用的 skill 或技能？请列出名称。");

        assertThat(response.getStatusCode())
            .as("HTTP status: %s body: %s", response.getStatusCode(), response.getBody())
            .isEqualTo(HttpStatus.OK);

        String content = resultContent(response);
        assertThat(content)
            .as("LLM response should be non-empty")
            .isNotBlank();

        // Collect real skill names from the SkillHubManager's registered list.
        // These are the names parsed from the downloaded SKILL.md front matter.
        List<String> realSkillNames = collectRegisteredSkillNames(skillHubManager);

        // If registration silently no-op'd (e.g. sysOperationId null), the list is
        // empty and the assertion below would fail — surfacing the integration bug
        // instead of masking it with a loose containsAnyOf.
        assertThat(realSkillNames)
            .as("SkillHubManager should have at least one registered skill after the first query; "
                + "if empty, registration silently no-op'd (check sysOperationId in the agent factory)")
            .isNotEmpty();

        // Lowercase both sides for case-insensitive matching; skill names from
        // swarmskills are typically lowercase (dev-coder, pptx-craft, kami, ...).
        String lowerContent = content.toLowerCase(java.util.Locale.ROOT);
        boolean anyReferenced = realSkillNames.stream()
            .anyMatch(name -> name != null && !name.isBlank()
                && lowerContent.contains(name.toLowerCase(java.util.Locale.ROOT)));

        assertThat(anyReferenced)
            .as("LLM response should reference at least one real registered skill name "
                + "(from %s). Response: %s", realSkillNames, content)
            .isTrue();
    }

    private ResponseEntity<String> postQuery(String conversationId, String message) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.postForEntity("/v1/query", new HttpEntity<>(Map.of(
            "conversation_id", conversationId,
            "user_id", USER_ID,
            "message", message,
            "stream", false), headers), String.class);
    }

    @SuppressWarnings("unchecked")
    private String resultContent(ResponseEntity<String> response) throws Exception {
        Map<String, Object> json = mapper.readValue(response.getBody(), Map.class);
        Object result = json.get("result");
        if (result instanceof Map<?, ?> m) {
            Object content = m.get("content");
            return content == null ? "" : String.valueOf(content);
        }
        return String.valueOf(result);
    }

    /**
     * Reflect into SkillHubManager.getRegisteredList() (package-private, not
     * visible from this test's package) and parse the {@code name:} field
     * from each registered skill's SKILL.md front matter.
     *
     * <p>Note: SkillHubManager tracks per-agent processed
     * paths via a WeakHashMap; {@code getRegisteredList()} returns the union
     * of all paths processed for any agent. The previous reflection target
     * {@code registeredList} no longer exists.
     *
     * @param manager the SkillHubManager bean to reflect into
     * @return the list of registered skill names parsed from SKILL.md front matter
     * @throws Exception if reflection or file parsing fails
     */
    private static List<String> collectRegisteredSkillNames(SkillHubManager manager) throws Exception {
        java.lang.reflect.Method m = SkillHubManager.class.getDeclaredMethod("getRegisteredList");
        m.setAccessible(true);
        Object listObj = m.invoke(manager);
        if (!(listObj instanceof List<?> list)) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Path skillDir)) {
                continue;
            }
            Path md = Files.isReadable(skillDir.resolve("SKILL.md"))
                    ? skillDir.resolve("SKILL.md")
                    : skillDir.resolve("Skill.md");
            if (!Files.isReadable(md)) {
                continue;
            }
            parseFrontMatterName(Files.readString(md, StandardCharsets.UTF_8))
                    .ifPresent(names::add);
        }
        return names;
    }

    private static Optional<String> parseFrontMatterName(String content) {
        if (content == null) {
            return Optional.empty();
        }
        String trimmed = content.trim();
        if (!trimmed.startsWith("---")) {
            return Optional.empty();
        }
        String[] parts = trimmed.split("---", 3);
        if (parts.length < 2) {
            return Optional.empty();
        }
        for (String line : parts[1].split("\\r?\\n")) {
            String t = line.trim();
            if (t.startsWith("name:")) {
                return Optional.of(t.substring("name:".length()).trim());
            }
        }
        return Optional.empty();
    }
}
