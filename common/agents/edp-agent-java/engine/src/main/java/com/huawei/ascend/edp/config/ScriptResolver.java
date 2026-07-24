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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 话术工具类（供 EdpaEventRail A 面与 ScriptsRail B 面共用）。
 *
 * <p>单一类承载三职责：</p>
 * <ul>
 *     <li>① SafeFormat 渲染：{@code {var}} 缺失→空串，不抛异常（对齐 Python {@code _SafeDict}）。</li>
 *     <li>② {@link #coerceJsonMap(Object)}：LLM 非法 JSON 容错（复刻 Python {@code _coerce_dict_arg}）。</li>
 *     <li>③ Prompt 文本生成：{@link #cancelRulesPrompt} / {@link #businessRulesPrompt}，
 *         由 {@code ScriptsRail.init()} 调用注入系统提示词（收编原独立 ScriptPromptGenerator）。</li>
 * </ul>
 *
 * <p>纯静态、无状态。{@code scripts==null} 时各方法返回空串/空 Map，保证调用方退化安全。</p>
 *
 * @since 2024-01-01
 *
 */

public final class ScriptResolver {
    private static final Pattern VAR = Pattern.compile("\\{(\\w+)\\}");

    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

    /**
     * 话术 status→key 容错正则（非法 JSON 兜底逐对抓 key:value）。
     *
     * @param "(\"'? the "(\"'? value
     * @return the result
     */
    private static final Pattern KV_PAIR = Pattern.compile("([\"']?)([\\w_]+)\\1\\s*[:：]\\s*([\"']?)(.*?)\\3");

    private static final String FK_PREFIX = "scriptconfig.think_chunk_scripts.think_chunk_fixed_scripts.";
    private static final String FK_MODE = "scriptconfig.think_chunk_scripts.think_chunk_mode";

    private ScriptResolver() {
        // no-op
    }

    // ═══════════════════════════════════════════════════
    // ① 渲染（SafeFormat）
    // ═══════════════════════════════════════════════════

    /**
     * UC-A06 模板变量渲染：{@code {var}} 缺失→保留占位符原样，不替换为空串。
     *
     * <p>与 {@link #safeFormat} 的区别：本方法用于通用话术（tool_start/tool_end等），
     * 缺失变量时保留 {@code {var}} 占位符文本（UC-A06 验收标准第3条）。
     * {@code safeFormat} 用于 ask_user 话术，缺失变量替换为空串（UC-A07 验收标准第5条）。</p>
     *
     * @param template 模板，null/空返回空串
     * @param vars     变量字典，null/空时保留所有占位符
     * @return 渲染后内容
     */

    public static String resolve(String template, Map<String, String> vars) {
        if (template == null || template.isEmpty()) {
            return "";
        }
        if (vars == null || vars.isEmpty()) {
            return template; // 保留占位符原样
        }
        Matcher m = VAR.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String value = vars.get(m.group(1));
            if (value != null) {
                m.appendReplacement(sb, Matcher.quoteReplacement(value));
            } else {
                // UC-A06: 缺失变量保留 {var} 占位符原样
                m.appendReplacement(sb, Matcher.quoteReplacement(m.group(0)));
            }
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /**
     * UC-A07 safeFormat：{@code {var}} 缺失→空串，不抛异常。
     *
     * <p>用于 ask_user 话术模板渲染（UC-A07 验收标准第5条：变量缺失时替换为空字符串）。</p>
     *
     * @param template 模板，null/空返回空串
     * @param vars     变量字典，null/空时所有占位符替换为空串
     * @return 渲染后内容
     */

    public static String safeFormat(String template, Map<String, String> vars) {
        if (template == null || template.isEmpty()) {
            return "";
        }
        if (vars == null || vars.isEmpty()) {
            return VAR.matcher(template).replaceAll("");
        }
        Matcher m = VAR.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String value = vars.getOrDefault(m.group(1), "");
            m.appendReplacement(sb, Matcher.quoteReplacement(value));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /**
     * 按 key 取模板并渲染（UC-A06 保留占位符语义）。
     *
     * @param cfg  话术配置，null 返回空串
     * @param key  模板 key
     * @param vars 变量字典
     * @return 渲染后内容；配置缺失则空串
     */

    public static String resolve(SysScriptsConfig cfg, String key, Map<String, String> vars) {
        return cfg == null ? "" : resolve(cfg.getTemplate(key).orElse(null), vars);
    }

    /**
     * 提取模板内全部 {@code {var}} 变量名。
     *
     * @param template 模板
     * @return 变量名集合（保持出现顺序）
     *
     */

    public static Set<String> extractVariableNames(String template) {
        Set<String> names = new LinkedHashSet<>();
        if (template == null || template.isEmpty()) {
            return names;
        }
        Matcher m = VAR.matcher(template);
        while (m.find()) {
            names.add(m.group(1));
        }
        return names;
    }

    // ═══════════════════════════════════════════════════
    // A 面生命周期事件话术快捷方法（保持 EdpaEventRail 薄）
    // ═══════════════════════════════════════════════════

    /**
     * Returns the tool-start script.
     *
     * @param cfg the cfg value
     * @param tool the tool value
     * @return the result
     */
    public static String toolStart(SysScriptsConfig cfg, String tool) {
        return resolve(cfg, EdpaEventType.TOOL_START.wireName(), Map.of("tool_name", safe(tool)));
    }

    /**
     * Returns the tool-end script.
     *
     * @param cfg the cfg value
     * @param tool the tool value
     * @return the result
     */
    public static String toolEnd(SysScriptsConfig cfg, String tool) {
        return resolve(cfg, EdpaEventType.TOOL_END.wireName(), Map.of("tool_name", safe(tool)));
    }

    /**
     * Returns the tool-start script.
     *
     * @param cfg the cfg value
     * @param queryIntent the queryIntent value
     * @param tool the tool value
     * @return the result
     */
    public static String toolStart(SysScriptsConfig cfg, String queryIntent, String tool) {
        if (queryIntent != null && !queryIntent.isBlank() && cfg != null) {
            String key = "query_intent_tool_text." + queryIntent + ".tool_start";
            String matched = cfg.getTemplate(key).orElse(null);
            if (matched != null && !matched.isBlank()) {
                return matched;
            }
        }
        return resolve(cfg, EdpaEventType.TOOL_START.wireName(), Map.of("tool_name", safe(tool)));
    }

    /**
     * Returns the tool-end script.
     *
     * @param cfg the cfg value
     * @param queryIntent the queryIntent value
     * @param tool the tool value
     * @return the result
     */
    public static String toolEnd(SysScriptsConfig cfg, String queryIntent, String tool) {
        if (queryIntent != null && !queryIntent.isBlank() && cfg != null) {
            String key = "query_intent_tool_text." + queryIntent + ".tool_end";
            String matched = cfg.getTemplate(key).orElse(null);
            if (matched != null && !matched.isBlank()) {
                return matched;
            }
        }
        return resolve(cfg, EdpaEventType.TOOL_END.wireName(), Map.of("tool_name", safe(tool)));
    }

    /**
     * Returns the todo-start script.
     *
     * @param cfg the cfg value
     * @param title the title value
     * @return the result
     */
    public static String todoStart(SysScriptsConfig cfg, String title) {
        return resolve(cfg, EdpaEventType.TODO_START.wireName(), Map.of("title", safe(title)));
    }

    /**
     * Returns the todo-end script.
     *
     * @param cfg the cfg value
     * @param title the title value
     * @return the result
     */
    public static String todoEnd(SysScriptsConfig cfg, String title) {
        return resolve(cfg, EdpaEventType.TODO_END.wireName(), Map.of("title", safe(title)));
    }

    /**
     * Returns the todo-start script.
     *
     * @param cfg the cfg value
     * @param queryIntent the queryIntent value
     * @param title the title value
     * @return the result
     */
    public static String todoStart(SysScriptsConfig cfg, String queryIntent, String title) {
        if (queryIntent != null && !queryIntent.isBlank() && cfg != null) {
            String key = "query_intent_tool_text." + queryIntent + ".tool_start";
            String matched = cfg.getTemplate(key).orElse(null);
            if (matched != null && !matched.isBlank()) {
                return matched;
            }
        }
        return resolve(cfg, EdpaEventType.TODO_START.wireName(), Map.of("title", safe(title)));
    }

    /**
     * Returns the todo-end script.
     *
     * @param cfg the cfg value
     * @param queryIntent the queryIntent value
     * @param title the title value
     * @return the result
     */
    public static String todoEnd(SysScriptsConfig cfg, String queryIntent, String title) {
        if (queryIntent != null && !queryIntent.isBlank() && cfg != null) {
            String key = "query_intent_tool_text." + queryIntent + ".tool_end";
            String matched = cfg.getTemplate(key).orElse(null);
            if (matched != null && !matched.isBlank()) {
                return matched;
            }
        }
        return resolve(cfg, EdpaEventType.TODO_END.wireName(), Map.of("title", safe(title)));
    }

    /**
     * Resolve tool start by intent.
     *
     * @param cfg the cfg value
     * @param queryIntent the queryIntent value
     * @param toolName the toolName value
     * @return the result
     */
    public static Optional<String> resolveToolStartByIntent(SysScriptsConfig cfg, String queryIntent, String toolName) {
        if (cfg == null || queryIntent == null || queryIntent.isBlank() || "null".equals(queryIntent)) {
            return Optional.empty();
        }
        String key = "scriptconfig.query_intent_tool_text." + queryIntent + ".tool_start";
        String template = cfg.getTemplate(key).orElse(null);
        if (template != null && !template.isBlank()) {
            return Optional.of(resolve(template, Map.of("tool_name", safe(toolName))));
        }
        return Optional.empty();
    }

    /**
     * Resolve tool end by intent.
     *
     * @param cfg the cfg value
     * @param queryIntent the queryIntent value
     * @param toolName the toolName value
     * @return the result
     */
    public static Optional<String> resolveToolEndByIntent(SysScriptsConfig cfg, String queryIntent, String toolName) {
        if (cfg == null || queryIntent == null || queryIntent.isBlank() || "null".equals(queryIntent)) {
            return Optional.empty();
        }
        String key = "scriptconfig.query_intent_tool_text." + queryIntent + ".tool_end";
        String template = cfg.getTemplate(key).orElse(null);
        if (template != null && !template.isBlank()) {
            return Optional.of(resolve(template, Map.of("tool_name", safe(toolName))));
        }
        return Optional.empty();
    }

    /**
     * Returns the todolist-start script.
     *
     * @param cfg the cfg value
     * @return the result
     */
    public static String todolistStart(SysScriptsConfig cfg) {
        return cfg == null ? "" : safe(cfg.getTemplate(EdpaEventType.TODOLIST_START.wireName()).orElse(null));
    }

    /**
     * Returns the todolist-end script.
     *
     * @param cfg the cfg value
     * @return the result
     */
    public static String todolistEnd(SysScriptsConfig cfg) {
        return cfg == null ? "" : safe(cfg.getTemplate(EdpaEventType.TODOLIST_END.wireName()).orElse(null));
    }

    /**
     * Returns the interrupt-start script.
     *
     * @param cfg the cfg value
     * @return the result
     */
    public static String interruptStart(SysScriptsConfig cfg) {
        return cfg == null ? "" : safe(cfg.getTemplate(EdpaEventType.INTERRUPT_START.wireName()).orElse(null));
    }

    /**
     * Returns the request-start script.
     *
     * @param cfg the cfg value
     * @return the result
     */
    public static String requestStart(SysScriptsConfig cfg) {
        return getScriptByEvent(cfg, ScriptEvent.REQUEST_START);
    }

    /**
     * Returns the planning-start script.
     *
     * @param cfg the cfg value
     * @return the result
     */
    public static String planningStart(SysScriptsConfig cfg) {
        return getScriptByEvent(cfg, ScriptEvent.PLANNING_START);
    }

    /**
     * Gets the script by event.
     *
     * @param cfg the cfg value
     * @param event the event value
     * @return the result
     */
    public static String getScriptByEvent(SysScriptsConfig cfg, ScriptEvent event) {
        if (cfg == null || event == null) {
            return "";
        }
        return safe(cfg.getTemplate(event.getKey()).orElse(null));
    }

    /**
     * Gets the script by key.
     *
     * @param cfg the cfg value
     * @param key the key value
     * @return the result
     */
    public static String getScriptByKey(SysScriptsConfig cfg, String key) {
        return cfg == null ? "" : safe(cfg.getTemplate(key).orElse(null));
    }

    // ═══════════════════════════════════════════════════
    // 固定帧 think_chunk 切分（对齐 Python select_fixed_scripts + feeder）
    // ═══════════════════════════════════════════════════

    /**
     * Checks whether fixed script mode.
     *
     * @param cfg the cfg value
     * @return the result
     */
    public static boolean isFixedScriptMode(SysScriptsConfig cfg) {
        if (cfg == null) {
            return false;
        }
        String mode = cfg.getTemplate(FK_MODE).orElse(null);
        if (!"fixed_script".equals(mode)) {
            return false;
        }
        String enabled = cfg.getTemplate(FK_PREFIX + "enabled").orElse(null);
        return "true".equalsIgnoreCase(enabled);
    }

    /**
     * Selects fixed scripts for the given phase.
     *
     * @param cfg the cfg value
     * @param phase the phase value
     * @param userQuery the userQuery value
     * @return the result
     */
    public static List<String> selectFixedScripts(SysScriptsConfig cfg, String phase, String userQuery) {
        if (cfg == null) {
            return List.of();
        }
        switch (phase) {
            case "planning" :
                // ★ 从配置中读取 query_patterns 进行关键词匹配（替代硬编码）
                List<String> matchedScripts = cfg.matchQueryPatterns(userQuery);
                if (!matchedScripts.isEmpty()) {
                    return matchedScripts;
                }

                // 未命中时降级到 default_scripts
                List<String> defScripts = parseScriptList(cfg.getTemplate(FK_PREFIX + "default_scripts").orElse(null));
                if (!defScripts.isEmpty()) {
                    return defScripts;
                }
                return parseScriptList(cfg.getTemplate(FK_PREFIX + "scripts").orElse(null));
            case "executing" :
                List<String> execScripts = parseScriptList(cfg.getTemplate(FK_PREFIX
                    + "execution_scripts").orElse(null));
                if (!execScripts.isEmpty()) {
                    return execScripts;
                }
                return parseScriptList(cfg.getTemplate(FK_PREFIX + "scripts").orElse(null));
            case "resuming" :
                String enableResume = cfg.getTemplate(FK_PREFIX + "enable_resume_scripts").orElse(null);
                if ("false".equalsIgnoreCase(enableResume)) {
                    return List.of();
                }
                // 先走 query_patterns 关键词匹配（与 planning 阶段一致）
                List<String> resumeMatched = cfg.matchQueryPatterns(userQuery);
                if (!resumeMatched.isEmpty()) {
                    return resumeMatched;
                }
                // 未匹配关键词时降级到 resume_scripts
                List<String> resumeScripts = parseScriptList(cfg.getTemplate(FK_PREFIX
                    + "resume_scripts").orElse(null));
                if (!resumeScripts.isEmpty()) {
                    return resumeScripts;
                }
                return parseScriptList(cfg.getTemplate(FK_PREFIX + "scripts").orElse(null));
            default :
                return parseScriptList(cfg.getTemplate(FK_PREFIX + "default_scripts").orElse(null));
        }
    }

    /**
     * Splits fixed scripts into frames of the given size.
     *
     * @param scripts the scripts value
     * @param charsPerFrame the charsPerFrame value
     * @return the result
     */
    public static List<String> splitFixedScriptsIntoFrames(List<String> scripts, int charsPerFrame) {
        List<String> result = new ArrayList<>();
        if (scripts == null || scripts.isEmpty()) {
            return result;
        }
        for (String script : scripts) {
            if (script == null || script.isBlank()) {
                continue;
            }
            if (charsPerFrame <= 0) {
                result.add(script);
                continue;
            }
            for (int i = 0; i < script.length(); i += charsPerFrame) {
                result.add(script.substring(i, Math.min(i + charsPerFrame, script.length())));
            }
        }
        return result;
    }

    private static List<String> parseScriptList(String raw) {
        List<String> result = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return result;
        }
        for (String line : raw.split("\n")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                if (trimmed.startsWith("- ")) {
                    trimmed = trimmed.substring(2);
                } else if (trimmed.startsWith("-")) {
                    trimmed = trimmed.substring(1).trim();
                } else {
                    trimmed = trimmed; // line without list marker, keep as-is
                }
                if (!trimmed.isEmpty()) {
                    result.add(trimmed);
                }
            }
        }
        return result;
    }

    /**
     * Parses an integer, returning a default on failure.
     *
     * @param value the value value
     * @param def the def value
     * @return the result
     */
    public static int parseIntOrDefault(String value, int def) {
        if (value == null || value.isBlank()) {
            return def;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    // ═══════════════════════════════════════════════════
    // ② coerceJsonMap（复刻 Python _coerce_dict_arg，LLM 非法 JSON 容错）
    // ═══════════════════════════════════════════════════

    /**
     * 把 LLM 的 {@code response_template_keys/vars} 解析为 {@code Map<String,String>}。
     *
     * <p>容错链：</p>
     * <ol>
     *     <li>已是 Map → 转字符串键值。</li>
     *     <li>字符串：去首尾包裹引号；中文 {@code “”‘：} 归一 ASCII；尝试 Jackson 解析。</li>
     *     <li>JSON 解析失败 → 正则逐对抓 {@code key:value}。</li>
     * </ol>
     * <p>失败兜底返回空 Map（不抛异常），避免话术降级中断主流程。</p>
     *
     * @param raw LLM 原始输出
     * @return 字符串键值 Map
     *
     */

    @SuppressWarnings("unchecked")
    /**
     * Coerces a raw object into a string map.
     *
     * @param raw the raw value
     * @return the result
     */
    public static Map<String, String> coerceJsonMap(Object raw) {
        Map<String, String> out = new LinkedHashMap<>();
        if (raw == null) {
            return out;
        }
        try {
            // 1) 已是 Map
            if (raw instanceof Map<?, ?> map) {
                map.forEach((k, v) -> out.put(String.valueOf(k), v == null ? "" : String.valueOf(v)));
                return out;
            }

            // 1b) 已是 List → Map（每个元素同时作为 key 和 value）
            if (raw instanceof List<?> listRaw) {
                for (Object item : listRaw) {
                    if (item != null) {
                        String val = String.valueOf(item);
                        out.put(val, val);
                    }
                }
                return out;
            }
            String s = String.valueOf(raw).trim();
            if (s.isEmpty()) {
                return out;
            }

            // 2) 去首尾包裹引号；中文引号/冒号归一
            if ((s.startsWith("\"") && s.endsWith("\"")) || (s.startsWith("'") && s.endsWith("'"))) {
                s = s.substring(1, s.length() - 1);
            }
            s = s.replace('\u201c', '"').replace('\u201d', '"').replace('\u2018', '\'').replace('\u2019', '\'')
                    .replace('\uff1a', ':');
            tryParseJsonString(s, out);

            // 3) 非法 JSON → 正则逐对抓取
            Matcher m = KV_PAIR.matcher(s);
            while (m.find()) {
                out.put(m.group(2), m.group(4));
            }
        } catch (IllegalStateException ignore) {
            // fallback return empty Map
        }
        return out;
    }

    private static void tryParseJsonString(String s, Map<String, String> out) {
        try {
            Object parsed = JSON_MAPPER.readValue(s, Object.class);
            if (parsed instanceof Map<?, ?> map) {
                map.forEach((k, v) -> out.put(String.valueOf(k), v == null ? "" : String.valueOf(v)));
                return;
            }

            // List → Map（每个元素同时作为 key 和 value，支持 LLM 传 ["out_of_scope"] 而非 {"out_of_scope":"out_of_scope"}）
            if (parsed instanceof List<?> list) {
                for (Object item : list) {
                    if (item != null) {
                        String val = String.valueOf(item);
                        out.put(val, val);
                    }
                }
            }
        } catch (JsonProcessingException ignore) {
            // 落入正则兜底
        }
    }

    // ═══════════════════════════════════════════════════
    // ③ Prompt 文本生成（收编原 ScriptPromptGenerator，由 ScriptsRail.init 调用）
    // ═══════════════════════════════════════════════════

    /**
     * 生成 cancel 规则 Prompt 段：告知 LLM 调 {@code cancel_task} 时 {@code reason} 取值。
     *
     * <p>使用配置驱动的键映射，展示配置中实际存在的键名。</p>
     *
     * @param scripts 话术配置，null 返回空串
     * @return Prompt 文本
     *
     */

    public static String cancelRulesPrompt(SysScriptsConfig scripts) {
        if (scripts == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder("## 取消/超范围话术规则\n");

        // UC-C03 两步取消：cancel_confirm 由 ask_user(status=cancel_confirm) 触发（见 businessRulesPrompt），
        // cancel_task 的 reason 只列真正用于"执行取消"的 key，避免 LLM 混淆两步流程。
        sb.append("调用 cancel_task 时 reason 须取以下值之一（对应配置内话术）：");
        appendKey(sb, scripts, ScriptConstants.SCRIPT_TASK_CANCELLED, "取消任务");
        appendKey(sb, scripts, ScriptConstants.SCRIPT_OUT_OF_SCOPE, "超范围");
        return sb.toString();
    }

    /**
     * 生成业务规则 Prompt 段：告知 LLM {@code ask_user} 的 status→key 映射与需补 vars。
     *
     * <p>使用配置驱动的键映射，展示配置中实际存在的键名。</p>
     *
     * @param scripts 话术配置，null 返回空串
     * @return Prompt 文本
     *
     */

    public static String businessRulesPrompt(SysScriptsConfig scripts) {
        if (scripts == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder("## 业务话术规则\n");
        sb.append("ask_user 通过 response_template_status + response_template_keys + response_template_vars 选话术。");
        sb.append("\n可用业务话术键（来自各 SKILL.yaml scripts 字段，动态收集）：");

        // 动态遍历所有业务话术键（来自 SKILL.yaml，不含通用前缀）
        for (String key : scripts.listBusinessScriptKeys()) {
            sb.append("\n- ").append(key);
        }

        // 通用话术键（取消确认、超范围等）单独列出
        appendKey(sb, scripts, ScriptConstants.SCRIPT_CANCEL_CONFIRM, "取消确认（两步取消第一步）");
        appendKey(sb, scripts, ScriptConstants.SCRIPT_OUT_OF_SCOPE, "超范围提示");
        return sb.toString();
    }

    /**
     * 按话术常量名追加到 Prompt StringBuilder。
     *
     * <p>内部通过 {@link SysScriptsConfig#hasScript(String)} 检查话术是否存在，
     * 存在时追加配置中的实际键名（而非常量名）。</p>
     *
     * @param sb   StringBuilder
     * @param cfg  话术配置
     * @param constantName 常量名（如 "task_cancelled"，不含 SCRIPT_ 前缀）
     * @param desc 描述文本
     *
     */

    private static void appendKey(StringBuilder sb, SysScriptsConfig cfg, String constantName, String desc) {
        if (cfg == null || constantName == null) {
            return;
        }

        // 检查话术是否存在（constantName 已含 SCRIPT_ 前缀）
        if (!cfg.hasScript(constantName)) {
            return;
        }

        // 获取配置中的实际键名
        String resolvedKey = cfg.resolveScriptKey(constantName);
        sb.append("\n- ").append(resolvedKey).append("（").append(desc).append("）");
    }

    // ═══════════════════════════════════════════════════
    // ④ ask_user 话术解析（F3-fix：供 EdpaEventRail.onToolException 调用）
    // ═══════════════════════════════════════════════════

    /**
     * 解析 ask_user 话术参数 → 渲染 → 写入 extra（`_edp_response_template`/`_edp_last_script_key`/`_edp_selected_product`）。
     *
     * <p><b>F3-fix 承载点</b>：由 {@code EdpaEventRail.onToolException}（p=80，异常处理回调，必定触发）调用，
     * 而非 {@code beforeToolCall}（p=80 被 {@code AskUserTemplateRail}(85) 抛异常中断，不可达）。
     * 解析与 {@code interrupt_start} 发射在同一回调，无时序竞态。</p>
     *
     * <p><b>键映射说明</b>：
     * <ul>
     *   <li>LLM 通过 Prompt 获知配置中的话术键名，填充 {@code response_template_keys}</li>
     *   <li>此处 key 是配置中的实际键名，直接用 {@link SysScriptsConfig#has(String)} 校验</li>
     *   <li>status 值（如 "confirm"）直接与字符串常量比较，绕过 ScriptConstants</li>
     * </ul>
     *
     * @param scripts 话术配置
     * @param rawArgs ask_user 工具原始入参（Map 或 JSON 字符串）
     * @param extra   写入目标 Map（通常 {@code ctx.getExtra()}）
     * @return true 表示已写入业务话术
     */

    public static boolean resolveAskUser(SysScriptsConfig scripts, Object rawArgs, Map<String, Object> extra) {
        if (scripts == null || extra == null) {
            return false;
        }
        Map<String, Object> args = normalizeArgs(rawArgs);
        if (args.isEmpty()) {
            return false;
        }

        // status 是配置中的键名（如 "confirm"），不是 ScriptConstants 常量
        String status = str(args.get("response_template_status"));
        Map<String, String> keys = coerceJsonMap(args.get("response_template_keys"));
        if (keys.isEmpty() || isBlank(status)) {
            return false; // 无话术参数放行
        }
        String key = keys.get(status);
        if (isBlank(key) || !scripts.has(key)) {
            return false; // 配置缺位放行
        }
        Map<String, String> vars = coerceJsonMap(args.get("response_template_vars"));
        extra.put(ScriptConstants.KEY_RESPONSE_TEMPLATE, safeFormat(scripts.getTemplate(key).orElse(null), vars));
        extra.put(ScriptConstants.KEY_LAST_SCRIPT, key);

        // status "confirm" 是配置中的键名，不是 ScriptConstants 常量
        if ("confirm".equalsIgnoreCase(status)) {
            extra.put(ScriptConstants.KEY_SELECTED_PRODUCT, vars);
        }
        return true;
    }

    /**
     * 把工具原始入参归一为 {@code Map<String,Object>}（Map 直转 / JSON 字符串解析）。
     *
     * @param rawArgs the rawArgs value
     * @return the result
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> normalizeArgs(Object rawArgs) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (rawArgs instanceof Map<?, ?> map) {
            map.forEach((k, v) -> result.put(String.valueOf(k), v));
            return result;
        }
        if (rawArgs instanceof String s && !s.isBlank()) {
            try {
                Map<String, Object> parsed = JSON_MAPPER.readValue(s,
                        new com.fasterxml.jackson.core.type.TypeReference<LinkedHashMap<String, Object>>() {
                        });
                return parsed != null ? parsed : result;
            } catch (JsonProcessingException ignore) {
                // 非 JSON，返回空
            }
        }
        return result;
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
