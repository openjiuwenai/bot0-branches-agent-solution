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

package com.huawei.ascend.edp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * EDPAgent Spring Boot 启动入口（适配版）。
 *
 * <p>扫描以下包路径：</p>
 * <ul>
 *     <li>{@code com.openjiuwen.service.app.autoconfigure} - agent-runtime A2A/Query endpoints</li>
 *     <li>{@code com.openjiuwen.service.adapters.agentcore.autoconfigure} - agentcore middleware adapters</li>
 *     <li>{@code com.openjiuwen.service.adapters.agentcore.ext.autoconfigure} - AgentCoreExt auto-config + RemoteA2aToolInstaller</li>
 *     <li>{@code com.huawei.ascend.edp} - EDPAgent 自身 Bean</li>
 * </ul>
 *
 * @since 2026-01-01
 */

@SpringBootApplication(scanBasePackages = {"com.openjiuwen.service.app.autoconfigure",
        "com.openjiuwen.service.adapters.agentcore.autoconfigure",
        "com.openjiuwen.service.adapters.agentcore.ext.autoconfigure", "com.huawei.ascend.edp"})
public class EdpApplication {
    private static final Logger LOGGER = LoggerFactory.getLogger(EdpApplication.class);

    /**
     * 应用启动入口。
     * 依赖的环境变量（对应 application.yml 中的 ${} 占位符）：
     * SERVER_PORT, EDP_AGENT_SCENARIO_HOME, EDPA_SANDBOX_ENABLED,
     * EDPA_REDIS_HOST/PORT, EDP_AGENT_MODEL_* 等。
     * 对齐 Python agent.py L820-823: 初始化完成汇总。
     *
     * @param args description
     */

    public static void main(String[] args) {
        LOGGER.info("EDPAgent Application starting...");
        SpringApplication.run(EdpApplication.class, args);
    }
}
