package com.openjiuwen.example.versatile.intent.directchain;

import com.openjiuwen.example.versatile.intent.VersatileIntentAutoConfiguration;
import com.openjiuwen.example.versatile.intent.a2a.A2AGatewayCardResolver;
import com.openjiuwen.service.adapters.versatile.autoconfigure.VersatileProperties;
import com.openjiuwen.service.spec.spi.AgentHandler;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@AutoConfiguration(before = VersatileIntentAutoConfiguration.class)
@ConditionalOnProperty(prefix = "openjiuwen.example.direct-chain", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(DirectChainProperties.class)
public class DirectChainAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(AgentHandler.class)
    @ConditionalOnProperty(prefix = "openjiuwen.example.direct-chain", name = "raw-passthrough",
            havingValue = "false", matchIfMissing = true)
    public AgentHandler directChainVersatileAgentHandler(VersatileProperties versatileProps,
            DirectChainProperties props, A2AGatewayCardResolver gatewayResolver) {
        return new DirectChainVersatileAgentHandler(versatileProps, props, gatewayResolver);
    }

    @Bean
    @ConditionalOnMissingBean(AgentHandler.class)
    @ConditionalOnProperty(prefix = "openjiuwen.example.direct-chain", name = "raw-passthrough",
            havingValue = "true")
    public AgentHandler rawVersatilePassthroughHandler(VersatileProperties versatileProps) {
        return new RawVersatilePassthroughHandler(versatileProps);
    }
}