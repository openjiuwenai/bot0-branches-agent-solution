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

/**
 * planrule.yaml 配置模型。
 *
 * <p>定位：定义Agent的角色定位、职责边界和行为约束</p>
 * <p>作用：</p>
 * <ul>
 *     <li>回答Agent是谁（角色定义）</li>
 *     <li>回答Agent负责什么（职责范围）</li>
 *     <li>回答Agent边界是什么（允许/禁止的业务范围）</li>
 *     <li>定义Agent的行为约束规则</li>
 * </ul>
 *
 * @since 2024-01-01
 */

public class PlanRuleConfig {
    /** Agent角色。 */
    private String role;

    /** Agent描述。 */
    private String description;

    /** 场景名称（仅场景级配置，框架默认无值）。仅在 scenario 模式下注入系统提示词。 */
    private String scenarioName;

    /** 场景描述（仅场景级配置，框架默认无值）。仅在 scenario 模式下注入系统提示词。 */
    private String scenarioDescription;

    /** Agent职责边界配置。 */
    private Scope scope;

    /** 补充提示词（行为约束规则）。包含框架内置的 baseProtocol 和场景追加的 additionalPrompt。 */
    private SupplementaryPrompt supplementaryPrompt;

    /** Skill路由规则列表（继承式覆盖）。框架默认无值，仅场景级配置。 */
    private java.util.List<SkillRoute> skillRouting;

    /** Gets the role. */
    public String getRole() {
        return role;
    }

    /** Sets the role. */
    public void setRole(String role) {
        this.role = role;
    }

    /** Gets the description. */
    public String getDescription() {
        return description;
    }

    /** Sets the description. */
    public void setDescription(String description) {
        this.description = description;
    }

    /** Gets the scenario name. */
    public String getScenarioName() {
        return scenarioName;
    }

    /** Sets the scenario name. */
    public void setScenarioName(String scenarioName) {
        this.scenarioName = scenarioName;
    }

    /** Gets the scenario description. */
    public String getScenarioDescription() {
        return scenarioDescription;
    }

    /** Sets the scenario description. */
    public void setScenarioDescription(String scenarioDescription) {
        this.scenarioDescription = scenarioDescription;
    }

    /** Gets the scope. */
    public Scope getScope() {
        return scope;
    }

    /** Sets the scope. */
    public void setScope(Scope scope) {
        this.scope = scope;
    }

    /** Gets the supplementary prompt. */
    public SupplementaryPrompt getSupplementaryPrompt() {
        return supplementaryPrompt;
    }

    /** Sets the supplementary prompt. */
    public void setSupplementaryPrompt(SupplementaryPrompt supplementaryPrompt) {
        this.supplementaryPrompt = supplementaryPrompt;
    }

    /** Gets the skill routing. */
    public java.util.List<SkillRoute> getSkillRouting() {
        return skillRouting;
    }

    /** Sets the skill routing. */
    public void setSkillRouting(java.util.List<SkillRoute> skillRouting) {
        this.skillRouting = skillRouting;
    }

    /**
     * Agent职责边界配置。
     */

    public static class Scope {
        /** 允许的业务范围列表（替代式覆盖）。 */
        private String allowed;

        /** 禁止的业务范围列表（追加拼接：框架denied + 场景denied取并集）。 */
        private String denied;

        /** Gets the allowed. */
        public String getAllowed() {
            return allowed;
        }

        /** Sets the allowed. */
        public void setAllowed(String allowed) {
            this.allowed = allowed;
        }

        /** Gets the denied. */
        public String getDenied() {
            return denied;
        }

        /** Sets the denied. */
        public void setDenied(String denied) {
            this.denied = denied;
        }
    }

    /**
     * 补充提示词配置。
     *
     * <p>包含框架内置的 baseProtocol 和场景追加的 additionalPrompt。</p>
     * <p>合并策略：章节智能合并，同名章节内容追加而非生成独立章节。</p>
     */

    public static class SupplementaryPrompt {
        /** 框架内置协议（不可覆盖）。包含任务规划协议、工具调用规约等核心规则。 */
        private String baseProtocol;

        /** 场景追加提示词（有序拼接）。可为 null 或空字符串。 */
        private String additionalPrompt;

        /** Gets the base protocol. */
        public String getBaseProtocol() {
            return baseProtocol;
        }

        /** Sets the base protocol. */
        public void setBaseProtocol(String baseProtocol) {
            this.baseProtocol = baseProtocol;
        }

        /** Gets the additional prompt. */
        public String getAdditionalPrompt() {
            return additionalPrompt;
        }

        /** Sets the additional prompt. */
        public void setAdditionalPrompt(String additionalPrompt) {
            this.additionalPrompt = additionalPrompt;
        }
    }

    /**
     * Skill路由规则。
     *
     * <p>描述特定触发条件下应调用的目标Skill及其优先级。</p>
     */

    public static class SkillRoute {
        /** 触发条件描述，例如 "用户首次请求推荐理财产品"。 */
        private String trigger;

        /** 目标 Skill 名称，例如 "product_recommend_skill"。 */
        private String skill;

        /** 优先级，数字越小越优先。 */
        private int priority;

        /** Gets the trigger. */
        public String getTrigger() {
            return trigger;
        }

        /** Sets the trigger. */
        public void setTrigger(String trigger) {
            this.trigger = trigger;
        }

        /** Gets the skill. */
        public String getSkill() {
            return skill;
        }

        /** Sets the skill. */
        public void setSkill(String skill) {
            this.skill = skill;
        }

        /** Gets the priority. */
        public int getPriority() {
            return priority;
        }

        /** Sets the priority. */
        public void setPriority(int priority) {
            this.priority = priority;
        }
    }
}
