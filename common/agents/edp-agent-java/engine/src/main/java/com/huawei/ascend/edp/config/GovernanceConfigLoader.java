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

package com.huawei.ascend.edp.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Governance配置加载器。
 *
 * <p>文件作用：</p>
 * <ul>
 *     <li>读取三个治理域配置文件（planrule.yaml、actrule.yaml、scriptconfig.yaml）。</li>
 *     <li>支持配置路径优先级：场景级governance > 框架级governance。</li>
 *     <li>实现继承覆盖机制（字段级别的继承覆盖）。</li>
 * </ul>
 *
 * <p>对外提供的接口：</p>
 * <ul>
 *     <li>{@link #load(Path)}：从指定governance目录加载配置。</li>
 *     <li>{@link #loadWithPriority(Path, Path)}：优先加载场景级配置，再合并框架级配置。</li>
 * </ul>
 *
 * @since 2024-01-01
 */

public class GovernanceConfigLoader {
    private static final Logger LOGGER = LoggerFactory.getLogger(GovernanceConfigLoader.class);

    /**
     * YAML 解析器。使用 snake_case 字段映射。
     */

    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

    /**
     * 从指定governance目录加载配置。
     *
     * <p>加载策略：文件系统优先，classpath 回退。</p>
     * <ul>
     *     <li>文件系统路径存在 → 从文件系统加载（开发态，CWD 下有 governance 目录）</li>
     *     <li>文件系统路径不存在 → 从 classpath 加载（集成态，引擎 JAR 内有 governance 目录）</li>
     * </ul>
     *
     * @param governanceDir governance目录路径（包含三个yaml文件）
     * @return 解析后的 GovernanceConfig；文件系统和classpath均不存在时返回默认对象
     */

    public static GovernanceConfig load(Path governanceDir) {
        // 1. 优先从文件系统加载
        if (governanceDir != null && Files.exists(governanceDir)) {
            return loadFromFilesystem(governanceDir);
        }
        // 2. 文件系统路径不存在 → 回退到 classpath
        LOGGER.info("Governance directory not found at {}, falling back to classpath", governanceDir);
        return loadFromClasspath();
    }

    /**
     * 从文件系统加载governance配置（内部方法）。
     *
     * @param governanceDir governance目录路径
     * @return 解析后的 GovernanceConfig
     */

    private static GovernanceConfig loadFromFilesystem(Path governanceDir) {
        GovernanceConfig config = new GovernanceConfig();

        // 加载planrule.yaml
        Path planrulePath = governanceDir.resolve("planrule.yaml");
        if (Files.exists(planrulePath)) {
            try {
                JsonNode root = YAML_MAPPER.readTree(Files.readString(planrulePath));
                JsonNode planruleNode = root.get("planrule");
                if (planruleNode != null) {
                    PlanRuleConfig planrule = YAML_MAPPER.treeToValue(planruleNode, PlanRuleConfig.class);
                    config.setPlanrule(planrule);
                    LOGGER.info("Loaded planrule.yaml from {}", planrulePath);
                }
            } catch (IOException e) {
                LOGGER.warn("Failed to load planrule.yaml from {}: {}", planrulePath, e.getMessage());
            }
        }

        // 加载actrule.yaml
        Path actrulePath = governanceDir.resolve("actrule.yaml");
        if (Files.exists(actrulePath)) {
            try {
                JsonNode root = YAML_MAPPER.readTree(Files.readString(actrulePath));
                JsonNode actruleNode = root.get("actrule");
                if (actruleNode != null) {
                    ActRuleConfig actrule = YAML_MAPPER.treeToValue(actruleNode, ActRuleConfig.class);
                    config.setActrule(actrule);
                    LOGGER.info("Loaded actrule.yaml from {}", actrulePath);
                }
            } catch (IOException e) {
                LOGGER.warn("Failed to load actrule.yaml from {}: {}", actrulePath, e.getMessage());
            }
        }

        // 加载scriptconfig.yaml
        Path scriptconfigPath = governanceDir.resolve("scriptconfig.yaml");
        if (Files.exists(scriptconfigPath)) {
            try {
                JsonNode root = YAML_MAPPER.readTree(Files.readString(scriptconfigPath));
                JsonNode scriptconfigNode = root.get("scriptconfig");
                if (scriptconfigNode != null) {
                    ScriptConfig scriptconfig = YAML_MAPPER.treeToValue(scriptconfigNode, ScriptConfig.class);
                    config.setScriptconfig(scriptconfig);
                    LOGGER.info("Loaded scriptconfig.yaml from {}", scriptconfigPath);
                }
            } catch (IOException e) {
                LOGGER.warn("Failed to load scriptconfig.yaml from {}: {}", scriptconfigPath, e.getMessage());
            }
        }

        return config;
    }

    /**
     * 从classpath加载governance配置（引擎 JAR 内的默认配置）。
     *
     * <p>适用于集成态：当文件系统路径不存在时，从引擎 JAR 内的 governance/ 目录加载
     * actrule.yaml、planrule.yaml、scriptconfig.yaml。</p>
     *
     * @return 解析后的 GovernanceConfig；classpath中无可用资源时字段为null
     */

    private static GovernanceConfig loadFromClasspath() {
        GovernanceConfig config = new GovernanceConfig();
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        if (cl == null) {
            cl = GovernanceConfigLoader.class.getClassLoader();
        }

        // classpath 搜索路径需覆盖多种打包形态
        String[] basePathPrefixes = {"", "BOOT-INF/classes/", "classes/"};

        config.setPlanrule(
                loadYamlFromClasspath(cl, basePathPrefixes, "governance/planrule.yaml", "planrule", PlanRuleConfig.class));
        config.setActrule(
                loadYamlFromClasspath(cl, basePathPrefixes, "governance/actrule.yaml", "actrule", ActRuleConfig.class));
        config.setScriptconfig(
                loadYamlFromClasspath(cl, basePathPrefixes, "governance/scriptconfig.yaml", "scriptconfig",
                        ScriptConfig.class));

        return config;
    }

    /**
     * 从classpath加载单个yaml配置文件。
     *
     * @param cl ClassLoader
     * @param prefixes classpath路径前缀列表（覆盖多种打包形态）
     * @param resourcePath 相对资源路径（如 "governance/actrule.yaml"）
     * @param rootNodeName yaml根节点名（如 "actrule"）
     * @param targetType 目标反序列化类型
     * @return 解析后的配置对象；classpath中无可用资源时返回null
     */

    private static <T> T loadYamlFromClasspath(ClassLoader cl, String[] prefixes,
            String resourcePath, String rootNodeName, Class<T> targetType) {
        for (String prefix : prefixes) {
            try (InputStream is = cl.getResourceAsStream(prefix + resourcePath)) {
                if (is != null) {
                    JsonNode root = YAML_MAPPER.readTree(is);
                    JsonNode node = root.get(rootNodeName);
                    if (node != null) {
                        T result = YAML_MAPPER.treeToValue(node, targetType);
                        LOGGER.info("Loaded {} from classpath: {}", rootNodeName, prefix + resourcePath);
                        return result;
                    }
                }
            } catch (IOException e) {
                LOGGER.debug("Failed to load {} from classpath {}: {}", rootNodeName, prefix + resourcePath,
                        e.getMessage());
            }
        }
        LOGGER.info("{} not found in classpath, using defaults", rootNodeName);
        return null;
    }

    /**
     * 优先加载场景级配置，再合并框架级配置（场景级优先）。
     *
     * <p>配置优先级：</p>
     * <ul>
     *     <li>场景级governance配置（scenarios/{场景名}/governance/）</li>
     *     <li>框架级governance配置（engine/src/main/resources/governance/）</li>
     * </ul>
     *
     * <p>继承覆盖规则：字段级别的继承覆盖，不是文件级别的完全覆盖。</p>
     *
     * @param scenarioDir 场景级governance目录
     * @param frameworkDir 框架级governance目录
     * @return 合并后的 GovernanceConfig
     */

    public static GovernanceConfig loadWithPriority(Path scenarioDir, Path frameworkDir) {
        // 1. 先加载框架级配置（Default）
        GovernanceConfig defaultConfig = load(frameworkDir);

        // 2. 再加载场景级配置（Scenario）
        GovernanceConfig scenarioConfig = load(scenarioDir);

        // 3. 合并配置：场景级覆盖框架级
        defaultConfig.mergeScenarioConfig(scenarioConfig);

        LOGGER.info("Governance config loaded with priority: scenario={}, framework={}", scenarioDir, frameworkDir);

        return defaultConfig;
    }

    /**
     * 从classpath加载框架级默认配置。
     *
     * <p>加载策略：文件系统优先，classpath 回退。</p>
     * <ul>
     *     <li>文件系统路径存在 → 从文件系统加载（开发态，CWD 下有 src/main/resources/governance）</li>
     *     <li>文件系统路径不存在 → 从 classpath 加载（集成态，引擎 JAR 内有 governance）</li>
     * </ul>
     *
     * @return 框架级默认 GovernanceConfig
     */

    public static GovernanceConfig loadDefaultFromClasspath() {
        Path defaultGovernancePath = Path.of("src/main/resources/governance").toAbsolutePath();
        // 1. 文件系统优先（开发态，CWD 下有 src/main/resources/governance）
        if (Files.exists(defaultGovernancePath)) {
            return loadFromFilesystem(defaultGovernancePath);
        }
        // 2. 文件系统不存在 → classpath 回退（集成态，引擎 JAR 内有 governance）
        LOGGER.info("Default governance path not found at {}, falling back to classpath", defaultGovernancePath);
        return loadFromClasspath();
    }

    /**
     * 根据场景名称加载场景级配置并合并框架级配置。
     *
     * @param scenarioName 场景名称（例如："wealth-demo"、"abc-loan-scenario"）
     * @param baseDir scenarios目录路径
     * @return 合并后的 GovernanceConfig
     */

    public static GovernanceConfig loadForScenario(String scenarioName, Path baseDir) {
        if (scenarioName == null || scenarioName.isBlank()) {
            LOGGER.info("No scenario specified, using default governance config");
            return loadDefaultFromClasspath();
        }

        // 场景级governance路径：scenarios/{场景名}/governance/
        Path scenarioGovernancePath = baseDir.resolve(scenarioName).resolve("governance");

        // 框架级governance路径：engine/src/main/resources/governance/
        Path frameworkGovernancePath = Path.of("src/main/resources/governance").toAbsolutePath();

        // 优先级合并
        return loadWithPriority(scenarioGovernancePath, frameworkGovernancePath);
    }
}
