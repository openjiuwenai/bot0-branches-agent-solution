package com.openjiuwen.example.versatile.intent.routecache;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.time.Duration;

/**
 * Configuration for {@link CachedVersatileAgentHandler}.
 *
 * <pre>
 *   openjiuwen.service.versatile.route-cache:
 *     enabled: true   # default false
 *     ttl: 30m        # default 30 minutes
 * </pre>
 *
 * @since 2026-07-25
 */
@ConfigurationProperties(prefix = "openjiuwen.service.versatile.route-cache")
public class RouteCacheProperties {
    private boolean enabled = false;
    private Duration ttl = Duration.ofMinutes(30);

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public Duration getTtl() { return ttl; }
    public void setTtl(Duration ttl) { this.ttl = ttl; }
}