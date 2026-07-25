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

/**
 * edp-config.yaml 专有配置模型。
 *
 * <p>文件作用：</p>
 * <ul>
 *     <li>承载 EDPAgent 专有 YAML 配置的反序列化结果。</li>
 *     <li>向业务工具 Schema、业务 Rails、模型采样参数和话术模板提供配置。</li>
 *     <li>作为 Python EDPAgent 配置迁移到 Java spike 的主要配置承载对象。</li>
 * </ul>
 *
 * <p>对外提供的接口：</p>
 * <ul>
 *     <li>各字段 getter/setter：供 Jackson 反序列化和业务代码读取。</li>
 *     <li>{@link Scope}：业务范围配置。</li>
 *     <li>{@link TodolistStep}：轻量 Todo 步骤定义。</li>
 *     <li>{@link LlmSampling}：模型采样参数。</li>
 *     <li>{@link Memory}：记忆开关配置。</li>
 * </ul>
 *
 * @since 2024-01-01
 *
 */

public class EdpConfig {
    /**
     * 业务范围配置。
     */
    private Scope scope;

    /**
     * 规划步骤文本列表。
     */
    private List<String> planningSteps;

    /**
     * 模型采样参数配置。
     */
    private LlmSampling llmSampling;

    /**
     * Gets the scope.
     *
     * @return the result
     */
    public Scope getScope() {
        return scope;
    }

    /**
     * Sets the scope.
     *
     * @param scope the scope value
     */
    public void setScope(Scope scope) {
        this.scope = scope;
    }

    /**
     * Gets the planning steps.
     *
     * @return the result
     */
    public List<String> getPlanningSteps() {
        return planningSteps;
    }

    /**
     * Sets the planning steps.
     *
     * @param planningSteps the planningSteps value
     */
    public void setPlanningSteps(List<String> planningSteps) {
        this.planningSteps = planningSteps;
    }

    /**
     * Gets the llm sampling.
     *
     * @return the result
     */
    public LlmSampling getLlmSampling() {
        return llmSampling;
    }

    /**
     * Sets the llm sampling.
     *
     * @param llmSampling the llmSampling value
     */
    public void setLlmSampling(LlmSampling llmSampling) {
        this.llmSampling = llmSampling;
    }

    /**
     * 业务范围配置。
     *
     */

    public static class Scope {
        /**
         * 允许处理的业务范围描述。
         */
        private String allowed;

        /**
         * Gets the allowed.
         *
         * @return the result
         */
        public String getAllowed() {
            return allowed;
        }

        /**
         * Sets the allowed.
         *
         * @param allowed the allowed value
         */
        public void setAllowed(String allowed) {
            this.allowed = allowed;
        }
    }

    /**
     * 模型采样参数配置。
     *
     */

    public static class LlmSampling {
        /**
         * 温度参数。
         */
        private double temperature;

        /**
         * top_p 采样参数。
         */
        private double topP;

        /**
         * 最大重试次数。
         */
        private int maxRetries;

        /**
         * Gets the temperature.
         *
         * @return the result
         */
        public double getTemperature() {
            return temperature;
        }

        /**
         * Sets the temperature.
         *
         * @param temperature the temperature value
         */
        public void setTemperature(double temperature) {
            this.temperature = temperature;
        }

        /**
         * Gets the top p.
         *
         * @return the result
         */
        public double getTopP() {
            return topP;
        }

        /**
         * Sets the top p.
         *
         * @param topP the topP value
         */
        public void setTopP(double topP) {
            this.topP = topP;
        }

        /**
         * Gets the max retries.
         *
         * @return the result
         */
        public int getMaxRetries() {
            return maxRetries;
        }

        /**
         * Sets the max retries.
         *
         * @param maxRetries the maxRetries value
         */
        public void setMaxRetries(int maxRetries) {
            this.maxRetries = maxRetries;
        }
    }
}
