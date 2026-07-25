/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.versatile.intent.routecache;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.example.versatile.intent.VersatileIntentAutoConfiguration;
import com.openjiuwen.service.adapters.versatile.agentfw.VersatileAgentHandler;
import com.openjiuwen.service.adapters.versatile.autoconfigure.VersatileProperties;
import com.openjiuwen.service.spec.spi.AgentHandler;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * Verifies {@link RouteCacheAutoConfiguration} wiring:
 * <ul>
 *   <li>{@code route-cache.enabled=true} → {@link CachedVersatileAgentHandler}
 *       claims the {@code AgentHandler} slot before
 *       {@link VersatileIntentAutoConfiguration} can register the bare handler.</li>
 *   <li>{@code route-cache.enabled=false} (or absent) → bare
 *       {@link VersatileAgentHandler} is registered, no {@link RouteCache} bean.</li>
 * </ul>
 *
 * @since 2026-07-25
 */
class RouteCacheAutoConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    RouteCacheAutoConfiguration.class,
                    VersatileIntentAutoConfiguration.class))
            .withUserConfiguration(MockVersatilePropertiesConfig.class);

    /**
     * Empty config that merely registers {@link VersatileProperties} so
     * {@link VersatileIntentAutoConfiguration} can resolve its dependency when
     * loaded outside the full runtime auto-configuration chain.
     */
    @Configuration
    @EnableConfigurationProperties(VersatileProperties.class)
    static class MockVersatilePropertiesConfig {
    }

    @Test
    void enabledRegistersCachedHandlerAndRouteCache() {
        runner.withPropertyValues(
                "openjiuwen.service.versatile.url-template=http://localhost:9090/x",
                "openjiuwen.service.versatile.route-cache.enabled=true",
                "openjiuwen.service.versatile.route-cache.ttl=5m"
        ).run(ctx -> {
            assertThat(ctx).hasSingleBean(AgentHandler.class);
            assertThat(ctx.getBean(AgentHandler.class)).isInstanceOf(CachedVersatileAgentHandler.class);
            assertThat(ctx).hasSingleBean(RouteCache.class);
            assertThat(ctx).hasSingleBean(RouteCacheProperties.class);
        });
    }

    @Test
    void disabledRegistersBareVersatileHandler() {
        runner.withPropertyValues(
                "openjiuwen.service.versatile.url-template=http://localhost:9090/x",
                "openjiuwen.service.versatile.route-cache.enabled=false"
        ).run(ctx -> {
            assertThat(ctx).hasSingleBean(AgentHandler.class);
            assertThat(ctx.getBean(AgentHandler.class)).isInstanceOf(VersatileAgentHandler.class);
            assertThat(ctx).doesNotHaveBean(RouteCache.class);
        });
    }

    @Test
    void defaultDisabledWhenPropertyAbsent() {
        runner.withPropertyValues(
                "openjiuwen.service.versatile.url-template=http://localhost:9090/x"
        ).run(ctx -> {
            assertThat(ctx).hasSingleBean(AgentHandler.class);
            assertThat(ctx.getBean(AgentHandler.class)).isInstanceOf(VersatileAgentHandler.class);
        });
    }
}
