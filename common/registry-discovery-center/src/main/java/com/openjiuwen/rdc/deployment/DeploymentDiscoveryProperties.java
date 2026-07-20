/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.rdc.deployment;

import com.openjiuwen.rdc.model.FrameworkType;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Configuration for Feat-015 P1 deployment discovery + reconciliation.
 *
 * @since 0.1.0 (2026)
 */
@Component
@ConfigurationProperties(prefix = "rdc.deployment-discovery")
public class DeploymentDiscoveryProperties {
    private boolean enabled = false;
    private Duration reconcileInterval = Duration.ofSeconds(60);
    private Duration drainingGracePeriod = Duration.ofSeconds(30);

    /**
     * Runtime binding defaults when a provider observation has no matching
     * {@code instances[]} entry (dynamic / non-yml fact sources).
     */
    private BindingDefaults bindingDefaults = new BindingDefaults();
    private List<StaticInstanceEntry> instances = new ArrayList<>();

    /**
     * isEnabled.
     *
     * @return result
     * @since 0.1.0
     */
    public boolean isEnabled() {
        return enabled;
    }
    /**
     * setEnabled.
     *
     * @param enabled enabled
     * @since 0.1.0
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
    /**
     * getReconcileInterval.
     *
     * @return result
     * @since 0.1.0
     */
    public Duration getReconcileInterval() {
        return reconcileInterval;
    }
    /**
     * setReconcileInterval.
     *
     * @param reconcileInterval reconcileInterval
     * @since 0.1.0
     */
    public void setReconcileInterval(Duration reconcileInterval) {
        this.reconcileInterval = reconcileInterval;
    }
    /**
     * getDrainingGracePeriod.
     *
     * @return result
     * @since 0.1.0
     */
    public Duration getDrainingGracePeriod() {
        return drainingGracePeriod;
    }
    /**
     * setDrainingGracePeriod.
     *
     * @param drainingGracePeriod drainingGracePeriod
     * @since 0.1.0
     */
    public void setDrainingGracePeriod(Duration drainingGracePeriod) {
        this.drainingGracePeriod = drainingGracePeriod;
    }
    /**
     * getBindingDefaults.
     *
     * @return result
     * @since 0.1.0
     */
    public BindingDefaults getBindingDefaults() {
        return bindingDefaults;
    }
    /**
     * setBindingDefaults.
     *
     * @param bindingDefaults bindingDefaults
     * @since 0.1.0
     */
    public void setBindingDefaults(BindingDefaults bindingDefaults) {
        this.bindingDefaults = bindingDefaults != null ? bindingDefaults : new BindingDefaults();
    }
    /**
     * getInstances.
     *
     * @return result
     * @since 0.1.0
     */
    public List<StaticInstanceEntry> getInstances() {
        return instances;
    }
    /**
     * setInstances.
     *
     * @param instances instances
     * @since 0.1.0
     */
    public void setInstances(List<StaticInstanceEntry> instances) {
        this.instances = instances;
    }
    /**
     * Defaults applied when reconcile sees an instance that is not listed under
     * {@code rdc.deployment-discovery.instances}.
     */
    public static class BindingDefaults {
        private FrameworkType frameworkType = FrameworkType.JIUWEN;
        private String routeKey = "/v1/query";
        private String contractVersion = "1.0.0";
        private String capabilityVersion = "1.0.0";
        private String cardPath = "/.well-known/agent-card.json";
        private Map<String, String> headers = Map.of();
        private int maxConcurrency = 10;
        private int weight = 100;
        private String region;

        /**
         * getFrameworkType.
         *
         * @return result
         * @since 0.1.0
         */
        public FrameworkType getFrameworkType() {
            return frameworkType;
        }
        /**
         * setFrameworkType.
         *
         * @param frameworkType frameworkType
         * @since 0.1.0
         */
        public void setFrameworkType(FrameworkType frameworkType) {
            this.frameworkType = frameworkType;
        }
        /**
         * getRouteKey.
         *
         * @return result
         * @since 0.1.0
         */
        public String getRouteKey() {
            return routeKey;
        }
        /**
         * setRouteKey.
         *
         * @param routeKey routeKey
         * @since 0.1.0
         */
        public void setRouteKey(String routeKey) {
            this.routeKey = routeKey;
        }
        /**
         * getContractVersion.
         *
         * @return result
         * @since 0.1.0
         */
        public String getContractVersion() {
            return contractVersion;
        }
        /**
         * setContractVersion.
         *
         * @param contractVersion contractVersion
         * @since 0.1.0
         */
        public void setContractVersion(String contractVersion) {
            this.contractVersion = contractVersion;
        }
        /**
         * getCapabilityVersion.
         *
         * @return result
         * @since 0.1.0
         */
        public String getCapabilityVersion() {
            return capabilityVersion;
        }
        /**
         * setCapabilityVersion.
         *
         * @param capabilityVersion capabilityVersion
         * @since 0.1.0
         */
        public void setCapabilityVersion(String capabilityVersion) {
            this.capabilityVersion = capabilityVersion;
        }
        /**
         * getCardPath.
         *
         * @return result
         * @since 0.1.0
         */
        public String getCardPath() {
            return cardPath;
        }
        /**
         * setCardPath.
         *
         * @param cardPath cardPath
         * @since 0.1.0
         */
        public void setCardPath(String cardPath) {
            this.cardPath = cardPath;
        }
        public Map<String, String> getHeaders() {
            return headers;
        }

        /**
         * setHeaders.
         *
         * @param headers headers
         * @since 0.1.0
         */
        public void setHeaders(Map<String, String> headers) {
            this.headers = headers != null ? headers : Map.of();
        }
        /**
         * getMaxConcurrency.
         *
         * @return result
         * @since 0.1.0
         */
        public int getMaxConcurrency() {
            return maxConcurrency;
        }
        /**
         * setMaxConcurrency.
         *
         * @param maxConcurrency maxConcurrency
         * @since 0.1.0
         */
        public void setMaxConcurrency(int maxConcurrency) {
            this.maxConcurrency = maxConcurrency;
        }
        /**
         * getWeight.
         *
         * @return result
         * @since 0.1.0
         */
        public int getWeight() {
            return weight;
        }
        /**
         * setWeight.
         *
         * @param weight weight
         * @since 0.1.0
         */
        public void setWeight(int weight) {
            this.weight = weight;
        }
        /**
         * getRegion.
         *
         * @return result
         * @since 0.1.0
         */
        public String getRegion() {
            return region;
        }
        /**
         * setRegion.
         *
         * @param region region
         * @since 0.1.0
         */
        public void setRegion(String region) {
            this.region = region;
        }
    }

    public static class StaticInstanceEntry {
        private String tenantId;
        private String serviceId;
        private String instanceId;
        private String baseUrl;
        private String deploymentVersion = "1.0.0";
        private String readiness = "READY";
        private FrameworkType frameworkType;
        private String routeKey = "/v1/query";
        private String contractVersion = "1.0.0";
        private String capabilityVersion = "1.0.0";
        private String cardPath = "/.well-known/agent-card.json";
        private Map<String, String> headers;
        private Integer maxConcurrency;
        private Integer weight;
        private String region;

        /**
         * getTenantId.
         *
         * @return result
         * @since 0.1.0
         */
        public String getTenantId() {
            return tenantId;
        }
        /**
         * setTenantId.
         *
         * @param tenantId tenantId
         * @since 0.1.0
         */
        public void setTenantId(String tenantId) {
            this.tenantId = tenantId;
        }
        /**
         * getServiceId.
         *
         * @return result
         * @since 0.1.0
         */
        public String getServiceId() {
            return serviceId;
        }
        /**
         * setServiceId.
         *
         * @param serviceId serviceId
         * @since 0.1.0
         */
        public void setServiceId(String serviceId) {
            this.serviceId = serviceId;
        }
        /**
         * getInstanceId.
         *
         * @return result
         * @since 0.1.0
         */
        public String getInstanceId() {
            return instanceId;
        }
        /**
         * setInstanceId.
         *
         * @param instanceId instanceId
         * @since 0.1.0
         */
        public void setInstanceId(String instanceId) {
            this.instanceId = instanceId;
        }
        /**
         * getBaseUrl.
         *
         * @return result
         * @since 0.1.0
         */
        public String getBaseUrl() {
            return baseUrl;
        }
        /**
         * setBaseUrl.
         *
         * @param baseUrl baseUrl
         * @since 0.1.0
         */
        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }
        /**
         * getDeploymentVersion.
         *
         * @return result
         * @since 0.1.0
         */
        public String getDeploymentVersion() {
            return deploymentVersion;
        }
        /**
         * setDeploymentVersion.
         *
         * @param deploymentVersion deploymentVersion
         * @since 0.1.0
         */
        public void setDeploymentVersion(String deploymentVersion) {
            this.deploymentVersion = deploymentVersion;
        }
        /**
         * getReadiness.
         *
         * @return result
         * @since 0.1.0
         */
        public String getReadiness() {
            return readiness;
        }
        /**
         * setReadiness.
         *
         * @param readiness readiness
         * @since 0.1.0
         */
        public void setReadiness(String readiness) {
            this.readiness = readiness;
        }
        /**
         * getFrameworkType.
         *
         * @return result
         * @since 0.1.0
         */
        public FrameworkType getFrameworkType() {
            return frameworkType;
        }
        /**
         * setFrameworkType.
         *
         * @param frameworkType frameworkType
         * @since 0.1.0
         */
        public void setFrameworkType(FrameworkType frameworkType) {
            this.frameworkType = frameworkType;
        }
        /**
         * getRouteKey.
         *
         * @return result
         * @since 0.1.0
         */
        public String getRouteKey() {
            return routeKey;
        }
        /**
         * setRouteKey.
         *
         * @param routeKey routeKey
         * @since 0.1.0
         */
        public void setRouteKey(String routeKey) {
            this.routeKey = routeKey;
        }
        /**
         * getContractVersion.
         *
         * @return result
         * @since 0.1.0
         */
        public String getContractVersion() {
            return contractVersion;
        }
        /**
         * setContractVersion.
         *
         * @param contractVersion contractVersion
         * @since 0.1.0
         */
        public void setContractVersion(String contractVersion) {
            this.contractVersion = contractVersion;
        }
        /**
         * getCapabilityVersion.
         *
         * @return result
         * @since 0.1.0
         */
        public String getCapabilityVersion() {
            return capabilityVersion;
        }
        /**
         * setCapabilityVersion.
         *
         * @param capabilityVersion capabilityVersion
         * @since 0.1.0
         */
        public void setCapabilityVersion(String capabilityVersion) {
            this.capabilityVersion = capabilityVersion;
        }
        /**
         * getCardPath.
         *
         * @return result
         * @since 0.1.0
         */
        public String getCardPath() {
            return cardPath;
        }
        /**
         * setCardPath.
         *
         * @param cardPath cardPath
         * @since 0.1.0
         */
        public void setCardPath(String cardPath) {
            this.cardPath = cardPath;
        }
        public Map<String, String> getHeaders() {
            return headers;
        }

        /**
         * setHeaders.
         *
         * @param headers headers
         * @since 0.1.0
         */
        public void setHeaders(Map<String, String> headers) {
            this.headers = headers;
        }
        /**
         * getMaxConcurrency.
         *
         * @return result
         * @since 0.1.0
         */
        public Integer getMaxConcurrency() {
            return maxConcurrency;
        }
        /**
         * setMaxConcurrency.
         *
         * @param maxConcurrency maxConcurrency
         * @since 0.1.0
         */
        public void setMaxConcurrency(Integer maxConcurrency) {
            this.maxConcurrency = maxConcurrency;
        }
        /**
         * getWeight.
         *
         * @return result
         * @since 0.1.0
         */
        public Integer getWeight() {
            return weight;
        }
        /**
         * setWeight.
         *
         * @param weight weight
         * @since 0.1.0
         */
        public void setWeight(Integer weight) {
            this.weight = weight;
        }
        /**
         * getRegion.
         *
         * @return result
         * @since 0.1.0
         */
        public String getRegion() {
            return region;
        }
        /**
         * setRegion.
         *
         * @param region region
         * @since 0.1.0
         */
        public void setRegion(String region) {
            this.region = region;
        }
    }
}
