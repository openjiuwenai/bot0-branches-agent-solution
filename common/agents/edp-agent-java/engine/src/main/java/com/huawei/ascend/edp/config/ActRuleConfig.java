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

import java.util.List;
import java.util.Map;

/**
 * actrule.yaml 配置模型。
 *
 * <p>定位：定义Agent的任务执行约束与行为边界</p>
 * <p>作用：</p>
 * <ul>
 *     <li>回答Agent如何执行任务</li>
 *     <li>控制执行过程中的步数限制、子任务数量上限</li>
 *     <li>约束Agent可调用的工具集合</li>
 * </ul>
 *
 * @since 2024-01-01
 */
public class ActRuleConfig {

    /** 限制单层最大子任务数量（资源限制类字段，继承时取min，场景不能放宽框架上限）。 */
    private Integer maxSubtasks;

    /** 限制最大执行步数（资源限制类字段，继承时取min，场景不能放宽框架步数上限）。 */
    private Integer maxSteps;

    /** 允许调用的工具列表（继承式覆盖）。 */
    private List<String> allowedTools;

    /** 是否启用任务循环（从 framework.options.enableTaskLoop 迁移）。 */
    private Boolean enableTaskLoop;

    /** Skill 加载模式（从 skills.mode 迁移）。
     * 可选值：all（列出所有 Skill）、auto_list（动态搜索 Skill）、none（关闭 Skill 系统）。
     */
    private String skillMode;

    /** 单个工具调用次数上限（逐key合并，key为工具名，value为上限值，场景只能设更小值不能放宽框架限制）。 */
    private Map<String, Integer> toolLimits;

    /** Gets the max subtasks. */
    public Integer getMaxSubtasks() {
        return maxSubtasks;
    }
    /** Sets the max subtasks. */
    public void setMaxSubtasks(Integer maxSubtasks) {
        this.maxSubtasks = maxSubtasks;
    }

    /** Gets the max steps. */
    public Integer getMaxSteps() {
        return maxSteps;
    }
    /** Sets the max steps. */
    public void setMaxSteps(Integer maxSteps) {
        this.maxSteps = maxSteps;
    }

    /** Gets the allowed tools. */
    public List<String> getAllowedTools() {
        return allowedTools;
    }
    /** Sets the allowed tools. */
    public void setAllowedTools(List<String> allowedTools) {
        this.allowedTools = allowedTools;
    }

    /** Gets the enable task loop. */
    public Boolean getEnableTaskLoop() {
        return enableTaskLoop;
    }
    /** Sets the enable task loop. */
    public void setEnableTaskLoop(Boolean enableTaskLoop) {
        this.enableTaskLoop = enableTaskLoop;
    }

    /** Gets the skill mode. */
    public String getSkillMode() {
        return skillMode;
    }
    /** Sets the skill mode. */
    public void setSkillMode(String skillMode) {
        this.skillMode = skillMode;
    }

    /** Gets the tool limits. */
    public Map<String, Integer> getToolLimits() {
        return toolLimits;
    }
    /** Sets the tool limits. */
    public void setToolLimits(Map<String, Integer> toolLimits) {
        this.toolLimits = toolLimits;
    }

    // ========== todolist 字段（替代式覆盖，框架默认无值） ==========

    /** 任务定义列表（场景级替代式覆盖，框架默认无值）。 */
    private List<TodolistEntry> todolistEntries;

    /** 动态路径规则列表。 */
    private List<TodolistPath> todolistDynamicPaths;

    /** Gets the todolist entries. */
    public List<TodolistEntry> getTodolistEntries() {
        return todolistEntries;
    }
    /** Sets the todolist entries. */
    public void setTodolistEntries(List<TodolistEntry> entries) {
        this.todolistEntries = entries;
    }

    /** Gets the todolist dynamic paths. */
    public List<TodolistPath> getTodolistDynamicPaths() {
        return todolistDynamicPaths;
    }
    /** Sets the todolist dynamic paths. */
    public void setTodolistDynamicPaths(List<TodolistPath> paths) {
        this.todolistDynamicPaths = paths;
    }

    /**
     * 任务定义（catalog_id 为内部主键）。
     */
    public static class TodolistEntry {
        private String catalogId;
        private String content;
        private String description;
        private List<String> dependsOn;
        private String skill;

        /** Gets the catalog id. */
        public String getCatalogId() {
            return catalogId;
        }
        /** Sets the catalog id. */
        public void setCatalogId(String catalogId) {
            this.catalogId = catalogId;
        }
        /** Gets the content. */
        public String getContent() {
            return content;
        }
        /** Sets the content. */
        public void setContent(String content) {
            this.content = content;
        }
        /** Gets the description. */
        public String getDescription() {
            return description;
        }
        /** Sets the description. */
        public void setDescription(String description) {
            this.description = description;
        }
        /** Gets the depends on. */
        public List<String> getDependsOn() {
            return dependsOn;
        }
        /** Sets the depends on. */
        public void setDependsOn(List<String> dependsOn) {
            this.dependsOn = dependsOn;
        }
        /** Gets the skill. */
        public String getSkill() {
            return skill;
        }
        /** Sets the skill. */
        public void setSkill(String skill) {
            this.skill = skill;
        }
    }

    /**
     * 动态路径规则（LLM 根据业务结果自主判断是否切换）。
     */
    public static class TodolistPath {
        private String pathId;
        private String description;
        private String trigger;
        private List<String> skipSteps;
        private String redirect;

        /** Gets the path id. */
        public String getPathId() {
            return pathId;
        }
        /** Sets the path id. */
        public void setPathId(String pathId) {
            this.pathId = pathId;
        }
        /** Gets the description. */
        public String getDescription() {
            return description;
        }
        /** Sets the description. */
        public void setDescription(String description) {
            this.description = description;
        }
        /** Gets the trigger. */
        public String getTrigger() {
            return trigger;
        }
        /** Sets the trigger. */
        public void setTrigger(String trigger) {
            this.trigger = trigger;
        }
        /** Gets the skip steps. */
        public List<String> getSkipSteps() {
            return skipSteps;
        }
        /** Sets the skip steps. */
        public void setSkipSteps(List<String> skipSteps) {
            this.skipSteps = skipSteps;
        }
        /** Gets the redirect. */
        public String getRedirect() {
            return redirect;
        }
        /** Sets the redirect. */
        public void setRedirect(String redirect) {
            this.redirect = redirect;
        }
    }
}