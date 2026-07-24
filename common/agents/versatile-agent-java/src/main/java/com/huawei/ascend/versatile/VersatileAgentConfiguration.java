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

package com.huawei.ascend.versatile;

import com.openjiuwen.service.adapters.versatile.agentfw.VersatileAgentHandler;
import com.openjiuwen.service.adapters.versatile.autoconfigure.VersatileProperties;
import com.openjiuwen.service.spec.spi.AgentHandler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the Versatile workflow proxy Agent as an {@link AgentHandler} Bean
 * for agent-runtime-java.
 *
 * <p>Compared to the original VersatileAgentConfiguration:</p>
 * <ul>
 *     <li>Uses {@link AgentHandler} (adapted SPI) instead of AgentRuntimeHandler (original SPI).</li>
 *     <li>Uses {@link VersatileAgentHandler} which encapsulates VersatileHttpClient,
 *         VersatileRequestExtractor, VersatileResponseExtractor internally - no manual
 *         assembly of client/adapter/streamAdapter needed.</li>
 *     <li>{@code resultNodeName} is configured via VersatileProperties (prefix:
 *         {@code openjiuwen.service.versatile}) instead of a separate @Value injection.</li>
 * </ul>
 *
 * @since 2026-01-01
 */

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(VersatileProperties.class)
public class VersatileAgentConfiguration {
    private static final Logger LOGGER = LoggerFactory.getLogger(VersatileAgentConfiguration.class);

    /** A2A 路由使用的 Agent 标识，同时是上游远程工具名的派生来源。
     *  必须与 application.yml 中 openjiuwen.service.agent-id 配置值保持一致。 */
    public static final String AGENT_ID = "versatile-agent";

    @Bean
    /**
     * 注册 Versatile 工作流代理 AgentHandler Bean。
     * 内部封装 VersatileHttpClient/VersatileRequestExtractor/VersatileResponseExtractor。
     * AGENT_ID 常量必须与 application.yml 中 openjiuwen.service.agent-id 保持一致。
     * 对齐 Python app.py L107-134。
     *
     * @param properties Versatile 配置属性（prefix: openjiuwen.service.versatile）
     * @return VersatileAgentHandler 实例
     */

    AgentHandler versatileAgentHandler(VersatileProperties properties) {
        // 对齐 Python app.py L107-116: VersatileProxy 初始化 + headers template keys 确认
        LOGGER.info(
                "[VersatileAdapter] Registering VersatileAgentHandler bean: AGENT_ID={}, "
                        + "urlTemplate={}, resultNodeName={}",
                AGENT_ID, properties.getUrlTemplate(), properties.getResultNodeName());
        return new VersatileAgentHandler(properties);
    }
}
