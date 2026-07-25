/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.customer.agent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 客户自有 Spring Boot 应用入口。
 *
 * <p>EDPAgent 引擎的 AutoConfiguration 通过 scanBasePackages 自动扫描激活：
 * <ul>
 *   <li>com.openjiuwen.service.* — agent-runtime A2A/Query 入口</li>
 *   <li>com.huawei.ascend.edp.* — EDPA 引擎（handler/rails/tools/config）</li>
 *   <li>com.customer.agent.* — 客户自定义代码</li>
 * </ul>
 *
 * <p>客户无需额外配置任何 Bean，引擎自动注册 EdpaExtHandler 为 AgentHandler SPI Bean。
 * 客户只需提供 application.yml 配置和场景目录。
 *
 * @since 2026-01-01
 */

@SpringBootApplication(scanBasePackages = {
    "com.customer.agent",
    "com.huawei.ascend.edp",
    "com.openjiuwen.service"
})
public class CustomerApplication {
    public static void main(String[] args) {
        SpringApplication.run(CustomerApplication.class, args);
    }
}
