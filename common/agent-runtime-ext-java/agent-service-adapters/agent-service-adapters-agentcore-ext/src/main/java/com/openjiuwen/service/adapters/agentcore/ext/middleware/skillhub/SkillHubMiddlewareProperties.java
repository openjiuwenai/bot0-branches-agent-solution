/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.ext.middleware.skillhub;

import com.openjiuwen.service.spec.ext.skillhub.SkillHubConfig;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Spring binding for {@link SkillHubConfig} (FEAT-005 §5.2).
 *
 * <p>Extends the pure-POJO contract from {@code agent-service-spec-ext} and adds
 * the {@link ConfigurationProperties} annotation so Spring binds it to the
 * {@code openjiuwen.service.middleware.skillhub} prefix. This mirrors the runtime
 * middleware pattern (e.g. {@code MiddlewareProperties.RedisEndpoint.encryptedPassword}).
 *
 * <p>The encrypted token field is {@code encryptedToken}; it is decrypted at use
 * site by the autoconfiguration via runtime {@code CredentialDecryptor.decrypt}.
 *
 * @since 2026-07-15
 */
@ConfigurationProperties(prefix = "openjiuwen.service.middleware.skillhub")
public class SkillHubMiddlewareProperties extends SkillHubConfig {
}
