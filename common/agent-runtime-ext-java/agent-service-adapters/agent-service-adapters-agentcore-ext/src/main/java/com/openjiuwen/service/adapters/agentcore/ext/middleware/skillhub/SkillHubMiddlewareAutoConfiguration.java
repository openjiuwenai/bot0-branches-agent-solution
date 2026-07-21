/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.ext.middleware.skillhub;

import com.openjiuwen.service.adapters.common.credential.CredentialDecryptor;
import com.openjiuwen.service.adapters.agentcore.ext.middleware.skillhub.openjiuwen.OpenJiuwenSkillHubProvider;
import com.openjiuwen.service.spec.ext.skillhub.SkillHubConfig;
import com.openjiuwen.service.spec.ext.skillhub.spi.SkillHubProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Auto-configuration for the SkillHub middleware chain (FEAT-005 §4.7).
 *
 * <p>Aligns with runtime {@code RedisMiddlewareAutoConfiguration}: uses
 * {@code @ConditionalOnProperty} + {@code @ConditionalOnMissingBean} and injects
 * {@link CredentialDecryptor} to decrypt {@code encryptedToken} before handing
 * the plaintext token to the provider.
 *
 * <p>Only active when {@code openjiuwen.service.middleware.skillhub.enabled=true}
 * AND a {@code SkillHubProvider} bean exists (or the default OpenJiuwen provider
 * is created here). When disabled or no provider, the whole chain is inactive
 * and {@code JiuwenCoreAgentExtHandler} runs without SkillHub.
 *
 * @since 2026-07-15
 */
@AutoConfiguration
@EnableConfigurationProperties(SkillHubMiddlewareProperties.class)
@ConditionalOnProperty(
        prefix = "openjiuwen.service.middleware.skillhub",
        name = "enabled",
        havingValue = "true")
@ConditionalOnClass(name = "com.openjiuwen.core.runner.RunnerConfig")
public class SkillHubMiddlewareAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(SkillHubMiddlewareAutoConfiguration.class);

    /**
     * Default SkillHubProvider: OpenJiuwen implementation talking to
     * {@code https://swarmskills.openjiuwen.com} (or the configured endpoint).
     *
     * <p>Businesses can override by registering their own {@code @Bean SkillHubProvider}.
     */
    @Bean
    @ConditionalOnMissingBean(SkillHubProvider.class)
    public SkillHubProvider skillHubProvider(SkillHubMiddlewareProperties properties,
                                             ObjectProvider<CredentialDecryptor> decryptorProvider) {
        String decryptedToken = decrypt(properties, decryptorProvider);
        validateEndpoint(properties.getEndpoint());
        return new OpenJiuwenSkillHubProvider(
                properties.getEndpoint(),
                decryptedToken,
                properties.getAuthType());
    }

    /**
     * Default SkillHubInstaller.
     */
    @Bean
    @ConditionalOnMissingBean(SkillHubInstaller.class)
    public SkillHubInstaller skillHubInstaller() {
        return new SkillHubInstaller();
    }

    /**
     * SkillHubManager: orchestrates download/verify/register.
     *
     * <p>The constructor starts the provider and triggers the first download.
     */
    @Bean
    @ConditionalOnMissingBean(SkillHubManager.class)
    public SkillHubManager skillHubManager(SkillHubProvider provider,
                                           SkillHubInstaller installer,
                                           SkillHubMiddlewareProperties properties,
                                           ObjectProvider<CredentialDecryptor> decryptorProvider) {
        String decryptedToken = decrypt(properties, decryptorProvider);
        return new SkillHubManager(provider, installer, asConfig(properties), decryptedToken);
    }

    /**
     * Decrypt the encryptedToken using runtime CredentialDecryptor (aligned with redis).
     *
     * <p>Issue #12: the "no decryptor → treat as plaintext" fallback is removed
     * because runtime's {@code CredentialDecryptorAutoConfiguration} always
     * registers a {@code PassthroughCredentialDecryptor} bean. If the bean is
     * genuinely absent (misconfiguration), we return empty token and the provider
     * will operate in anonymous mode — but we never silently treat ciphertext
     * as plaintext, which would encourage deployments to put raw tokens in
     * the {@code encryptedToken} field.
     */
    private static String decrypt(SkillHubMiddlewareProperties properties,
                                  ObjectProvider<CredentialDecryptor> decryptorProvider) {
        String encrypted = properties.getEncryptedToken();
        if (encrypted == null || encrypted.isEmpty()) {
            return "";
        }
        CredentialDecryptor decryptor = decryptorProvider.getIfAvailable();
        if (decryptor == null) {
            // No decryptor bean = misconfiguration. Refuse to ship the encrypted
            // blob as plaintext; treat as anonymous access instead.
            log.warn("SkillHub no CredentialDecryptor bean — encryptedToken will be treated as empty (anonymous access)");
            return "";
        }
        try {
            return decryptor.decrypt(encrypted);
        } catch (RuntimeException ex) {
            log.warn("SkillHub CredentialDecryptor.decrypt failed, reason={}", ex.getMessage());
            return "";
        }
    }

    /**
     * Fail fast when endpoint is blank — otherwise the provider would only blow
     * up later inside {@code download()} with an unfriendly {@code URI.create}.
     * (Issue #13.)
     */
    private static void validateEndpoint(String endpoint) {
        if (endpoint == null || endpoint.isBlank()) {
            throw new IllegalArgumentException(
                    "SkillHub endpoint is not configured — set openjiuwen.service.middleware.skillhub.endpoint");
        }
    }

    /** Treat the Spring-bound properties as the pure POJO contract SkillHubConfig. */
    private static SkillHubConfig asConfig(SkillHubMiddlewareProperties properties) {
        // SkillHubMiddlewareProperties extends SkillHubConfig, so it IS-A SkillHubConfig
        return properties;
    }
}
