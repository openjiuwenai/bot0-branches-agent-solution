/*
 * Copyright 2026 Huawei Technologies Co., Ltd.
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

/**
 * Governance配置聚合模型。
 *
 * <p>定位：聚合三个治理域配置（planrule、actrule、scriptconfig）</p>
 * <p>作用：</p>
 * <ul>
 *     <li>统一管理三个治理域配置</li>
 *     <li>提供配置继承覆盖机制</li>
 *     <li>支持场景级配置覆盖框架级配置</li>
 * </ul>
 *
 * <p>继承覆盖规则：</p>
 * <ul>
 *     <li>planrule.scope字段：替代式覆盖（完全覆盖）</li>
 *     <li>planrule.supplementaryPrompt字段：替代式覆盖</li>
 *     <li>actrule.maxSubtasks等参数字段：继承式覆盖（只写差异）</li>
 *     <li>scriptconfig.summary字段：替代式覆盖</li>
 *     <li>scriptconfig.generalScripts字段：替代式覆盖</li>
 * </ul>
 *
 * @since 2024-01-01
 */
public class GovernanceConfig {

    /** 身份域配置（planrule.yaml）。 */
    private PlanRuleConfig planrule;

    /** 规划域+执行域配置（actrule.yaml）。 */
    private ActRuleConfig actrule;

    /** 交互域配置（scriptconfig.yaml）。 */
    private ScriptConfig scriptconfig;

    /** Gets the planrule. */
    public PlanRuleConfig getPlanrule() {
        return planrule;
    }
    /** Sets the planrule. */
    public void setPlanrule(PlanRuleConfig planrule) {
        this.planrule = planrule;
    }

    /** Gets the actrule. */
    public ActRuleConfig getActrule() {
        return actrule;
    }
    /** Sets the actrule. */
    public void setActrule(ActRuleConfig actrule) {
        this.actrule = actrule;
    }

    /** Gets the scriptconfig. */
    public ScriptConfig getScriptconfig() {
        return scriptconfig;
    }
    /** Sets the scriptconfig. */
    public void setScriptconfig(ScriptConfig scriptconfig) {
        this.scriptconfig = scriptconfig;
    }

    /**
     * 合并场景级配置（场景级覆盖框架级）。
     *
     * <p>继承覆盖规则：字段级别的继承覆盖，不是文件级别的完全覆盖。</p>
     *
     * @param scenarioConfig 场景级GovernanceConfig
     */
    public void mergeScenarioConfig(GovernanceConfig scenarioConfig) {
        if (scenarioConfig == null) {
            return;
        }

        // 合并planrule配置
        if (scenarioConfig.getPlanrule() != null) {
            mergePlanrule(scenarioConfig.getPlanrule());
        }

        // 合并actrule配置
        if (scenarioConfig.getActrule() != null) {
            mergeActrule(scenarioConfig.getActrule());
        }

        // 合并scriptconfig配置
        if (scenarioConfig.getScriptconfig() != null) {
            mergeScriptconfig(scenarioConfig.getScriptconfig());
        }
    }

    /**
     * 合并planrule配置（替代式覆盖）。
     */
    private void mergePlanrule(PlanRuleConfig scenarioPlanrule) {
        if (this.planrule == null) {
            this.planrule = scenarioPlanrule;
            return;
        }

        // role: 继承式覆盖（只写差异）
        if (scenarioPlanrule.getRole() != null) {
            this.planrule.setRole(scenarioPlanrule.getRole());
        }

        // description: 继承式覆盖
        if (scenarioPlanrule.getDescription() != null) {
            this.planrule.setDescription(scenarioPlanrule.getDescription());
        }

        // scenarioName: 继承式覆盖（仅场景级配置，框架默认无值）
        if (scenarioPlanrule.getScenarioName() != null) {
            this.planrule.setScenarioName(scenarioPlanrule.getScenarioName());
        }

        // scenarioDescription: 继承式覆盖（仅场景级配置，框架默认无值）
        if (scenarioPlanrule.getScenarioDescription() != null) {
            this.planrule.setScenarioDescription(scenarioPlanrule.getScenarioDescription());
        }

        // scope: allowed 替代式覆盖，denied 追加拼接（并集）
        if (scenarioPlanrule.getScope() != null) {
            if (this.planrule.getScope() == null) {
                this.planrule.setScope(scenarioPlanrule.getScope());
            } else {
                // allowed: 替代式覆盖
                if (scenarioPlanrule.getScope().getAllowed() != null) {
                    this.planrule.getScope().setAllowed(scenarioPlanrule.getScope().getAllowed());
                }
                // denied: 追加拼接（并集）
                if (scenarioPlanrule.getScope().getDenied() != null) {
                    String frameworkDenied = this.planrule.getScope().getDenied();
                    String scenarioDenied = scenarioPlanrule.getScope().getDenied();
                    String mergedDenied = mergeDeniedFields(frameworkDenied, scenarioDenied);
                    this.planrule.getScope().setDenied(mergedDenied);
                }
            }
        }

        // supplementaryPrompt: baseProtocol 保持框架内置，additionalPrompt 有序拼接
        if (scenarioPlanrule.getSupplementaryPrompt() != null) {
            if (this.planrule.getSupplementaryPrompt() == null) {
                this.planrule.setSupplementaryPrompt(scenarioPlanrule.getSupplementaryPrompt());
            } else {
                // baseProtocol: 保持框架内置，不可覆盖
                // additionalPrompt: 有序拼接（框架additionalPrompt + 场景additionalPrompt）
                if (scenarioPlanrule.getSupplementaryPrompt().getAdditionalPrompt() != null) {
                    String frameworkAdditional = this.planrule.getSupplementaryPrompt().getAdditionalPrompt();
                    String scenarioAdditional = scenarioPlanrule.getSupplementaryPrompt().getAdditionalPrompt();
                    String mergedAdditional = mergeSupplementaryPrompts(frameworkAdditional, scenarioAdditional);
                    this.planrule.getSupplementaryPrompt().setAdditionalPrompt(mergedAdditional);
                }
            }
        }

        // skillRouting: 叠加合并（框架通用路由 + 场景路由）
        if (scenarioPlanrule.getSkillRouting() != null) {
            if (this.planrule.getSkillRouting() == null) {
                this.planrule.setSkillRouting(scenarioPlanrule.getSkillRouting());
            } else {
                java.util.List<PlanRuleConfig.SkillRoute> mergedRouting = new java.util.ArrayList<>();
                mergedRouting.addAll(this.planrule.getSkillRouting()); // 先加框架路由
                mergedRouting.addAll(scenarioPlanrule.getSkillRouting()); // 再加场景路由
                this.planrule.setSkillRouting(mergedRouting);
            }
        }
    }

    /**
     * 合并 denied 字段（追加拼接，取并集）。
     *
     * <p>策略：框架denied + 场景denied 取并集，场景不能移除框架的禁止项。</p>
     *
     * @param frameworkDenied 框架级 denied 配置
     * @param scenarioDenied 场景级 denied 配置
     * @return 合并后的 denied 字段
     */
    private String mergeDeniedFields(String frameworkDenied, String scenarioDenied) {
        if (frameworkDenied == null || frameworkDenied.isEmpty()) {
            return scenarioDenied;
        }
        if (scenarioDenied == null || scenarioDenied.isEmpty()) {
            return frameworkDenied;
        }

        // 使用分隔符分割，去重，再合并
        java.util.Set<String> deniedSet = new java.util.LinkedHashSet<>();

        // 分割框架 denied（支持多种分隔符：中文顿号、英文逗号、分号等）
        String[] frameworkItems = frameworkDenied.split("[、,;]\\s*");
        for (String item : frameworkItems) {
            if (!item.trim().isEmpty()) {
                deniedSet.add(item.trim());
            }
        }

        // 分割场景 denied
        String[] scenarioItems = scenarioDenied.split("[、,;]\\s*");
        for (String item : scenarioItems) {
            if (!item.trim().isEmpty()) {
                deniedSet.add(item.trim());
            }
        }

        // 使用中文顿号连接
        return deniedSet.stream().collect(java.util.stream.Collectors.joining("、"));
    }

    /**
     * 合并 supplementaryPrompt 的 additionalPrompt（章节智能合并）。
     *
     * <p>策略：解析 markdown 章节（##），同名章节内容追加，而非生成独立章节。</p>
     *
     * @param frameworkAdditional 框架级 additionalPrompt
     * @param scenarioAdditional 场景级 additionalPrompt
     * @return 合并后的 additionalPrompt
     */
    private String mergeSupplementaryPrompts(String frameworkAdditional, String scenarioAdditional) {
        if (frameworkAdditional == null || frameworkAdditional.isEmpty()) {
            return scenarioAdditional;
        }
        if (scenarioAdditional == null || scenarioAdditional.isEmpty()) {
            return frameworkAdditional;
        }

        // 简化实现：直接拼接，框架在前，场景在后
        // 未来可优化为章节智能合并（解析 ## 标题，同名章节合并）
        return frameworkAdditional + "\n\n" + scenarioAdditional;
    }

    /**
     * 合并actrule配置（继承式覆盖）。
     */
    private void mergeActrule(ActRuleConfig scenarioActrule) {
        if (this.actrule == null) {
            this.actrule = scenarioActrule;
            return;
        }

        // 资源限制类字段：继承（取min，场景不能放宽框架上限）
        if (scenarioActrule.getMaxSubtasks() != null) {
            Integer frameworkValue = this.actrule.getMaxSubtasks();
            Integer scenarioValue = scenarioActrule.getMaxSubtasks();
            Integer mergedValue = (frameworkValue != null) ? Math.min(frameworkValue, scenarioValue) : scenarioValue;
            this.actrule.setMaxSubtasks(mergedValue);
        }
        if (scenarioActrule.getMaxSteps() != null) {
            Integer frameworkValue = this.actrule.getMaxSteps();
            Integer scenarioValue = scenarioActrule.getMaxSteps();
            Integer mergedValue = (frameworkValue != null) ? Math.min(frameworkValue, scenarioValue) : scenarioValue;
            this.actrule.setMaxSteps(mergedValue);
        }
        if (scenarioActrule.getAllowedTools() != null) {
            // 叠加合并：框架工具 + 场景扩展工具，去重但保持顺序
            java.util.LinkedHashSet<String> merged = new java.util.LinkedHashSet<>(
                    this.actrule.getAllowedTools() != null ? this.actrule.getAllowedTools() : java.util.List.of());
            merged.addAll(scenarioActrule.getAllowedTools());
            this.actrule.setAllowedTools(new java.util.ArrayList<>(merged));
        }
        if (scenarioActrule.getEnableTaskLoop() != null) {
            this.actrule.setEnableTaskLoop(scenarioActrule.getEnableTaskLoop());
        }
        if (scenarioActrule.getSkillMode() != null) {
            this.actrule.setSkillMode(scenarioActrule.getSkillMode());
        }
        // toolLimits: 逐key合并，场景只声明需要调整的工具，限制值取min（场景不能放宽框架限制）
        if (scenarioActrule.getToolLimits() != null) {
            if (this.actrule.getToolLimits() == null) {
                this.actrule.setToolLimits(scenarioActrule.getToolLimits());
            } else {
                // 逐key合并：框架toolLimits + 场景toolLimits，同key取min
                java.util.Map<String, Integer> mergedToolLimits = new java.util.HashMap<>(this.actrule.getToolLimits());
                for (java.util.Map.Entry<String, Integer> entry : scenarioActrule.getToolLimits().entrySet()) {
                    String toolName = entry.getKey();
                    Integer scenarioLimit = entry.getValue();
                    Integer frameworkLimit = mergedToolLimits.get(toolName);
                    // 场景只能设更小值，不能放宽框架限制
                    if (frameworkLimit != null) {
                        mergedToolLimits.put(toolName, Math.min(frameworkLimit, scenarioLimit));
                    } else {
                        // 框架无该工具限制，场景新增限制
                        mergedToolLimits.put(toolName, scenarioLimit);
                    }
                }
                this.actrule.setToolLimits(mergedToolLimits);
            }
        }

        // todolistEntries: 替代式覆盖（场景提供完整定义，框架默认无值）
        if (scenarioActrule.getTodolistEntries() != null) {
            this.actrule.setTodolistEntries(scenarioActrule.getTodolistEntries());
        }
        // todolistDynamicPaths: 替代式覆盖
        if (scenarioActrule.getTodolistDynamicPaths() != null) {
            this.actrule.setTodolistDynamicPaths(scenarioActrule.getTodolistDynamicPaths());
        }
    }

    /**
     * 合成scriptconfig配置（逐字段继承式覆盖）。
     */
    private void mergeScriptconfig(ScriptConfig scenarioScriptconfig) {
        if (this.scriptconfig == null) {
            this.scriptconfig = scenarioScriptconfig;
            return;
        }

        // generalScripts: 继承式覆盖（逐字段合并，未被场景覆盖的字段继承框架默认值）
        if (scenarioScriptconfig.getGeneralScripts() != null) {
            mergeGeneralScripts(scenarioScriptconfig.getGeneralScripts());
        }

        // thinkChunkScripts: 继承式覆盖
        if (scenarioScriptconfig.getThinkChunkScripts() != null) {
            mergeThinkChunkScripts(scenarioScriptconfig.getThinkChunkScripts());
        }

        // askUserConfirm: 继承式覆盖
        if (scenarioScriptconfig.getAskUserConfirm() != null) {
            mergeAskUserConfirm(scenarioScriptconfig.getAskUserConfirm());
        }
    }

    /**
     * 合并 askUserConfirm 配置（继承式覆盖，逐字段合并）。
     */
    private void mergeAskUserConfirm(ScriptConfig.AskUserConfirm scenarioConfirm) {
        if (this.scriptconfig.getAskUserConfirm() == null) {
            this.scriptconfig.setAskUserConfirm(new ScriptConfig.AskUserConfirm());
        }
        ScriptConfig.AskUserConfirm target = this.scriptconfig.getAskUserConfirm();

        if (scenarioConfirm.getPurchaseConfirm() != null) {
            target.setPurchaseConfirm(scenarioConfirm.getPurchaseConfirm());
        }
        if (scenarioConfirm.getCancelConfirm() != null) {
            target.setCancelConfirm(scenarioConfirm.getCancelConfirm());
        }
    }

    /**
     * 合并 generalScripts 配置（继承式覆盖，逐字段合并）。
     */
    private void mergeGeneralScripts(ScriptConfig.GeneralScripts scenarioScripts) {
        if (this.scriptconfig.getGeneralScripts() == null) {
            this.scriptconfig.setGeneralScripts(new ScriptConfig.GeneralScripts());
        }
        ScriptConfig.GeneralScripts target = this.scriptconfig.getGeneralScripts();

        if (scenarioScripts.getToolStart() != null) {
            target.setToolStart(scenarioScripts.getToolStart());
        }
        if (scenarioScripts.getToolEnd() != null) {
            target.setToolEnd(scenarioScripts.getToolEnd());
        }
        if (scenarioScripts.getTodoStart() != null) {
            target.setTodoStart(scenarioScripts.getTodoStart());
        }
        if (scenarioScripts.getTodoEnd() != null) {
            target.setTodoEnd(scenarioScripts.getTodoEnd());
        }
        if (scenarioScripts.getTodolistStart() != null) {
            target.setTodolistStart(scenarioScripts.getTodolistStart());
        }
        if (scenarioScripts.getTodolistEnd() != null) {
            target.setTodolistEnd(scenarioScripts.getTodolistEnd());
        }
        if (scenarioScripts.getInterruptStart() != null) {
            target.setInterruptStart(scenarioScripts.getInterruptStart());
        }
        if (scenarioScripts.getRequestStart() != null) {
            target.setRequestStart(scenarioScripts.getRequestStart());
        }
        if (scenarioScripts.getPlanningStart() != null) {
            target.setPlanningStart(scenarioScripts.getPlanningStart());
        }
        if (scenarioScripts.getTaskCancelled() != null) {
            target.setTaskCancelled(scenarioScripts.getTaskCancelled());
        }
        if (scenarioScripts.getCancelConfirm() != null) {
            target.setCancelConfirm(scenarioScripts.getCancelConfirm());
        }
        if (scenarioScripts.getOutOfScope() != null) {
            target.setOutOfScope(scenarioScripts.getOutOfScope());
        }
    }

    /**
     * 合并thinkChunkScripts配置（继承式覆盖）。
     */
    private void mergeThinkChunkScripts(ScriptConfig.ThinkChunkScripts scenarioThinkChunk) {
        if (this.scriptconfig.getThinkChunkScripts() == null) {
            this.scriptconfig.setThinkChunkScripts(scenarioThinkChunk);
            return;
        }

        ScriptConfig.ThinkChunkScripts defaultThinkChunk = this.scriptconfig.getThinkChunkScripts();

        if (scenarioThinkChunk.getThinkChunkMode() != null) {
            defaultThinkChunk.setThinkChunkMode(scenarioThinkChunk.getThinkChunkMode());
        }
        if (scenarioThinkChunk.getThinkChunkFixedScripts() != null) {
            if (defaultThinkChunk.getThinkChunkFixedScripts() == null) {
                defaultThinkChunk.setThinkChunkFixedScripts(scenarioThinkChunk.getThinkChunkFixedScripts());
            } else {
                mergeFixedScripts(defaultThinkChunk.getThinkChunkFixedScripts(),
                        scenarioThinkChunk.getThinkChunkFixedScripts());
            }
        }
    }

    /**
     * 合并固定话术帧配置（继承式覆盖各字段）。
     */
    private void mergeFixedScripts(ScriptConfig.ThinkChunkFixedScripts def,
            ScriptConfig.ThinkChunkFixedScripts scenario) {
        // enabled: 布尔开关，继承式覆盖
        if (scenario.getEnabled() != null) {
            def.setEnabled(scenario.getEnabled());
        }

        // charsPerFrame: 资源限制类字段，取min（场景不能放宽框架限制）
        if (scenario.getCharsPerFrame() != null) {
            Integer frameworkValue = def.getCharsPerFrame();
            Integer scenarioValue = scenario.getCharsPerFrame();
            Integer mergedValue = (frameworkValue != null) ? Math.min(frameworkValue, scenarioValue) : scenarioValue;
            def.setCharsPerFrame(mergedValue);
        }

        // tokensBetweenFrames: 资源限制类字段，取min
        if (scenario.getTokensBetweenFrames() != null) {
            Integer frameworkValue = def.getTokensBetweenFrames();
            Integer scenarioValue = scenario.getTokensBetweenFrames();
            Integer mergedValue = (frameworkValue != null) ? Math.min(frameworkValue, scenarioValue) : scenarioValue;
            def.setTokensBetweenFrames(mergedValue);
        }

        // minIntervalMs: 资源限制类字段，取min
        if (scenario.getMinIntervalMs() != null) {
            Integer frameworkValue = def.getMinIntervalMs();
            Integer scenarioValue = scenario.getMinIntervalMs();
            Integer mergedValue = (frameworkValue != null) ? Math.min(frameworkValue, scenarioValue) : scenarioValue;
            def.setMinIntervalMs(mergedValue);
        }

        // defaultScripts: 替代式覆盖（场景有配置时以场景替代框架默认）
        if (scenario.getDefaultScripts() != null) {
            def.setDefaultScripts(scenario.getDefaultScripts());
        }

        // executionScripts: 替代式覆盖
        if (scenario.getExecutionScripts() != null) {
            def.setExecutionScripts(scenario.getExecutionScripts());
        }

        // resumeScripts: 替代式覆盖
        if (scenario.getResumeScripts() != null) {
            def.setResumeScripts(scenario.getResumeScripts());
        }

        // queryPatterns: 追加策略（框架通用模式 + 场景业务关键词）
        if (scenario.getQueryPatterns() != null) {
            if (def.getQueryPatterns() == null) {
                def.setQueryPatterns(scenario.getQueryPatterns());
            } else {
                // 追加合并：框架 queryPatterns + 场景 queryPatterns
                java.util.List<ScriptConfig.ThinkChunkFixedScripts.QueryPattern> mergedPatterns =
                        new java.util.ArrayList<>();
                mergedPatterns.addAll(def.getQueryPatterns()); // 先加框架通用模式
                mergedPatterns.addAll(scenario.getQueryPatterns()); // 再追加场景业务关键词
                def.setQueryPatterns(mergedPatterns);
            }
        }
    }
}