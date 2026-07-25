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
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 系统话术配置管理器。
 *
 * 加载场景话术配置并管理 ask_user、取消确认、异常说明等模板。
 *
 * <h2>话术键映射机制（自动推断，无硬编码）</h2>
 * <p>话术键映射从配置中自动推断，无需显式声明：</p>
 * <pre>
 * scriptconfig:
 *   general_scripts:
 *     todo_start: "开始执行：{title}"   # 自动映射: todo_start → todo_start
 *   fund_planning_success: "理财购买已成功完成..."  # 自动映射: fund_planning_success → fund_planning_success
 * </pre>
 *
 * <p>调用方使用 {@link #getScriptOrDefault(String, String)} 按 ScriptConstants 常量名获取话术，
 * 内部通过 {@link #resolveScriptKey(String)} 映射到配置中的实际键名。
 * 如果配置中存在该键名（忽略大小写），则自动建立映射。</p>
 *
 * <h2>场景特有键</h2>
 * <p>场景可以定义自己的话术键（如 hz-zhidaitong 的 loan_success），同样自动映射。</p>
 *
 * @since 2024-01-01
 *
 */

public class SysScriptsConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger(SysScriptsConfig.class);

    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory());

    /**
     * 话术模板字典（扁平化后的键值对）。
     *
     * @return the result
     */
    private final Map<String, String> templates = new LinkedHashMap<>();

    /**
     * 话术键映射表：ScriptConstants 常量名（不含前缀） → 配置中的实际键名。
     * <b>从配置中自动推断，无需显式声明。</b>
     *
     * <p>推断规则：
     * <ul>
     *   <li>常量名（如 {@code "fund_planning_success"}）存在 → 自动映射到自身</li>
     *   <li>常量名存在但大小写不同 → 自动映射（忽略大小写匹配）</li>
     *   <li>常量名不存在 → 运行时抛出 IllegalStateException</li>
     * </ul>
     *
     * @return the result
     */

    private final Map<String, String> scriptKeysMap = new LinkedHashMap<>();

    /**
     * query_patterns 配置结构（解析后缓存）。
     */
    private List<QueryPattern> queryPatterns = null;

    /**
     * 每次 load() 时独立解析的 query_patterns 源列表（用于框架级+场景级合并）。
     *
     * @return the result
     */
    private final List<List<QueryPattern>> queryPatternsSources = new ArrayList<>();

    /**
     * interrupt 追问内容来源开关：script（默认，必须脚本）或 llm（允许 LLM 生成）。UC-C02
     */
    private String interruptSource = "script";

    /**
     * 标记是否已加载场景配置。
     */
    private boolean loaded = false;

    public SysScriptsConfig() {
        // 无默认值，映射从配置中自动推断
    }

    /**
     * 加载话术模板配置。
     *
     * <p>加载顺序：
     * <ol>
     *   <li>如果 configPath 是文件系统路径且文件存在 → 从文件系统加载</li>
     *   <li>否则尝试从 classpath 加载（支持 JAR 内置配置）</li>
     * </ol>
     *
     * @param configPath 配置文件路径或 classpath 资源路径
     * @throws IllegalStateException 配置文件不存在且 classpath 中也不存在
     *
     */

    public void load(String configPath) {
        if (configPath == null || configPath.isBlank()) {
            throw new IllegalArgumentException("场景配置路径不能为空");
        }

        Path filePath = Path.of(configPath).toAbsolutePath().normalize();
        boolean loadedFromFile = false;

        // 1. 尝试从文件系统加载
        if (Files.exists(filePath)) {
            try {
                loadFromFile(filePath);
                this.loaded = true;
                loadedFromFile = true;
                LOGGER.info("SysScriptsConfig loaded from file: {}", filePath);
            } catch (IOException | RuntimeException e) {
                throw new IllegalStateException("加载场景话术配置失败: " + filePath, e);
            }
        }

        // 2. 文件系统路径不存在 → 尝试从 classpath 加载
        if (!loadedFromFile) {
            // 从完整路径中提取相对路径
            // （如 src/main/resources/governance/scriptconfig.yaml -> governance/scriptconfig.yaml）
            String relativePath = extractClasspathResource(configPath);

            // 尝试多个可能的 classpath 资源路径
            String[] resourcePaths = {"BOOT-INF/classes/" + relativePath, relativePath, "classes/" + relativePath};

            ClassLoader cl = Thread.currentThread().getContextClassLoader();
            if (cl == null) {
                cl = getClass().getClassLoader();
            }

            for (String resourcePath : resourcePaths) {
                try (InputStream is = cl.getResourceAsStream(resourcePath)) {
                    if (is != null) {
                        Map<String, Object> parsed = YAML_MAPPER.readValue(is, Map.class);
                        collectQueryPatternsSource(parsed);
                        parseInterruptSource(parsed);
                        flatten("", parsed);
                        aliasCommonKeys();
                        aliasGovernancePrefixes();
                        inferScriptKeys(templates, scriptKeysMap);
                        this.loaded = true;
                        LOGGER.info("SysScriptsConfig loaded from classpath: {}", resourcePath);
                        loadedFromFile = true;
                        break;
                    }
                } catch (IOException e) {
                    LOGGER.debug("Failed to load from classpath {}: {}", resourcePath, e.getMessage());
                }
            }
        }

        if (!loadedFromFile) {
            throw new IllegalStateException("话术配置不存在: " + configPath
                    + "（文件系统路径不存在，classpath 中也未找到）");
        }
    }

    /**
     * 从完整路径中提取 classpath 相对路径。
     *
     * @param configPath the configPath value
     * @return the result
     */

    private String extractClasspathResource(String configPath) {
        // 统一路径分隔符（Windows 使用 \，Linux 使用 /）
        String normalizedPath = configPath.replace('\\', '/');

        // 尝试查找 src/main/resources 或 resources 后缀
        String[] suffixes = {"src/main/resources/", "src/main/", "resources/"};
        for (String suffix : suffixes) {
            int idx = normalizedPath.lastIndexOf(suffix);
            if (idx >= 0) {
                // 使用 normalizedPath 的索引，从 normalizedPath 提取（统一使用 / 分隔符）
                return normalizedPath.substring(idx + suffix.length());
            }
        }
        return normalizedPath;
    }

    private void loadFromFile(Path path) throws IOException {
        Map<String, Object> parsed = YAML_MAPPER.readValue(Files.readString(path), Map.class);
        collectQueryPatternsSource(parsed);
        parseInterruptSource(parsed);
        flatten("", parsed);
        aliasCommonKeys();
        aliasGovernancePrefixes();

        // 自动推断话术键映射
        inferScriptKeys(templates, scriptKeysMap);
        loaded = true;
        LOGGER.info("SysScriptsConfig loaded from {}, templates={}, scriptKeys={}, interruptSource={}", path,
                templates.size(), scriptKeysMap.size(), interruptSource);
    }

    /**
     * 从配置中自动推断话术键映射（静态方法，供外部调用或 load 时调用）。
     *
     * <p>扫描所有模板键，按以下规则建立映射：
     * <ul>
     *   <li>general_scripts.* 下的键 → 去掉前缀后自动映射</li>
     *   <li>顶层业务话术键（如 fund_planning_success） → 自动映射到自身</li>
     * </ul>
     *
     * @param templates 扁平化的模板字典
     * @param outScriptKeys 输出映射的目标 map
     *
     * @param Map<String description
     *
     */

    public static void inferScriptKeys(Map<String, String> templates, Map<String, String> outScriptKeys) {
        // 1. 处理 general_scripts. 前缀的键
        for (Map.Entry<String, String> e : templates.entrySet()) {
            String key = e.getKey();
            if (key.startsWith("general_scripts.")) {
                String shortKey = key.substring("general_scripts.".length());

                // 映射: shortKey → key（保留 general_scripts. 前缀供后续查找）
                outScriptKeys.put(shortKey, key);
                LOGGER.debug("Inferred script key: {} -> {}", shortKey, key);
            }
        }

        // 2. 处理顶层键（业务话术、ask_user_confirm 等）
        for (Map.Entry<String, String> e : templates.entrySet()) {
            String key = e.getKey();

            // 跳过嵌套路径键（已在上一步处理）
            if (key.contains(".")) {
                continue;
            }

            // 顶层键自动映射到自身
            outScriptKeys.put(key, key);
            LOGGER.debug("Inferred top-level key: {} -> {}", key, key);
        }
    }

    // ═══════════════════════════════════════════════════
    // 话术键映射 API
    // ═══════════════════════════════════════════════════

    /**
     * 按 ScriptConstants 常量名获取实际配置键名。
     *
     * <p>常量名格式（与 ScriptConstants 字段名一致）：
     * <ul>
     *   <li>{@code SCRIPT_FUND_PLANNING_SUCCESS} → 提取 {@code fund_planning_success}</li>
     *   <li>{@code STATUS_CONFIRM} → 提取 {@code confirm}</li>
     *   <li>{@code PARAM_RESPONSE_TEMPLATE_STATUS} → 提取 {@code response_template_status}</li>
     * </ul>
     *
     * <p>映射流程：
     * <ol>
     *   <li>提取常量名（去掉前缀，转小写）</li>
     *   <li>查 scriptKeysMap（自动推断的映射）</li>
     *   <li>映射存在 → 返回映射后的键名</li>
     *   <li>映射不存在 → 抛出 IllegalStateException</li>
     * </ol>
     *
     * @param constantName ScriptConstants 常量全名（如 {@code "SCRIPT_FUND_PLANNING_SUCCESS"}）
     * @return 配置中的实际键名
     * @throws IllegalStateException 映射不存在（常量在配置中未定义）
     *
     */

    public String resolveScriptKey(String constantName) {
        if (constantName == null || constantName.isBlank()) {
            return "";
        }

        // 去掉前缀：SCRIPT_/STATUS_/PARAM_/RESULT_/KEY_
        String normalized = constantName.replaceFirst("^SCRIPT_", "").replaceFirst("^STATUS_", "")
                .replaceFirst("^PARAM_", "").replaceFirst("^RESULT_", "").replaceFirst("^KEY_", "");

        // 转小写
        String constantKey = normalized.toLowerCase(Locale.ROOT);

        // 查映射表（自动推断）
        String mapped = scriptKeysMap.get(constantKey);
        if (mapped != null) {
            return mapped;
        }

        // 尝试忽略大小写匹配
        for (Map.Entry<String, String> entry : scriptKeysMap.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(constantKey)) {
                return entry.getValue();
            }
        }

        // 无映射 → 常量在配置中未定义
        throw new IllegalStateException("话术键不存在: " + constantKey + "。请在 scriptconfig.yaml 中定义该话术。");
    }

    /**
     * 按 ScriptConstants 常量名获取话术模板。
     *
     * @param constantName ScriptConstants 常量名
     * @return 话术模板内容，不存在返回 Optional.empty()
     *
     */

    public Optional<String> getScript(String constantName) {
        try {
            String resolvedKey = resolveScriptKey(constantName);
            return Optional.ofNullable(templates.get(resolvedKey));
        } catch (IllegalStateException e) {
            return Optional.empty();
        }
    }

    /**
     * 按 ScriptConstants 常量名获取话术模板，缺失返回默认值。
     *
     * @param constantName ScriptConstants 常量名
     * @param defaultValue 缺失时的默认值
     * @return 话术模板内容或 defaultValue
     *
     */

    public String getScriptOrDefault(String constantName, String defaultValue) {
        return getScript(constantName).orElse(defaultValue);
    }

    /**
     * 按 ScriptConstants 常量名检查话术是否存在。
     *
     * @param constantName ScriptConstants 常量名
     * @return true 表示配置中存在该话术
     *
     */

    public boolean hasScript(String constantName) {
        try {
            resolveScriptKey(constantName);
            return true;
        } catch (IllegalStateException e) {
            return false;
        }
    }

    /**
     * 动态收集业务话术键（来自各 SKILL.yaml 的 scripts 字段，由 SkillScriptsCollector 合并）。
     * 业务键 = templates 中不含 "." 的键（如 product_select_confirm），排除通用前缀键（如 general_scripts.xxx）。
     *
     * @return the result
     */

    public List<String> listBusinessScriptKeys() {
        List<String> keys = new ArrayList<>();
        for (String key : templates.keySet()) {
            if (!key.contains(".") && !key.startsWith("think_chunk") && !key.startsWith("query_patterns")) {
                keys.add(key);
            }
        }
        return keys;
    }

    /**
     * 获取当前话术键映射表（调试用）。
     *
     * @return 不可变的映射表副本
     *
     */

    public Map<String, String> getScriptKeysMap() {
        return Map.copyOf(scriptKeysMap);
    }

    /**
     * 设置话术键映射（用于测试）。
     *
     * @param keysMap 常量名 → 实际键名 的映射
     *
     */

    public void setScriptKeysMap(Map<String, String> keysMap) {
        if (keysMap != null) {
            this.scriptKeysMap.putAll(keysMap);
        }
    }

    /**
     * 从当前模板自动推断话术键映射（用于测试或动态配置场景）。
     *
     */

    public void inferScriptKeysFromTemplates() {
        inferScriptKeys(templates, scriptKeysMap);
    }

    // ═══════════════════════════════════════════════════
    // 模板基础 API
    // ═══════════════════════════════════════════════════

    /**
     * 获取话术模板。
     *
     * @param key 模板 key（可以是完整路径或短键名）
     * @return 模板内容的 Optional，不存在时返回 Optional.empty()
     *
     */

    public Optional<String> getTemplate(String key) {
        // 先尝试直接匹配
        if (templates.containsKey(key)) {
            return Optional.ofNullable(templates.get(key));
        }

        // 尝试 general_scripts. 前缀
        if (!key.startsWith("general_scripts.")) {
            String withPrefix = "general_scripts." + key;
            if (templates.containsKey(withPrefix)) {
                return Optional.ofNullable(templates.get(withPrefix));
            }
        }
        return Optional.empty();
    }

    /**
     * 是否存在该话术 key（话术消费面合规判定 / 兜底用）。
     *
     * @param key 模板 key
     * @return true 表示配置内存在该 key
     *
     */

    public boolean has(String key) {
        return templates.containsKey(key) || (key != null && templates.containsKey("general_scripts." + key));
    }

    /**
     * 取模板，缺失返回默认值。
     *
     * @param key 模板 key
     * @param def 缺失时的默认值
     * @return 模板内容或 def
     *
     */

    public String getOrDefault(String key, String def) {
        String value = getTemplate(key).orElse(null);
        return value != null ? value : def;
    }

    /**
     * 合并场景级话术（场景级覆盖系统级同名 key）。
     *
     * @param skillScripts Skill 话术字典
     *
     */

    public void mergeSkillScripts(Map<String, String> skillScripts) {
        if (skillScripts != null) {
            templates.putAll(skillScripts);
        }
    }

    /**
     * 做安全变量替换。
     *
     * @param template 模板内容
     * @param vars 变量字典
     * @return 替换后的内容
     *
     */

    public String render(String template, Map<String, String> vars) {
        if (template == null) {
            return "";
        }
        String result = template;
        if (vars != null) {
            for (Map.Entry<String, String> entry : vars.entrySet()) {
                result = result.replace("{{" + entry.getKey() + "}}", entry.getValue());
                result = result.replace("{" + entry.getKey() + "}", entry.getValue());
            }
        }
        return result;
    }

    /**
     * Gets the templates.
     *
     * @return the result
     */
    public Map<String, String> getTemplates() {
        return Map.copyOf(templates);
    }

    // ═══════════════════════════════════════════════════
    // query_patterns API
    // ═══════════════════════════════════════════════════

    /**
     * 获取 interrupt 追问内容来源开关（UC-C02）。
     *
     * @return "script"（默认，追问内容必须来自脚本）或 "llm"（允许 LLM 生成）
     *
     */

    public String getInterruptSource() {
        return interruptSource;
    }

    /**
     * 获取 query_patterns 配置（解析后缓存）。
     * 支持场景级与框架级合并：同 keywords 组时场景级覆盖框架级，不同 keywords 组各自生效。
     * 对齐 FEAT_EDPA 话术特性用例文档 UC-B02 A5 验收5/6。
     *
     * @return the result
     */

    public List<QueryPattern> getQueryPatterns() {
        if (queryPatterns != null) {
            return queryPatterns;
        }
        queryPatterns = new ArrayList<>();

        if (queryPatternsSources.isEmpty()) {
            LOGGER.info("SysScriptsConfig no query_patterns config found");
            return queryPatterns;
        }

        // 按 keywords 组为单位合并：同 keywords 组时后加载的覆盖先加载的
        Map<String, QueryPattern> mergedByKeywords = new LinkedHashMap<>();
        for (List<QueryPattern> source : queryPatternsSources) {
            for (QueryPattern qp : source) {
                String groupKey = String.join(",", qp.keywords);
                mergedByKeywords.put(groupKey, qp);
            }
        }

        queryPatterns = new ArrayList<>(mergedByKeywords.values());
        LOGGER.info("SysScriptsConfig merged query_patterns: {} entries (from {} sources)", queryPatterns.size(),
                queryPatternsSources.size());
        return queryPatterns;
    }

    /**
     * 从指定前缀解析 query_patterns 配置。
     *
     * @param prefix the prefix value
     * @return the result
     */

    @SuppressWarnings("unchecked")
    private List<QueryPattern> parseQueryPatternsFromPrefix(String prefix) {
        List<QueryPattern> result = new ArrayList<>();
        try {
            Map<String, String> qpEntries = collectPrefixedEntries(prefix);

            StringBuilder yamlBuilder = new StringBuilder();
            yamlBuilder.append("query_patterns:\n");

            Map<String, List<Map.Entry<String, String>>> groups = new LinkedHashMap<>();
            for (Map.Entry<String, String> e : qpEntries.entrySet()) {
                String idxStr = e.getKey().split("\\.")[0];
                groups.computeIfAbsent(idxStr, k -> new ArrayList<>()).add(e);
            }

            for (List<Map.Entry<String, String>> group : groups.values()) {
                List<String> keywords = new ArrayList<>();
                List<String> scripts = new ArrayList<>();
                collectGroupEntries(group, keywords, scripts);
                appendGroupYaml(yamlBuilder, keywords, scripts);
            }

            String yamlStr = yamlBuilder.toString();
            Map<String, Object> parsed = YAML_MAPPER.readValue(yamlStr, Map.class);
            Object qpObj = parsed.get("query_patterns");
            if (qpObj instanceof List<?> qpList) {
                for (Object item : qpList) {
                    collectQueryPatternFromItem(item, result);
                }
            }
        } catch (JsonProcessingException e) {
            LOGGER.warn("Failed to parse query_patterns from prefix {}: {}", prefix, e.getMessage());
        }
        return result;
    }

    private Map<String, String> collectPrefixedEntries(String prefix) {
        Map<String, String> qpEntries = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : templates.entrySet()) {
            if (e.getKey().startsWith(prefix)) {
                String shortKey = e.getKey().substring(prefix.length());
                qpEntries.put(shortKey, e.getValue());
            }
        }
        return qpEntries;
    }

    private void collectGroupEntries(List<Map.Entry<String, String>> group, List<String> keywords,
            List<String> scripts) {
        for (Map.Entry<String, String> e : group) {
            String[] parts = e.getKey().split("\\.");
            if (parts.length < 3) {
                continue;
            }
            String field = parts[1];
            if ("keywords".equals(field)) {
                keywords.add(e.getValue());
            } else if ("scripts".equals(field)) {
                scripts.add(e.getValue());
            } else {
                LOGGER.warn("unexpected field type: {}", field);
            }
        }
    }

    private void appendGroupYaml(StringBuilder yamlBuilder, List<String> keywords, List<String> scripts) {
        if (keywords.isEmpty() || scripts.isEmpty()) {
            return;
        }
        yamlBuilder.append("  - keywords: [");
        yamlBuilder.append(String.join(", ", keywords));
        yamlBuilder.append("]\n    scripts:\n");
        for (String scr : scripts) {
            yamlBuilder.append("      - \"").append(scr).append("\"\n");
        }
    }

    /**
     * 根据用户 query 匹配关键词，选择对应的话术。
     *
     * @param userQuery the userQuery value
     * @return the result
     */

    public List<String> matchQueryPatterns(String userQuery) {
        List<QueryPattern> patterns = getQueryPatterns();
        if (patterns.isEmpty() || userQuery == null || userQuery.isBlank()) {
            return List.of();
        }
        for (QueryPattern qp : patterns) {
            for (String keyword : qp.keywords) {
                if (userQuery.contains(keyword)) {
                    return qp.scripts;
                }
            }
        }
        return List.of();
    }

    /**
     * query_patterns 单条配置。
     */
    public static class QueryPattern {
        /**
         * Keywords for matching.
         */
        public final List<String> keywords;

        /**
         * Scripts to emit on match.
         */
        public final List<String> scripts;

        public QueryPattern(List<String> keywords, List<String> scripts) {
            this.keywords = keywords;
            this.scripts = scripts;
        }
    }

    // ═══════════════════════════════════════════════════
    // 私有辅助方法
    // ═══════════════════════════════════════════════════

    private void flatten(String prefix, Map<String, Object> values) {
        if (values == null) {
            return;
        }
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            String key = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
            Object value = entry.getValue();
            if (value instanceof Map<?, ?> map) {
                Map<String, Object> child = new LinkedHashMap<>();
                map.forEach((k, v) -> child.put(String.valueOf(k), v));
                flatten(key, child);
            } else if (value instanceof List<?> list) {
                templates.put(key, joinList(list));
            } else if (value != null) {
                templates.put(key, String.valueOf(value));
            } else {
                LOGGER.warn("unexpected value type for key: {}", key);
            }
        }
    }

    /**
     * 从原始 YAML 解析结果中收集 query_patterns（在 flatten 覆盖前调用）。
     * 对齐 UC-B02 A5：场景级同 keywords 组覆盖框架级，不同 keywords 组各自生效。
     *
     * @param parsed the parsed value
     */

    @SuppressWarnings("unchecked")
    private void collectQueryPatternsSource(Map<String, Object> parsed) {
        if (parsed == null) {
            return;
        }
        Object scriptsObj = parsed.get("scriptconfig");
        if (!(scriptsObj instanceof Map<?, ?>)) {
            return;
        }
        Object thinkChunkObj = ((Map<String, Object>) scriptsObj).get("think_chunk_scripts");
        if (!(thinkChunkObj instanceof Map<?, ?>)) {
            return;
        }
        Object fixedObj = ((Map<String, Object>) thinkChunkObj).get("think_chunk_fixed_scripts");
        if (!(fixedObj instanceof Map<?, ?>)) {
            return;
        }
        Object qpObj = ((Map<String, Object>) fixedObj).get("query_patterns");
        if (!(qpObj instanceof List<?>)) {
            return;
        }

        List<QueryPattern> source = new ArrayList<>();
        for (Object item : (List<?>) qpObj) {
            collectQueryPatternFromItem(item, source);
        }
        if (!source.isEmpty()) {
            queryPatternsSources.add(source);
            LOGGER.info("SysScriptsConfig collected query_patterns source: {} entries", source.size());
        }
    }

    @SuppressWarnings("unchecked")
    private void collectQueryPatternFromItem(Object item, List<QueryPattern> source) {
        if (!(item instanceof Map<?, ?> itemMap)) {
            return;
        }
        Object kwObj = itemMap.get("keywords");
        Object scriptsListObj = itemMap.get("scripts");
        if (!(kwObj instanceof List<?> keywords) || !(scriptsListObj instanceof List<?> scripts)) {
            return;
        }
        List<String> kws = new ArrayList<>();
        for (Object kw : keywords) {
            kws.add(String.valueOf(kw));
        }
        List<String> scrs = new ArrayList<>();
        for (Object scr : scripts) {
            scrs.add(String.valueOf(scr));
        }
        if (!kws.isEmpty() && !scrs.isEmpty()) {
            source.add(new QueryPattern(kws, scrs));
        }
    }

    /**
     * 解析 interrupt_source 开关（UC-C02）。
     * 配置位置：scriptconfig.interrupt_source，可选值 "script"（默认）/ "llm"。
     * 后加载的配置覆盖先加载的（场景级覆盖框架级）。
     *
     * @param parsed the parsed value
     */

    @SuppressWarnings("unchecked")
    private void parseInterruptSource(Map<String, Object> parsed) {
        if (parsed == null) {
            return;
        }
        Object scriptsObj = parsed.get("scriptconfig");
        if (!(scriptsObj instanceof Map<?, ?>)) {
            return;
        }
        Object isObj = ((Map<String, Object>) scriptsObj).get("interrupt_source");
        if (isObj instanceof String s && !s.isBlank()) {
            String val = s.trim().toLowerCase(Locale.ROOT);
            if ("script".equals(val) || "llm".equals(val)) {
                interruptSource = val;
                LOGGER.info("SysScriptsConfig interrupt_source={}", val);
            }
        }
    }

    private String joinList(List<?> values) {
        return values.stream().map(String::valueOf).reduce((left, right) -> left + "\n" + right).orElse("");
    }

    private void aliasCommonKeys() {
        if (templates.containsKey("thinking.default")) {
            templates.put("thinking", templates.get("thinking.default"));
        }
        if (templates.containsKey("ask_user_confirm.default_confirm")) {
            templates.put("ask_user_confirm", templates.get("ask_user_confirm.default_confirm"));
        }
    }

    /**
     * governance/scriptconfig.yaml 嵌套结构适配。
     *
     */

    private void aliasGovernancePrefixes() {
        Map<String, String> aliases = new LinkedHashMap<>();
        String prefix = "scriptconfig.general_scripts.";
        for (Map.Entry<String, String> e : templates.entrySet()) {
            if (e.getKey().startsWith(prefix)) {
                String shortKey = e.getKey().substring(prefix.length());
                aliases.put(shortKey, e.getValue());
            }
        }
        String qiPrefix = "scriptconfig.query_intent_tool_text.";
        for (Map.Entry<String, String> e : templates.entrySet()) {
            if (e.getKey().startsWith(qiPrefix)) {
                String shortKey = e.getKey().substring(qiPrefix.length());
                aliases.put("query_intent_tool_text." + shortKey, e.getValue());
            }
        }

        // UC-C02: ask_user_confirm 键别名（使 SKILL.md 中的 response_template_keys 值可直接查找）
        String aucPrefix = "scriptconfig.ask_user_confirm.";
        for (Map.Entry<String, String> e : templates.entrySet()) {
            if (e.getKey().startsWith(aucPrefix)) {
                String shortKey = e.getKey().substring(aucPrefix.length());
                aliases.put(shortKey, e.getValue());
            }
        }
        templates.putAll(aliases);
    }
}
