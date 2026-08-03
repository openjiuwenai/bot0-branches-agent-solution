/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.openjiuwen.edp.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 启动时配置校验器。
 *
 * fail-fast 校验：缺配置 → 启动失败并报明确错误。
 *
 * V2 方案 B：场景校验从 scenarioHome 出发，
 * 不再依赖 yamlDir.resolve(basePath) 解析。
 *
 * @since 2024-01-01
 */

public class EdpConfigValidator {
    private static final Logger LOGGER = LoggerFactory.getLogger(EdpConfigValidator.class);

    /**
     * 校验模型配置完整性（backend + model 新格式）。
     *
     * @param config description
     */

    public static void validateModelConfig(DeepAgentProperties config) {
        if (config == null) {
            throw new IllegalStateException("Config missing. Set deep-agent in application.yml.");
        }
        EdpaSpringBootConfig.BackendConfig backend = config.getBackend();
        EdpaSpringBootConfig.ModelConfig model = config.getModel();

        if (backend == null) {
            throw new IllegalStateException("Backend config missing. Set deep-agent.backend in application.yml.");
        }
        if (backend.getClientProvider() == null || backend.getClientProvider().isBlank()) {
            throw new IllegalStateException("Backend client_provider missing. Set deep-agent.backend.client_provider.");
        }
        if (backend.getApiBase() == null || backend.getApiBase().isBlank()) {
            throw new IllegalStateException("Backend api_base missing. Set deep-agent.backend.api_base.");
        }
        if (backend.getTimeout() <= 0) {
            throw new IllegalStateException("Backend timeout must be > 0. Set deep-agent.backend.timeout.");
        }

        String apiKey = backend.getApiKey();
        String envApiKey = System.getenv("DEEPSEEK_API_KEY");
        boolean hasValidApiKey = (apiKey != null && !apiKey.isBlank()) || (envApiKey != null && !envApiKey.isBlank());
        if (!hasValidApiKey) {
            throw new IllegalStateException("Backend apiKey missing. Set DEEPSEEK_API_KEY environment variable.");
        }

        if (model == null) {
            throw new IllegalStateException("Model config missing. Set deep-agent.model in application.yml.");
        }
        if (model.getModel() == null || model.getModel().isBlank()) {
            throw new IllegalStateException("Model name missing. Set deep-agent.model.model.");
        }

        LOGGER.info("Model config validated: clientProvider={}, model={}, apiKeySource={}",
                backend.getClientProvider(), model.getModel(),
                (envApiKey != null && !envApiKey.isBlank()) ? "ENV_VAR" : "application.yml");
    }

    /**
     * 校验 Skill 目录存在。
     *
     * @param skillDir description
     */

    public static void validateSkillDir(Path skillDir) {
        if (skillDir != null && !Files.exists(skillDir)) {
            throw new IllegalStateException("Skill directory not found: " + skillDir);
        }
    }

    /**
     * 校验场景目录。scenario-config.yaml 已删除，验证 governance/ 目录结构。
     *
     * @param scenarioHome description
     */

    public static void validateScenarioConfig(Path scenarioHome) {
        if (scenarioHome == null) {
            LOGGER.info("No scenarioHome configured, skipping scenario validation.");
            return;
        }
        if (!Files.exists(scenarioHome)) {
            throw new IllegalStateException("scenarioHome directory not found: " + scenarioHome);
        }
        Path governanceDir = scenarioHome.resolve("governance");
        if (!Files.exists(governanceDir)) {
            throw new IllegalStateException("Scenario governance directory not found: " + governanceDir
                    + ". Expected governance/{planrule,actrule,scriptconfig}.yaml");
        }
        LOGGER.info("Scenario governance validated: {}", governanceDir);
    }

    /**
     * 校验 skill_routing 中声明的 Skill 在 skills 目录中存在。
     * 数据源已从 ScenarioConfig 迁移至 PlanRuleConfig。
     *
     * @param planrule description
     *
     * @param skillsDir description
     */

    public static void validateSkillRouting(PlanRuleConfig planrule, Path skillsDir) {
        if (planrule == null || planrule.getSkillRouting() == null) {
            return;
        }
        for (PlanRuleConfig.SkillRoute routing : planrule.getSkillRouting()) {
            Path skillDir = skillsDir.resolve(routing.getSkill());
            if (!Files.exists(skillDir)) {
                throw new IllegalStateException("Skill routing references non-existent skill: " + routing.getSkill());
            }
        }
    }

    /**
     * 校验沙箱配置完整性。
     *
     * fail-fast 校验：sandbox.enabled=true 但 service-url 为空/空白时，
     * 视为配置缺失，启动失败并报明确错误。
     * sandbox.enabled=false 时跳过校验（沙箱功能未启用，service-url 无意义）。
     *
     * @param sandbox the sandbox config
     */
    public static void validateSandboxConfig(SandboxConfig sandbox) {
        if (sandbox == null || !sandbox.isEnabled()) {
            LOGGER.info("Sandbox config disabled, skipping validation.");
            return;
        }
        String serviceUrl = sandbox.getServiceUrl();
        if (serviceUrl == null || serviceUrl.isBlank()) {
            throw new IllegalStateException(
                    "Sandbox enabled but service-url is empty. "
                    + "Set EDPA_SANDBOX_SERVICE_URL to a valid sandbox service address, "
                    + "or set EDPA_SANDBOX_ENABLED=false to disable sandbox.");
        }
        if (!serviceUrl.startsWith("http://") && !serviceUrl.startsWith("https://")) {
            throw new IllegalStateException(
                    "Sandbox service-url invalid: " + serviceUrl
                    + ". Must start with http:// or https://.");
        }
        LOGGER.info("Sandbox config validated: enabled=true, serviceUrl={}", serviceUrl);
    }
}
