/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.deepresearch.runtime;

import com.openjiuwen.example.deepresearch.DeepResearchProperties;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Spring-bound subclass of the library {@link DeepResearchProperties}.
 *
 * <p>Lets the wrapper layer attach {@code @ConfigurationProperties} while keeping the
 * library tier Spring-free.
 *
 * @since 2026-07-06
 */
@ConfigurationProperties(prefix = "openjiuwen.demo.deep-research")
public class DeepResearchSpringProperties extends DeepResearchProperties {
}
