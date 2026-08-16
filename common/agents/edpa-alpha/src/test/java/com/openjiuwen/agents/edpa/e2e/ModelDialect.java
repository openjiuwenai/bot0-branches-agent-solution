/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.agents.edpa.e2e;

import java.util.Map;

/**
 * 模型方言表（test-scope）——把 6 模型 × thinking on/off 矩阵积累的模型行为差异收敛为
 * 单一查表点（此前散落在各 e2e 的 ad-hoc switch/硬编码里）。
 *
 * <p>覆盖的方言维度（全部来自矩阵实证，非猜测）：
 * <ul>
 *   <li><b>thinking 参数写法</b>：deepseek 系用 {@code thinking.type}（参数法）；
 *       qwen 系用 {@code reasoning.enabled}（openrouter 现行参数）；qwen3-30b-a3b-2507
 *       系列用变体名法（instruct/thinking 是独立模型 id，参数开关不适用）。</li>
 *   <li><b>迭代上限建议</b>：thinking/reasoning 模型每轮产出长，SDK 默认 5 轮内难以
 *       收敛到 verified forceFinish 终态（deepseek-pro+thinking 矩阵实证）；推理态
 *       建议 15，非推理态 10 足够。</li>
 * </ul>
 *
 * <p><b>边界</b>：这是测试基建的知识收敛，不是生产层的模型特判——生产侧按装配显式化
 * 范式（MR !312），模型适配由宿主显式配置（未来如需生产层，应做成声明式 profile +
 * 宿主 opt-in，而非启动时隐式特判）。
 *
 * @since 2026-08
 */
final class ModelDialect {
    private ModelDialect() {
    }

    /**
     * Resolves the extra request fields for the given thinking mode.
     *
     * @param mode LLM_THINKING env value: qwen-on / qwen-off / thinking-on / default off
     * @return extraField name → value map (apply via {@code forEach(reqCfg::setExtraField)})
     */
    static Map<String, Object> thinkingParams(String mode) {
        return switch (mode == null ? "" : mode) {
            case "qwen-on" -> Map.of("reasoning", Map.of("enabled", true, "include_reasoning", true));
            case "qwen-off" -> Map.of("reasoning", Map.of("enabled", false));
            case "thinking-on" -> Map.of("thinking", Map.of("type", "enabled"));
            default -> Map.of("thinking", Map.of("type", "disabled"));
        };
    }

    /**
     * Recommends the ReAct iteration ceiling for the given thinking mode — reasoning
     * models produce longer turns and need more room to reach a verified terminal.
     *
     * @param mode LLM_THINKING env value
     * @return recommended max iterations (15 reasoning / 10 plain)
     */
    static int recommendedMaxIterations(String mode) {
        boolean reasoning = "thinking-on".equals(mode) || "qwen-on".equals(mode);
        return reasoning ? 15 : 10;
    }
}
