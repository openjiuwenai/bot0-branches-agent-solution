/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.deepresearch.verify.runtime;

import com.openjiuwen.example.deepresearch.verify.VerifyAgentProperties;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Spring-bound subclass of the library {@link VerifyAgentProperties}. Keeps
 * the library tier Spring-free while letting {@code application.yml} bind via
 * {@code @ConfigurationProperties}.
 *
 * @since 2026-07-14
 */
@ConfigurationProperties(prefix = "openjiuwen.demo.verify-agent")
public class VerifyAgentSpringProperties extends VerifyAgentProperties {
}
