/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.versatile.intent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * SpringBoot entry point for the Versatile intent recognition deployment module.
 *
 * <p>Activates one of three deployment profiles via
 * {@code --spring.profiles.active=layer1|layer2|downstream}. The same jar hosts
 * all three layers; ops scale each layer independently by running multiple
 * instances with different profiles.
 *
 * @since 0.1.0
 */
@SpringBootApplication
public class VersatileIntentApplication {
    /**
     * Main entry point.
     *
     * @param args command-line args forwarded to Spring Boot
     */
    public static void main(String[] args) {
        SpringApplication.run(VersatileIntentApplication.class, args);
    }
}
