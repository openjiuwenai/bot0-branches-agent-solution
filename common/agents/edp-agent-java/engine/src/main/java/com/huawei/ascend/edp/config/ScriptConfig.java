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

import java.util.List;
import java.util.Map;

/**
 * scriptconfig.yaml 配置模型。
 *
 * <p>定位：定义Agent与人交互的话术配置和执行总结格式</p>
 * <p>作用：</p>
 * <ul>
 *     <li>回答Agent如何与人协同（话术模板、推送模式）</li>
 *     <li>回答Agent何时需要人工参与（中断确认场景）</li>
 *     <li>定义执行结果的总结输出格式</li>
 * </ul>
 *
 * @since 2024-01-01
 */

public class ScriptConfig {
    /** 通用话术配置。 */
    private GeneralScripts generalScripts;

    /** 工具级话术匹配模板（仅影响 todo_start/todo_end，不影响 tool_start/tool_end）。 */
    private Map<String, QueryIntentEntry> queryIntentToolText;

    /** 思维链话术配置。 */
    private ThinkChunkScripts thinkChunkScripts;

    /** ask_user 中断确认话术配置。 */
    private AskUserConfirm askUserConfirm;

    /** Gets the general scripts. */
    public GeneralScripts getGeneralScripts() {
        return generalScripts;
    }

    /** Sets the general scripts. */
    public void setGeneralScripts(GeneralScripts generalScripts) {
        this.generalScripts = generalScripts;
    }

    /** Gets the query intent tool text. */
    public Map<String, QueryIntentEntry> getQueryIntentToolText() {
        return queryIntentToolText;
    }

    /** Sets the query intent tool text. */
    public void setQueryIntentToolText(Map<String, QueryIntentEntry> queryIntentToolText) {
        this.queryIntentToolText = queryIntentToolText;
    }

    /** Gets the think chunk scripts. */
    public ThinkChunkScripts getThinkChunkScripts() {
        return thinkChunkScripts;
    }

    /** Sets the think chunk scripts. */
    public void setThinkChunkScripts(ThinkChunkScripts thinkChunkScripts) {
        this.thinkChunkScripts = thinkChunkScripts;
    }

    /** Gets the ask user confirm. */
    public AskUserConfirm getAskUserConfirm() {
        return askUserConfirm;
    }

    /** Sets the ask user confirm. */
    public void setAskUserConfirm(AskUserConfirm askUserConfirm) {
        this.askUserConfirm = askUserConfirm;
    }

    /**
     * 通用话术配置，用于业务流程状态（工具调用、中断、取消等）。
     */

    public static class GeneralScripts {
        private String toolStart;
        private String toolEnd;
        private String todoStart;
        private String todoEnd;
        private String todolistStart;
        private String todolistEnd;
        private String interruptStart;
        private String requestStart;
        private String planningStart;
        private String taskCancelled;
        private String cancelConfirm;
        private String outOfScope;

        /** Gets the tool start. */
        public String getToolStart() {
            return toolStart;
        }

        /** Sets the tool start. */
        public void setToolStart(String toolStart) {
            this.toolStart = toolStart;
        }

        /** Gets the tool end. */
        public String getToolEnd() {
            return toolEnd;
        }

        /** Sets the tool end. */
        public void setToolEnd(String toolEnd) {
            this.toolEnd = toolEnd;
        }

        /** Gets the todo start. */
        public String getTodoStart() {
            return todoStart;
        }

        /** Sets the todo start. */
        public void setTodoStart(String todoStart) {
            this.todoStart = todoStart;
        }

        /** Gets the todo end. */
        public String getTodoEnd() {
            return todoEnd;
        }

        /** Sets the todo end. */
        public void setTodoEnd(String todoEnd) {
            this.todoEnd = todoEnd;
        }

        /** Gets the todolist start. */
        public String getTodolistStart() {
            return todolistStart;
        }

        /** Sets the todolist start. */
        public void setTodolistStart(String todolistStart) {
            this.todolistStart = todolistStart;
        }

        /** Gets the todolist end. */
        public String getTodolistEnd() {
            return todolistEnd;
        }

        /** Sets the todolist end. */
        public void setTodolistEnd(String todolistEnd) {
            this.todolistEnd = todolistEnd;
        }

        /** Gets the interrupt start. */
        public String getInterruptStart() {
            return interruptStart;
        }

        /** Sets the interrupt start. */
        public void setInterruptStart(String interruptStart) {
            this.interruptStart = interruptStart;
        }

        /** Gets the request start. */
        public String getRequestStart() {
            return requestStart;
        }

        /** Sets the request start. */
        public void setRequestStart(String requestStart) {
            this.requestStart = requestStart;
        }

        /** Gets the planning start. */
        public String getPlanningStart() {
            return planningStart;
        }

        /** Sets the planning start. */
        public void setPlanningStart(String planningStart) {
            this.planningStart = planningStart;
        }

        /** Gets the task cancelled. */
        public String getTaskCancelled() {
            return taskCancelled;
        }

        /** Sets the task cancelled. */
        public void setTaskCancelled(String taskCancelled) {
            this.taskCancelled = taskCancelled;
        }

        /** Gets the cancel confirm. */
        public String getCancelConfirm() {
            return cancelConfirm;
        }

        /** Sets the cancel confirm. */
        public void setCancelConfirm(String cancelConfirm) {
            this.cancelConfirm = cancelConfirm;
        }

        /** Gets the out of scope. */
        public String getOutOfScope() {
            return outOfScope;
        }

        /** Sets the out of scope. */
        public void setOutOfScope(String outOfScope) {
            this.outOfScope = outOfScope;
        }
    }

    /**
     * 思维链话术配置，用于思维链展示（替代真实LLM thinking）。
     */

    public static class ThinkChunkScripts {
        /** 模式选择：real_stream 或 fixed_script。 */
        private String thinkChunkMode;

        /** 固定话术帧配置（仅 thinkChunkMode=fixed_script 时生效）。 */
        private ThinkChunkFixedScripts thinkChunkFixedScripts;

        /** Gets the think chunk mode. */
        public String getThinkChunkMode() {
            return thinkChunkMode;
        }

        /** Sets the think chunk mode. */
        public void setThinkChunkMode(String thinkChunkMode) {
            this.thinkChunkMode = thinkChunkMode;
        }

        /** Gets the think chunk fixed scripts. */
        public ThinkChunkFixedScripts getThinkChunkFixedScripts() {
            return thinkChunkFixedScripts;
        }

        /** Sets the think chunk fixed scripts. */
        public void setThinkChunkFixedScripts(ThinkChunkFixedScripts thinkChunkFixedScripts) {
            this.thinkChunkFixedScripts = thinkChunkFixedScripts;
        }
    }

    /**
     * 固定话术帧配置。
     */

    public static class ThinkChunkFixedScripts {
        /** 是否启用固定话术帧（布尔开关，继承式覆盖）。 */
        private Boolean enabled;

        /** 每帧字符数（资源限制类字段，继承时取min，场景不能放宽框架限制）。 */
        private Integer charsPerFrame;

        /** 帧间token数（资源限制类字段，继承时取min，场景不能放宽框架限制）。 */
        private Integer tokensBetweenFrames;

        /** 最小间隔毫秒数（资源限制类字段，继承时取min，场景不能放宽框架限制）。 */
        private Integer minIntervalMs;

        /** 默认话术列表（替代式覆盖，场景有配置时以场景替代框架默认）。 */
        private List<String> defaultScripts;

        /** 执行阶段话术列表（替代式覆盖，场景有配置时以场景替代框架默认）。 */
        private List<String> executionScripts;

        /** 续轮话术列表（替代式覆盖，场景有配置时以场景替代框架默认）。 */
        private List<String> resumeScripts;

        /** resuming 阶段是否输出固定话术（false=静默，不输出固定话术也不出真实 token）。 */
        private Boolean enableResumeScripts;

        /** 向后兼容：阶段化字段全空时降级使用。 */
        private List<String> scripts;

        /** 按用户 query 关键词匹配的话术组列表（planning 阶段，追加策略：框架通用模式 + 场景业务关键词）。 */
        private List<QueryPattern> queryPatterns;

        /** Gets the enabled. */
        public Boolean getEnabled() {
            return enabled;
        }

        /** Sets the enabled. */
        public void setEnabled(Boolean enabled) {
            this.enabled = enabled;
        }

        /** Gets the chars per frame. */
        public Integer getCharsPerFrame() {
            return charsPerFrame;
        }

        /** Sets the chars per frame. */
        public void setCharsPerFrame(Integer charsPerFrame) {
            this.charsPerFrame = charsPerFrame;
        }

        /** Gets the tokens between frames. */
        public Integer getTokensBetweenFrames() {
            return tokensBetweenFrames;
        }

        /** Sets the tokens between frames. */
        public void setTokensBetweenFrames(Integer tokensBetweenFrames) {
            this.tokensBetweenFrames = tokensBetweenFrames;
        }

        /** Gets the min interval ms. */
        public Integer getMinIntervalMs() {
            return minIntervalMs;
        }

        /** Sets the min interval ms. */
        public void setMinIntervalMs(Integer minIntervalMs) {
            this.minIntervalMs = minIntervalMs;
        }

        /** Gets the default scripts. */
        public List<String> getDefaultScripts() {
            return defaultScripts;
        }

        /** Sets the default scripts. */
        public void setDefaultScripts(List<String> defaultScripts) {
            this.defaultScripts = defaultScripts;
        }

        /** Gets the execution scripts. */
        public List<String> getExecutionScripts() {
            return executionScripts;
        }

        /** Sets the execution scripts. */
        public void setExecutionScripts(List<String> executionScripts) {
            this.executionScripts = executionScripts;
        }

        /** Gets the resume scripts. */
        public List<String> getResumeScripts() {
            return resumeScripts;
        }

        /** Sets the resume scripts. */
        public void setResumeScripts(List<String> resumeScripts) {
            this.resumeScripts = resumeScripts;
        }

        /** Gets the enable resume scripts. */
        public Boolean getEnableResumeScripts() {
            return enableResumeScripts;
        }

        /** Sets the enable resume scripts. */
        public void setEnableResumeScripts(Boolean enableResumeScripts) {
            this.enableResumeScripts = enableResumeScripts;
        }

        /** Gets the scripts. */
        public List<String> getScripts() {
            return scripts;
        }

        /** Sets the scripts. */
        public void setScripts(List<String> scripts) {
            this.scripts = scripts;
        }

        /** Gets the query patterns. */
        public List<QueryPattern> getQueryPatterns() {
            return queryPatterns;
        }

        /** Sets the query patterns. */
        public void setQueryPatterns(List<QueryPattern> queryPatterns) {
            this.queryPatterns = queryPatterns;
        }

        /**
         * 按关键词匹配的话术组。planning 阶段遍历列表，首个命中的关键词组即生效。
         */

        public static class QueryPattern {
            private List<String> keywords;
            private List<String> scripts;

            /** Gets the keywords. */
            public List<String> getKeywords() {
                return keywords;
            }

            /** Sets the keywords. */
            public void setKeywords(List<String> keywords) {
                this.keywords = keywords;
            }

            /** Gets the scripts. */
            public List<String> getScripts() {
                return scripts;
            }

            /** Sets the scripts. */
            public void setScripts(List<String> scripts) {
                this.scripts = scripts;
            }
        }
    }

    /**
     * ask_user 中断确认话术配置。
     *
     * <p>消费方：AskUserTemplateRail（spike 阶段，当前未读取 YAML，预留建模）。</p>
     */

    public static class AskUserConfirm {
        /** 购买确认话术模板，支持 {product_name}、{amount} 等占位符。 */
        private String purchaseConfirm;

        /** 取消确认话术模板。 */
        private String cancelConfirm;

        /** Gets the purchase confirm. */
        public String getPurchaseConfirm() {
            return purchaseConfirm;
        }

        /** Sets the purchase confirm. */
        public void setPurchaseConfirm(String purchaseConfirm) {
            this.purchaseConfirm = purchaseConfirm;
        }

        /** Gets the cancel confirm. */
        public String getCancelConfirm() {
            return cancelConfirm;
        }

        /** Sets the cancel confirm. */
        public void setCancelConfirm(String cancelConfirm) {
            this.cancelConfirm = cancelConfirm;
        }
    }

    /**
     * 工具级话术匹配条目（按 query_intent 精确匹配）。
     *
     * <p>仅影响 todo_start/todo_end，不影响 tool_start/tool_end（E-04 澄清）。
     * 对齐 Python QUERY_INTENT_TO_TODO_TEXT。</p>
     */

    public static class QueryIntentEntry {
        private String toolStart;
        private String toolEnd;

        /** Gets the tool start. */
        public String getToolStart() {
            return toolStart;
        }

        /** Sets the tool start. */
        public void setToolStart(String toolStart) {
            this.toolStart = toolStart;
        }

        /** Gets the tool end. */
        public String getToolEnd() {
            return toolEnd;
        }

        /** Sets the tool end. */
        public void setToolEnd(String toolEnd) {
            this.toolEnd = toolEnd;
        }
    }
}
