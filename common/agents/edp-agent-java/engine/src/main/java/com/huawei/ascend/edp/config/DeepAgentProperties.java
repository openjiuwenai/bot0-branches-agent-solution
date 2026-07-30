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

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * DeepAgent 顶层配置属性。
 *
 * <p>绑定 application.yml 中 {@code deep-agent.*} 顶层的 backend 和 model 配置，
 * 与 DeepAgent Spring Boot Starter 标准一致。</p>
 *
 * <p>配置映射：</p>
 * <pre>
 * application.yml                    -> DeepAgentProperties
 * ─────────────────────────────────────────────────────
 * deep-agent.backend.*               -> backend  (EdpaSpringBootConfig.BackendConfig)
 * deep-agent.model.*                 -> model    (EdpaSpringBootConfig.ModelConfig)
 * </pre>
 *
 * @since 2024-01-01
 */
@ConfigurationProperties(prefix = "deep-agent")
public class DeepAgentProperties {

    /**
     * 模型后端连接配置（服务端连接参数）。
     */
    private EdpaSpringBootConfig.BackendConfig backend;

    /**
     * 模型参数配置（模型名称、思维链等）。
     */
    private EdpaSpringBootConfig.ModelConfig model;

    /**
     * Gets the backend.
     *
     * @return the result
     */
    public EdpaSpringBootConfig.BackendConfig getBackend() {
        return backend;
    }

    /**
     * Sets the backend.
     *
     * @param backend the backend value
     */
    public void setBackend(EdpaSpringBootConfig.BackendConfig backend) {
        this.backend = backend;
    }

    /**
     * Gets the model.
     *
     * @return the result
     */
    public EdpaSpringBootConfig.ModelConfig getModel() {
        return model;
    }

    /**
     * Sets the model.
     *
     * @param model the model value
     */
    public void setModel(EdpaSpringBootConfig.ModelConfig model) {
        this.model = model;
    }
}
