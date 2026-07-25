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

package com.huawei.ascend.edp.lifecycle;

import com.openjiuwen.core.sysop.sandbox.ContainerManager;
import com.openjiuwen.service.spec.lifecycle.AgentLifecycleContext;
import com.openjiuwen.service.spec.lifecycle.AgentShutdownHook;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 沙箱清理生命周期钩子 -- 替代 EdpaExtHandler 手动释放。
 *
 * <p>享受 AgentLifecycleBootstrap 的 shutdown 排空活跃流保障。
 * ActiveStreamRegistry.awaitDrain() 由框架在本 Hook 之前调用，
 * 确保活跃流排空后再释放沙箱。</p>
 *
 * @since 2024-01-01
 */

@Component
@Order(Ordered.LOWEST_PRECEDENCE)
@ConditionalOnProperty(prefix = "edpa.agent.sandbox", name = "enabled", havingValue = "true")
public class SandboxShutdownHook implements AgentShutdownHook {
    private static final Logger LOGGER = LoggerFactory.getLogger(SandboxShutdownHook.class);

    @Override
    /** On shutdown. */
    public void onShutdown(AgentLifecycleContext context) {
        LOGGER.info("[EDP-SANDBOX] SandboxShutdownHook starting, context available={}", context != null);
        ContainerManager containerMgr = context.getAttribute("containerManager");
        if (containerMgr != null) {
            for (String key : containerMgr.keys()) {
                containerMgr.release(key);
                LOGGER.info("[EDP-SANDBOX] Released sandbox container: {}", key);
            }
        } else {
            LOGGER.info(
                    "[EDP-SANDBOX] no containerManager available, shutdown completed with no containers to release");
        }
        LOGGER.info("[EDP-SANDBOX] SandboxShutdownHook completed");
    }
}
