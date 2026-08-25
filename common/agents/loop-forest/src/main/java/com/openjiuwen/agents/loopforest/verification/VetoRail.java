/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.loopforest.verification;

import com.openjiuwen.core.foundation.llm.schema.ToolMessage;
import com.openjiuwen.core.singleagent.rail.AgentCallbackContext;
import com.openjiuwen.core.singleagent.rail.AgentRail;
import com.openjiuwen.core.singleagent.rail.ToolCallInputs;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Veto 写入拒绝 rail——零提及纪律的 Java 实现（GLH-2 Python 验证机制的移植）。
 *
 * <p>Hook {@code beforeToolCall}：当模型尝试写入含契约外顶层字段的内容时，
 * 拒绝该次写入（工具不执行），返回通用错误信息。
 *
 * <p><b>零提及纪律</b>（GLH-1 提示即诱导 CONFIRM 级发现的直接推论）：
 * <ul>
 *   <li>拒绝消息只说"含签名外字段"，<b>永不点名哪个字段多余</b></li>
 *   <li>本 rail 不发任何 goal_signal / progress report 到模型可见区</li>
 *   <li>提及本身就是诱导——GLH-2 用 72 run 双模型验证</li>
 * </ul>
 *
 * <p><b>GLH-2 实验证据</b>（Python lite harness，deepseek-v4-flash + qwen3.7-flash）：
 * <ul>
 *   <li>bait（签名外任务）执行率：OFF 50% → VETO 0%（跨模型一致）</li>
 *   <li>SAT（正事完成率）：VETO = OFF（静默模式，不伤）</li>
 * </ul>
 *
 * <p><b>消息外置</b>：拒绝消息从 classpath {@code /prompts/veto-rejection.txt} 加载
 * （react-rails MR !66 的 prompt 硬编码治本模式），源码零 prompt 字符串。
 *
 * <p><b>Honest boundary</b>：
 * <ul>
 *   <li>无契约的工具 fail-open（放行，不拦截）——{@link VetoContract#covers}</li>
 *   <li>content 非 JSON 对象不拦（无法判定顶层键）——只拦可判定的写入</li>
 *   <li>本 rail 不做 drift 检测、不做 PIN 注入——那些属于其他 rail 的职责</li>
 * </ul>
 *
 * @since 2026-08
 */
public class VetoRail extends AgentRail {

    /** 拒绝计数 telemetry key（RailTelemetry 观察）。 */
    public static final String VETO_COUNT_KEY = "veto_count";

    /** 跳过工具执行的 extra key（agent-core 约定，见 R2 spike 验证）。 */
    static final String SKIP_TOOL_EXTRA_KEY = "_skip_tool";

    private final VetoContract contract;

    private final String rejectionMessage;

    private volatile int vetoCount;

    /**
     * 构造 VetoRail。
     *
     * @param contract 写入契约（白名单）
     * @param rejectionMessage 拒绝消息（外置资源加载，不由本类拼接）
     */
    public VetoRail(VetoContract contract, String rejectionMessage) {
        this.contract = contract;
        this.rejectionMessage = rejectionMessage;
    }

    /**
     * 获取否决次数（遥测消费端读取）。
     *
     * @return 否决计数
     */
    public int getVetoCount() {
        return vetoCount;
    }

    @Override
    public void beforeToolCall(AgentCallbackContext ctx) {
        Object inputsObj = ctx.getInputs();
        if (!(inputsObj instanceof ToolCallInputs inputs)) {
            return;
        }
        String toolName = inputs.getToolName();
        if (!contract.covers(toolName)) {
            return; // 无契约的工具 fail-open
        }
        Set<String> topLevelKeys = extractTopLevelKeys(inputs.getToolArgs());
        if (topLevelKeys == null || topLevelKeys.isEmpty()) {
            return; // 非 JSON 对象或空——不可判定，不拦
        }
        if (contract.shouldVeto(toolName, topLevelKeys)) {
            veto(ctx, inputs);
        }
    }

    /**
     * 执行否决：跳过工具执行 + 预填错误结果 + 发遥测事件。
     *
     * <p>零提及：错误信息只说"含签名外字段"，不列具体键名。
     *
     * @param ctx 回调上下文
     * @param inputs 工具调用输入
     */
    private void veto(AgentCallbackContext ctx, ToolCallInputs inputs) {
        // 1. 跳过工具执行（agent-core 约定）
        Map<String, Object> extra = ctx.getExtra();
        if (extra != null) {
            extra.put(SKIP_TOOL_EXTRA_KEY, Boolean.TRUE);
        }

        // 2. 预填错误结果（rail 值优先于真实执行——R2 spike 字节码验证）
        inputs.setToolResult(Map.of("error", rejectionMessage));
        // ToolMessage(content, toolCallId, name) 三参构造器——toolCallId 必须是本次调用的
        // 真实 id（双参构造器是 (content, toolCallId)，曾误当 (role, content) 用，
        // 导致 API 400：tool_call_id 变成拒绝消息文本——真 LLM e2e 才暴露，mock 不可见）
        String callId = inputs.getToolCall() != null ? inputs.getToolCall().getId() : null;
        inputs.setToolMsg(new ToolMessage(rejectionMessage, callId, inputs.getToolName()));

        // 3. 遥测计数（RailTelemetry sealed 接口暂不扩展——MVP 用计数器，后续加 ToolCallVetoed record）
        vetoCount++;
    }

    /**
     * 从工具参数中提取顶层键集合。
     *
     * <p>支持 Map（直接取 keySet）和 JSON 字符串（解析后取 keySet）。
     * 其他类型返回 null（不可判定，不拦）。
     *
     * @param toolArgs 工具参数
     * @return 顶层键集合；不可判定返回 null
     */
    private Set<String> extractTopLevelKeys(Object toolArgs) {
        if (toolArgs instanceof Map<?, ?> map) {
            return map.keySet().stream()
                    .map(String::valueOf)
                    .collect(Collectors.toSet());
        }
        if (toolArgs instanceof String s) {
            return JsonTopLevelKeys.extract(s);
        }
        return null;
    }

    /**
     * JSON 字符串顶层键提取——单遍扫描、字符串感知（零 JSON 库依赖）。
     *
     * <p>安全承重：值字符串内部的大括号、键名样式文本、转义引号一律不参与结构判定
     * （4-lens 审查实证旧裸计数实现可被单字符右大括号绕过——越权字段放行、
     * 嵌套对象截断漏收、值内键名误拦三条路径）。键仅在 depth==1 且处于键上下文
     * （刚过左大括号，或对象内逗号之后）时收录；扫到顶层对象闭合即止。
     * O(n) 单遍，无正则回溯/递归。
     */
    static final class JsonTopLevelKeys {
        private JsonTopLevelKeys() {
        }

        /**
         * 从 JSON 对象字符串中提取顶层键。
         *
         * @param json JSON 字符串
         * @return 顶层键集合（可能为空）；非 JSON 对象返回 null
         */
        static Set<String> extract(String json) {
            String s = json == null ? "" : json.trim();
            if (!s.startsWith("{")) {
                return null;
            }
            Set<String> keys = new java.util.HashSet<>();
            int depth = 0;
            int arrayDepth = 0;
            boolean inString = false;
            boolean expectKey = false; // 下一个字符串 token 处于键位（刚过 { 或对象内 ,）
            int i = 0;
            while (i < s.length()) {
                char c = s.charAt(i);
                if (inString) {
                    if (c == '\\') {
                        i += 2; // 转义对整体跳过（含 \" 防提前闭串）
                        continue;
                    }
                    if (c == '"') {
                        inString = false;
                    }
                    i++;
                    continue;
                }
                switch (c) {
                    case '"': {
                        // 读完整字符串 token，再判定键/值
                        int end = stringEnd(s, i);
                        if (end < 0) {
                            return null; // 未闭合——不可判定
                        }
                        String token = s.substring(i + 1, end);
                        if (expectKey) {
                            int after = end + 1;
                            while (after < s.length() && Character.isWhitespace(s.charAt(after))) {
                                after++;
                            }
                            if (after < s.length() && s.charAt(after) == ':') {
                                if (depth == 1) {
                                    keys.add(token); // 顶层键
                                }
                                expectKey = false; // 键后为值
                            }
                            // 非键非值（畸形）——忽略，继续扫描
                        }
                        i = end + 1;
                        continue;
                    }
                    case '{':
                        depth++;
                        expectKey = true;
                        i++;
                        continue;
                    case '}':
                        depth--;
                        if (depth <= 0) {
                            return keys; // 顶层对象闭合
                        }
                        i++;
                        continue;
                    case '[':
                        arrayDepth++;
                        i++;
                        continue;
                    case ']':
                        arrayDepth--;
                        i++;
                        continue;
                    case ',':
                        // 对象内的 , 分隔键值对（下一 token 是键）；数组内的 , 分隔元素
                        expectKey = arrayDepth == 0;
                        i++;
                        continue;
                    default:
                        i++;
                }
            }
            return keys;
        }

        /**
         * 找字符串字面量的闭引号索引（跳过转义对）。
         *
         * @param s           JSON 文本
         * @param openQuoteIdx 开引号索引
         * @return 闭引号索引；未闭合返回 -1
         */
        private static int stringEnd(String s, int openQuoteIdx) {
            for (int j = openQuoteIdx + 1; j < s.length(); j++) {
                char c = s.charAt(j);
                if (c == '\\') {
                    j++; // 跳过转义字符
                } else if (c == '"') {
                    return j;
                }
            }
            return -1;
        }
    }
}
