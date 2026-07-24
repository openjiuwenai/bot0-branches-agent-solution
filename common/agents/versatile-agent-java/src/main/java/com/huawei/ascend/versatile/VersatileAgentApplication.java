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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Standalone A2A process hosting a single Versatile workflow proxy agent,
 * adapted for agent-runtime-java and agent-solution modules.
 *
 * <p>Scans the following packages:</p>
 * <ul>
 *     <li>{@code com.openjiuwen.service.app.autoconfigure} — agent-runtime A2A/Query endpoints</li>
 *     <li>{@code com.openjiuwen.service.adapters.versatile.autoconfigure} — VersatileProperties auto-config</li>
 *     <li>{@code com.huawei.ascend.versatile} — our own Configuration/Handler beans</li>
 * </ul>
 *
 * @since 2026-01-01
 */

@SpringBootApplication(scanBasePackages = {"com.openjiuwen.service.app.autoconfigure",
        "com.openjiuwen.service.adapters.versatile.autoconfigure", "com.huawei.ascend.versatile"})
public class VersatileAgentApplication {
    private static final Logger LOGGER = LoggerFactory.getLogger(VersatileAgentApplication.class);

    /**
     * 应用启动入口。
     * 依赖的环境变量：
     * VERSATILE_AGENT_PORT(默认8191), VERSATILE_URL, VERSATILE_TIMEOUT, VERSATILE_RESULT_NODE 等。
     * 对齐 Python main.py L13-29。
     *
     * @param args description
     */

    public static void main(String[] args) {
        // 对齐 Python main.py L16-19: 启动配置确认
        LOGGER.info("[VersatileAdapter] Versatile Agent Application starting...");
        SpringApplication.run(VersatileAgentApplication.class, args);
    }
}
