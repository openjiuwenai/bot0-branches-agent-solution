/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.versatile.orchestration.planagent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * plan-agent entry point. The ReActAgent bean is assembled in
 * {@link PlanAgentConfiguration}; this class only boots Spring.
 *
 * @since 2026-07-08
 */
@SpringBootApplication
public class PlanAgentApplication {
    public static void main(String[] args) {
        SpringApplication.run(PlanAgentApplication.class, args);
    }
}
