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

package com.openjiuwen.edp.config;

import com.openjiuwen.service.adapters.agentcore.ext.external.RemoteA2aToolInstaller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * EDPAgent 远端 A2A 工具注入配置。
 *
 * <p>覆盖框架 AgentCoreExtAutoConfiguration 默认的 RemoteA2aToolInstaller Bean 为 noop，
 * 避免框架自动注入 versatile-agent 工具（只有 remoteInput 参数）与 EDPAgent 保留的
 * call_versatile 工具（含 query_intent 等业务参数）并存导致 LLM 混淆。</p>
 *
 * <p>remote-agents 配置仍然生效：A2AAgentCardDiscovery 会拉取远端 AgentCard 并注册到
 * A2ARemoteAgentCardRegistry，供 A2AEnabledServeOrchestrator + A2ARemoteAgentClient
 * 根据 agentName 查找远端 URL。Versatile 委派由 VersatileDelegateRail 拦截
 * call_versatile 后构造 a2a_delegate 中断触发。</p>
 *
 * @since 2026-07-27
 */

@Configuration
public class EdpaRemoteA2aConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger(EdpaRemoteA2aConfig.class);

    /**
     * 覆盖框架默认的 RemoteA2aToolInstaller 为 noop。
     *
     * <p>框架 AgentCoreExtAutoConfiguration 用 @ConditionalOnMissingBean 声明默认 Bean，
     * 此处提前注册 noop Bean 即可阻止框架默认 Bean 创建。noop installer 的 install() 是空操作，
     * 不会向 agent 注册 RemoteA2aInterruptRail，从而避免 versatile-agent 工具自动注入。</p>
     *
     * @return noop RemoteA2aToolInstaller
     */
    @Bean
    RemoteA2aToolInstaller remoteA2aToolInstaller() {
        LOGGER.info("[EDPA-A2A] Registered noop RemoteA2aToolInstaller, "
                + "versatile delegation handled by VersatileDelegateRail");
        return RemoteA2aToolInstaller.noop();
    }
}
