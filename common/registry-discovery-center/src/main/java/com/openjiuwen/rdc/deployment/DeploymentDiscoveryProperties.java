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

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Duration getReconcileInterval() {
        return reconcileInterval;
    }

    public void setReconcileInterval(Duration reconcileInterval) {
        this.reconcileInterval = reconcileInterval;
    }

    public Duration getDrainingGracePeriod() {
        return drainingGracePeriod;
    }

    public void setDrainingGracePeriod(Duration drainingGracePeriod) {
        this.drainingGracePeriod = drainingGracePeriod;
    }

    public BindingDefaults getBindingDefaults() {
        return bindingDefaults;
    }

    public void setBindingDefaults(BindingDefaults bindingDefaults) {
        this.bindingDefaults = bindingDefaults != null ? bindingDefaults : new BindingDefaults();
    }

    public List<StaticInstanceEntry> getInstances() {
        return instances;
    }

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

        public FrameworkType getFrameworkType() {
            return frameworkType;
        }

        public void setFrameworkType(FrameworkType frameworkType) {
            this.frameworkType = frameworkType;
        }

        public String getRouteKey() {
            return routeKey;
        }

        public void setRouteKey(String routeKey) {
            this.routeKey = routeKey;
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
            this.headers = headers != null ? headers : Map.of();
        }

        public int getMaxConcurrency() {
            return maxConcurrency;
        }

        public void setMaxConcurrency(int maxConcurrency) {
            this.maxConcurrency = maxConcurrency;
        }

        public int getWeight() {
            return weight;
        }

        public void setWeight(int weight) {
            this.weight = weight;
        }

        public String getRegion() {
            return region;
        }

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

        public String getTenantId() {
            return tenantId;
        }

        public void setTenantId(String tenantId) {
            this.tenantId = tenantId;
        }

        public String getServiceId() {
            return serviceId;
        }

        public void setServiceId(String serviceId) {
            this.serviceId = serviceId;
        }

        public String getInstanceId() {
            return instanceId;
        }

        public void setInstanceId(String instanceId) {
            this.instanceId = instanceId;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getDeploymentVersion() {
            return deploymentVersion;
        }

        public void setDeploymentVersion(String deploymentVersion) {
            this.deploymentVersion = deploymentVersion;
        }

        public String getReadiness() {
            return readiness;
        }

        public void setReadiness(String readiness) {
            this.readiness = readiness;
        }

        public FrameworkType getFrameworkType() {
            return frameworkType;
        }

        public void setFrameworkType(FrameworkType frameworkType) {
            this.frameworkType = frameworkType;
        }

        public String getRouteKey() {
            return routeKey;
        }

        public void setRouteKey(String routeKey) {
            this.routeKey = routeKey;
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

        public String getRegion() {
            return region;
        }

        public void setRegion(String region) {
            this.region = region;
        }
    }
}
