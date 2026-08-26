/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.ext.middleware.otel;

import com.openjiuwen.extensions.tracerotel.OtelTracerConfig;
import com.openjiuwen.extensions.tracerotel.OtelTracerSetup;
import com.openjiuwen.service.adapters.agentcore.autoconfigure.AgentCoreAdaptersAutoConfiguration;
import com.openjiuwen.service.adapters.agentcore.ext.middleware.otel.egress.OtelRemoteAgentCallerPostProcessor;
import com.openjiuwen.service.adapters.agentcore.ext.middleware.otel.egress.OtelRemoteClientDecoratorFactory;
import com.openjiuwen.service.adapters.agentcore.ext.middleware.otel.egress.TraceparentResolver;
import com.openjiuwen.service.adapters.agentcore.ext.middleware.trajectory.identity.TraceContextCarrier;
import com.openjiuwen.service.adapters.agentcore.ext.middleware.otel.http.HttpRequestSpanFilter;
import com.openjiuwen.service.adapters.agentcore.external.AgentCoreRemoteClientDecoratorFactory;
import com.openjiuwen.service.adapters.agentcore.external.ExternalSvcAdapterRegistrar;
import com.openjiuwen.service.adapters.agentcore.middleware.MiddlewareAdapterRegistrar;
import com.openjiuwen.service.app.a2a.catalog.A2ARemoteAgentCardRegistry;
import com.openjiuwen.service.app.controller.a2a.client.A2APropagationHeaderRegistry;
import com.openjiuwen.service.spec.spi.AgentHandler;

import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SpanExporter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;

import java.util.HashMap;
import java.util.Map;

/**
 * Assembles OTel trajectory export for the agent runtime. Active only when
 * {@code openjiuwen.service.otel.enabled=true} and the engine's tracerotel extension is on
 * the classpath; otherwise the host behaves exactly as before.
 *
 * @since 2026-08-07
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "openjiuwen.service.otel", name = "enabled", havingValue = "true")
@ConditionalOnClass(name = "com.openjiuwen.extensions.tracerotel.OtelTracerSetup")
@AutoConfigureBefore(AgentCoreAdaptersAutoConfiguration.class)
@EnableConfigurationProperties(OtelTrajectoryProperties.class)
public class OtelAutoConfiguration {
    private static final Logger LOGGER = LoggerFactory.getLogger(OtelAutoConfiguration.class);

    @Bean
    OtelEnvProperties otelEnvProperties(OtelTrajectoryProperties props) {
        Map<String, String> overrides = new HashMap<>();
        putIfPresent(overrides, "endpoint", props.getEndpoint());
        putIfPresent(overrides, "protocol", props.getProtocol());
        putIfPresent(overrides, "service-name", props.getServiceName());
        putIfPresent(overrides, "service-version", props.getServiceVersion());
        putIfPresent(overrides, "service-instance-id", props.getServiceInstanceId());
        if (props.getTimeout() != null) {
            overrides.put("timeout", String.valueOf(props.getTimeout().toMillis()));
        }
        if (props.getSampleRate() != null) {
            overrides.put("sample-rate", String.valueOf(props.getSampleRate()));
        }
        return new OtelEnvProperties(System::getenv, overrides::get, props.getHeaders());
    }

    private static void putIfPresent(Map<String, String> overrides, String key, String value) {
        if (value != null && !value.isBlank()) {
            overrides.put(key, value.trim());
        }
    }

    @Bean
    InitializingBean otelUnsupportedVariableWarning(OtelEnvProperties properties) {
        return () -> {
            for (String name : properties.unsupportedVariables()) {
                LOGGER.warn("otel env {} is set but not supported by this runtime; see DFX-001 config doc", name);
            }
        };
    }

    @Bean
    OtelTracerConfig otelTracerConfig(OtelEnvProperties properties) {
        return properties.toTracerConfig();
    }

    @Bean(destroyMethod = "close")
    OtelProviderHolder otelProviderHolder(OtelTracerConfig config, OtelEnvProperties properties,
                                          ObjectProvider<TraceContextCarrier> carrierProvider) {
        String instanceId = properties.getServiceInstanceId().orElse(null);
        SpanExporter otlpExporter = OtelTracerSetup.createOtlpExporter(config);
        SdkTracerProvider provider = OtelSdkFactory.createProvider(config, otlpExporter, instanceId,
                carrierProvider::getIfAvailable);
        return new OtelProviderHolder(provider);
    }

    @Bean
    HttpContextBridge httpContextBridge() {
        return new HttpContextBridge();
    }

    @Bean
    InitializingBean otelRuntimeSupportActivator(HttpContextBridge bridge) {
        return () -> OtelRuntimeSupport.activate(bridge);
    }

    @Bean
    InitializingBean otelAgentRegistrarActivator(OtelProviderHolder holder, OtelTracerConfig config) {
        return () -> new OtelAgentRegistrar(holder.provider().get(config.getTracerName()),
                config).register();
    }

    @Bean
    @ConditionalOnClass(HttpRequestSpanFilter.class)
    FilterRegistrationBean<HttpRequestSpanFilter> httpRequestSpanFilter(OtelProviderHolder holder,
                                                                        HttpContextBridge bridge,
                                                                        OtelTracerConfig config) {
        FilterRegistrationBean<HttpRequestSpanFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new HttpRequestSpanFilter(holder.provider().get(config.getTracerName()), bridge));
        registration.addUrlPatterns("/a2a", "/a2a/*", "/v1/*");
        registration.setOrder(0);
        return registration;
    }

    @Bean
    @ConditionalOnMissingBean(AgentCoreRemoteClientDecoratorFactory.class)
    AgentCoreRemoteClientDecoratorFactory otelRemoteClientDecoratorFactory(OtelProviderHolder holder,
                                                                            OtelTracerConfig config) {
        return new OtelRemoteClientDecoratorFactory(holder.provider().get(config.getTracerName()));
    }

    @Bean
    InitializingBean otelPropagationHeaderRegistrar() {
        return () -> A2APropagationHeaderRegistry.registerProvider(TraceparentResolver::provideHeaders);
    }

    @Bean
    static OtelRemoteAgentCallerPostProcessor otelRemoteAgentCallerPostProcessor(
            OtelProviderHolder holder, OtelTracerConfig config,
            ObjectProvider<A2ARemoteAgentCardRegistry> registry) {
        return new OtelRemoteAgentCallerPostProcessor(holder.provider().get(config.getTracerName()), registry);
    }

    @Bean
    @ConditionalOnMissingBean(AgentHandler.class)
    @ConditionalOnExpression("'${openjiuwen.service.agent-id:}' != '' "
            + "&& '${openjiuwen.service.handler:agentcore}' == 'agentcore'")
    AgentHandler otelAgentHandler(@Value("${openjiuwen.service.agent-id}") String agentId,
                                  @org.springframework.beans.factory.annotation.Autowired(
                                          required = false) MiddlewareAdapterRegistrar middlewareAdapterRegistrar,
                                  ExternalSvcAdapterRegistrar externalSvcAdapterRegistrar) {
        return new OtelJiuwenCoreAgentHandler(agentId, middlewareAdapterRegistrar, externalSvcAdapterRegistrar);
    }

    /**
     * Holds the provider so shutdown flushes the batch queue before closing.
     */
    public static final class OtelProviderHolder implements AutoCloseable {
        private final SdkTracerProvider provider;

        OtelProviderHolder(SdkTracerProvider provider) {
            this.provider = provider;
        }

        /**
         * Returns the assembled tracer provider.
         *
         * @return tracer provider
         */
        public SdkTracerProvider provider() {
            return provider;
        }

        @Override
        public void close() {
            try {
                provider.forceFlush().join(10, java.util.concurrent.TimeUnit.SECONDS);
            } catch (IllegalStateException | IllegalArgumentException | UnsupportedOperationException e) {
                LOGGER.warn("otel provider flush on shutdown failed: {}", e.getClass().getSimpleName());
            } finally {
                provider.close();
                OtelRuntimeSupport.deactivate();
            }
        }
    }
}
