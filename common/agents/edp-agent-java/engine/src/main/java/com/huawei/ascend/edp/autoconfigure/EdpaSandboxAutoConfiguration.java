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

package com.huawei.ascend.edp.autoconfigure;

import com.huawei.ascend.edp.config.SandboxConfig;
import com.huawei.ascend.edp.lifecycle.SandboxInitHook;
import com.huawei.ascend.edp.lifecycle.SandboxShutdownHook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * 沙箱 Spring Auto-Configuration -- 条件注册沙箱相关 Bean。
 *
 * <p>仅在 {@code edpa.agent.sandbox.enabled=true} 时生效。
 * 注册 SandboxInitHook 和 SandboxShutdownHook 的保底 Bean
 * （它们本身已有 @Component 注解，此处为 Spring Boot Auto-Configuration 规范保底）。</p>
 *
 * @since 2024-01-01
 */

@AutoConfiguration
@EnableConfigurationProperties(SandboxConfig.class)
@ConditionalOnProperty(prefix = "edpa.agent.sandbox", name = "enabled", havingValue = "true")
public class EdpaSandboxAutoConfiguration {
    private static final Logger LOGGER = LoggerFactory.getLogger(EdpaSandboxAutoConfiguration.class);

    /**
     * Sandbox init hook.
     */
    @Bean
    @ConditionalOnMissingBean(SandboxInitHook.class)
    public SandboxInitHook sandboxInitHook() {
        LOGGER.info("[EDP-SANDBOX] SandboxInitHook bean registered, sandbox enabled=true");
        return new SandboxInitHook();
    }

    /**
     * Sandbox shutdown hook.
     */
    @Bean
    @ConditionalOnMissingBean(SandboxShutdownHook.class)
    public SandboxShutdownHook sandboxShutdownHook() {
        LOGGER.info("[EDP-SANDBOX] SandboxShutdownHook bean registered");
        return new SandboxShutdownHook();
    }
}
