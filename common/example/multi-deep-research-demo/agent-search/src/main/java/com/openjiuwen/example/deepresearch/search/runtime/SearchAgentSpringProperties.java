/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.deepresearch.search.runtime;

import com.openjiuwen.example.deepresearch.search.SearchAgentProperties;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Spring-bound subclass of the library {@link SearchAgentProperties}. Keeps
 * the library tier Spring-free while letting {@code application.yml} bind via
 * {@code @ConfigurationProperties}.
 *
 * @since 2026-07-06
 */
@ConfigurationProperties(prefix = "openjiuwen.demo.search-agent")
public class SearchAgentSpringProperties extends SearchAgentProperties {
}
