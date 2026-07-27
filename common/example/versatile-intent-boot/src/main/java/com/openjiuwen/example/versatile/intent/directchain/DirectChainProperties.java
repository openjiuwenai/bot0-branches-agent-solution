/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.example.versatile.intent.directchain;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 直链配置。默认走 versatile 直链（经 gateway 隧道透传原始 SSE）；
 * 仅 {@link #a2aForwardAgentCards} 列出的 agentCard 例外走 a2a 转发。
 * gateway URL 不在此配置——复用现有 {@code A2AGatewayCardResolver.resolveJsonRpcUrl}。
 *
 * @since 0.1.0
 */
@ConfigurationProperties(prefix = "openjiuwen.example.direct-chain")
public class DirectChainProperties {
    /** 是否启用直链 handler（关闭时各层照旧用 VersatileAgentHandler）。 */
    private boolean enabled = false;

    /** 走 a2a 转发的 agentCard 例外集合；未列出的 agentCard 默认走直链。 */
    private Set<String> a2aForwardAgentCards = new LinkedHashSet<>();

    /** 直链 SSE 调用超时。 */
    private Duration timeout = Duration.ofSeconds(600);

    /** true=业务终端注册 RawVersatilePassthroughHandler；false=中间层注册 DirectChainVersatileAgentHandler。 */
    private boolean rawPassthrough = false;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Set<String> getA2aForwardAgentCards() {
        return a2aForwardAgentCards;
    }

    public void setA2aForwardAgentCards(Set<String> a2aForwardAgentCards) {
        this.a2aForwardAgentCards = a2aForwardAgentCards;
    }

    public Duration getTimeout() {
        return timeout;
    }

    public void setTimeout(Duration timeout) {
        this.timeout = timeout;
    }

    public boolean isRawPassthrough() {
        return rawPassthrough;
    }

    public void setRawPassthrough(boolean rawPassthrough) {
        this.rawPassthrough = rawPassthrough;
    }

    /**
     * 默认直链：仅 a2aForwardAgentCards 内的 agent 走 a2a 转发。
     *
     * @param agentCard 待判定的 agent 卡片标识
     * @return true 表示该 agent 走直链，false 表示走 a2a 转发
     */
    public boolean shouldDirectChain(String agentCard) {
        return !a2aForwardAgentCards.contains(agentCard);
    }
}
