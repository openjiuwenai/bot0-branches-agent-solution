/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.rdc.registry.runtime.pull;

import com.openjiuwen.rdc.model.FrameworkType;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Configuration for pull-based agent registration (REQ-2026-004).
 *
 * <p>Bound to the {@code rdc.pull-registration.*} config tree. Default
 * {@code enabled=false} — pull registration is opt-in. When enabled,
 * {@link PullRegistrationBootstrap} serially pulls each runtime's A2A
 * AgentCard on {@code ApplicationReadyEvent} and upserts it.
 *
 * <p>HTTP client timeouts are code-defaulted (not exposed as config keys)
 * to prevent operator misconfiguration that could block bootstrap:
 * connect 5s, read 10s (OQ-3 H2 resolution).
 *
 * @since 2026-07-10
 */
@Component
@ConfigurationProperties(prefix = "rdc.pull-registration")
public class PullRegistrationProperties {
    /**
     * HTTP connect timeout for the pull GET. Code-defaulted (OQ-3 H2).
     * Not exposed as a config key to prevent operator misconfiguration.
     */
    public static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);

    /**
     * HTTP read timeout for the pull GET. Code-defaulted (OQ-3 H2).
     * Not exposed as a config key to prevent operator misconfiguration.
     */
    public static final Duration READ_TIMEOUT = Duration.ofSeconds(10);

    /**
     * Default {@code maxConcurrency} applied by
     * {@link PullRegistrationBootstrap#buildEntry} when the operator omits it.
     * Matches the DB-level DEFAULT on {@code agent_registry_mvp.max_concurrency}
     * and the push-path convention documented in the README.
     */
    public static final int DEFAULT_MAX_CONCURRENCY = 10;

    /**
     * Default {@code weight} applied by
     * {@link PullRegistrationBootstrap#buildEntry} when the operator omits it.
     * Matches the DB-level DEFAULT on {@code agent_registry_mvp.weight}.
     */
    public static final int DEFAULT_WEIGHT = 100;

    /**
     * Master switch. Default {@code false} — pull registration is opt-in.
     * When {@code false}, {@link PullRegistrationBootstrap} no-ops on
     * {@code ApplicationReadyEvent}.
     */
    private boolean isEnabled = false;

    /**
     * Runtime list. Each entry produces one {@code upsert} call on
     * {@code ApplicationReadyEvent}. Empty list is valid (no-op when
     * enabled=true with no runtimes).
     */
    private List<RuntimeEntry> runtimes = new ArrayList<>();

    public boolean isEnabled() {
        return isEnabled;
    }

    public void setEnabled(boolean isEnabled) {
        this.isEnabled = isEnabled;
    }

    public List<RuntimeEntry> getRuntimes() {
        return runtimes;
    }

    public void setRuntimes(List<RuntimeEntry> runtimes) {
        this.runtimes = runtimes;
    }

    /**
     * One runtime target for pull-based registration.
     *
     * <p>Required fields: {@code baseUrl}, {@code tenantId}, {@code agentId},
     * {@code frameworkType}. Optional fields: {@code cardPath},
     * {@code headers}, {@code routeKey}, {@code region},
     * {@code contractVersion}, {@code capabilityVersion},
     * {@code serviceId} (FEAT-016 — default: derived from {@code baseUrl} host),
     * {@code capabilities} (FEAT-016 — default: empty).
     */
    public static class RuntimeEntry {
        /** Runtime origin URL, e.g. {@code http://localhost:8090}. Also used as endpointUrl. */
        private String baseUrl;

        /** Tenant the pulled entry belongs to. */
        private String tenantId;

        /**
         * Agent ID — registry PK dimension. A2A AgentCard has no agentId
         * field, so the operator pins it per runtime.
         */
        private String agentId;

        /** Framework type pinned by the operator (cannot be derived from A2A card). */
        private FrameworkType frameworkType;

        /** Path to the A2A AgentCard on the runtime. Default {@code /.well-known/agent-card.json}. */
        private String cardPath = "/.well-known/agent-card.json";

        /** HTTP headers to attach to the GET (e.g. {@code Authorization: Bearer ...}). */
        private Map<String, String> headers;

        /** Route key for the runtime's query endpoint. Default {@code /v1/query}. */
        private String routeKey = "/v1/query";

        /** Optional region hint. */
        private String region;

        /** Registry contract version. Default {@code v1}. */
        private String contractVersion = "v1";

        /** Agent capability version. Default {@code v1}. */
        private String capabilityVersion = "v1";

        /**
         * Max concurrency hint for the runtime. Optional — when null,
         * {@link PullRegistrationBootstrap#buildEntry} applies the default
         * ({@link PullRegistrationProperties#DEFAULT_MAX_CONCURRENCY}) to
         * satisfy the NOT NULL constraint on {@code agent_registry_mvp}.
         */
        private Integer maxConcurrency;

        /**
         * Selection weight hint for the runtime. Optional — when null,
         * {@link PullRegistrationBootstrap#buildEntry} applies the default
         * ({@link PullRegistrationProperties#DEFAULT_WEIGHT}).
         */
        private Integer weight;

        /**
         * Optional logical service identifier (FEAT-016). Default: derived
         * from {@code baseUrl} host by {@link ServiceIdCodec#derive(String)}.
         * When the operator pins one, it overrides the derived value so
         * multiple agents / instances can share the same {@code serviceId}
         * as a logical service group.
         */
        private String serviceId;

        /**
         * Optional capability list (FEAT-016). Default: empty. Backed by the
         * {@code capabilities VARCHAR(64)[]} column on
         * {@code agent_registry_mvp} (V6 migration). Lets the pull path
         * populate the same column the push register endpoint populates.
         */
        private List<String> capabilities;

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getTenantId() {
            return tenantId;
        }

        public void setTenantId(String tenantId) {
            this.tenantId = tenantId;
        }

        public String getAgentId() {
            return agentId;
        }

        public void setAgentId(String agentId) {
            this.agentId = agentId;
        }

        public FrameworkType getFrameworkType() {
            return frameworkType;
        }

        public void setFrameworkType(FrameworkType frameworkType) {
            this.frameworkType = frameworkType;
        }

        public String getCardPath() {
            return cardPath;
        }

        public void setCardPath(String cardPath) {
            this.cardPath = cardPath;
        }

        public Map<String, String> getHeaders() {
            return headers;
        }

        public void setHeaders(Map<String, String> headers) {
            this.headers = headers;
        }

        public String getRouteKey() {
            return routeKey;
        }

        public void setRouteKey(String routeKey) {
            this.routeKey = routeKey;
        }

        public String getRegion() {
            return region;
        }

        public void setRegion(String region) {
            this.region = region;
        }

        public String getContractVersion() {
            return contractVersion;
        }

        public void setContractVersion(String contractVersion) {
            this.contractVersion = contractVersion;
        }

        public String getCapabilityVersion() {
            return capabilityVersion;
        }

        public void setCapabilityVersion(String capabilityVersion) {
            this.capabilityVersion = capabilityVersion;
        }

        public Integer getMaxConcurrency() {
            return maxConcurrency;
        }

        public void setMaxConcurrency(Integer maxConcurrency) {
            this.maxConcurrency = maxConcurrency;
        }

        public Integer getWeight() {
            return weight;
        }

        public void setWeight(Integer weight) {
            this.weight = weight;
        }

        public String getServiceId() {
            return serviceId;
        }

        public void setServiceId(String serviceId) {
            this.serviceId = serviceId;
        }

        public List<String> getCapabilities() {
            return capabilities;
        }

        public void setCapabilities(List<String> capabilities) {
            this.capabilities = capabilities;
        }
    }
}
