/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.deepresearch.runtime.a2a;

import com.openjiuwen.service.app.config.A2AProperties;
import com.openjiuwen.service.app.a2a.catalog.A2ARemoteAgentCardRegistry;
import com.openjiuwen.service.app.controller.a2a.client.A2ARemoteAgentClient;
import com.openjiuwen.service.app.controller.a2a.client.RemoteAgentCaller;
import com.openjiuwen.service.spec.paths.A2AServicePaths;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/**
 * Installs Deep Research's outbound Search callback policy when Push Notification
 * is enabled for this Runtime.
 *
 * @since 2026-08-04
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "openjiuwen.service.a2a.push-notifications", havingValue = "true")
public class DeepResearchOutboundPushConfiguration {
    /**
     * Replaces Runtime's conditional default caller with a callback-aware decorator.
     *
     * @param registry Runtime-managed remote Agent Card registry
     * @param properties Runtime A2A configuration
     * @param callbackToken optional callback Bearer token
     * @return callback-aware remote caller
     */
    @Bean
    RemoteAgentCaller deepResearchOutboundPushRemoteAgentCaller(A2ARemoteAgentCardRegistry registry,
            A2AProperties properties,
            @Value("${openjiuwen.demo.deep-research.callback-auth.bearer-token:}") String callbackToken) {
        String callbackUrl = callbackUrl(properties.getPublicUrl());
        A2ARemoteAgentClient delegate = new A2ARemoteAgentClient(registry,
                properties.getRemoteInvocation().getMaxConcurrency());
        return new DeepResearchOutboundPushRemoteAgentCaller(delegate, callbackUrl, callbackToken);
    }

    static String callbackUrl(String publicUrl) {
        if (publicUrl == null || publicUrl.isBlank()) {
            throw new IllegalStateException("DEEP_RESEARCH_PUBLIC_URL must be configured when "
                    + "DEEP_RESEARCH_PUSH_NOTIFICATIONS=true");
        }
        String baseUrl = publicUrl.trim().replaceAll("/+$", "");
        try {
            URI uri = new URI(baseUrl);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!("http".equals(scheme) || "https".equals(scheme)) || uri.getHost() == null
                    || uri.getQuery() != null || uri.getFragment() != null) {
                throw invalidPublicUrl();
            }
        } catch (URISyntaxException exception) {
            throw invalidPublicUrl();
        }
        return baseUrl + A2AServicePaths.A2A_PUSH_NOTIFICATION_CALLBACK;
    }

    private static IllegalStateException invalidPublicUrl() {
        return new IllegalStateException("DEEP_RESEARCH_PUBLIC_URL must be an absolute HTTP(S) base URL "
                + "without query or fragment when DEEP_RESEARCH_PUSH_NOTIFICATIONS=true");
    }
}
