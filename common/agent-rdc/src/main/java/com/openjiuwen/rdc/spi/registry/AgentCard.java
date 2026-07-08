package com.openjiuwen.rdc.spi.registry;

import java.util.Objects;

/**
 * Standard Agent Card — the request body for {@code POST /api/registry/register}.
 *
 * <p>Authority: ADR-0160 + ICD-Agent-Registry-Discovery Registry Entry. Field
 * set matches the design doc §2.1 verbatim. This is a plain Java POJO so
 * Jackson (used by the {@code MvpRegistryController} in
 * {@code registry.runtime.api}, where Spring imports are allowed) can
 * deserialize the JSON request via reflection — no Jackson annotation crosses
 * the SPI boundary.
 *
 * <p>Required fields (registry key + routing index): {@code tenantId},
 * {@code agentId}, {@code serviceId}, {@code agentName}, {@code agentType},
 * {@code capability}, {@code systemProfile}, {@code routeKey},
 * {@code contractVersion}, {@code capabilityVersion}, {@code endpointUrl}.
 * Optional fields (selection hint / extension): {@code capabilityKeywords},
 * {@code maxConcurrency}, {@code weight}, {@code region}, {@code toolSchemas}.
 * The MVP controller persists the card verbatim into {@code agent_registry_mvp};
 * phase-2 migration may add {@code embeddingModel} / {@code vectorDim} etc.
 * additively without changing this POJO's existing fields (ADR-0160 §2).
 */
public final class AgentCard {

    private String tenantId;
    private String agentId;
    private String serviceId;
    private String agentName;
    private String agentType;
    private String capability;
    private String capabilityKeywords;
    private String systemProfile;
    private String routeKey;
    private String contractVersion;
    private String capabilityVersion;
    private String endpointUrl;
    private Integer maxConcurrency;
    private Integer weight;
    private String region;
    private String toolSchemas;

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

    public String getServiceId() {
        return serviceId;
    }

    public void setServiceId(String serviceId) {
        this.serviceId = serviceId;
    }

    public String getAgentName() {
        return agentName;
    }

    public void setAgentName(String agentName) {
        this.agentName = agentName;
    }

    public String getAgentType() {
        return agentType;
    }

    public void setAgentType(String agentType) {
        this.agentType = agentType;
    }

    public String getCapability() {
        return capability;
    }

    public void setCapability(String capability) {
        this.capability = capability;
    }

    public String getCapabilityKeywords() {
        return capabilityKeywords;
    }

    public void setCapabilityKeywords(String capabilityKeywords) {
        this.capabilityKeywords = capabilityKeywords;
    }

    public String getSystemProfile() {
        return systemProfile;
    }

    public void setSystemProfile(String systemProfile) {
        this.systemProfile = systemProfile;
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

    public String getEndpointUrl() {
        return endpointUrl;
    }

    public void setEndpointUrl(String endpointUrl) {
        this.endpointUrl = endpointUrl;
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

    public String getToolSchemas() {
        return toolSchemas;
    }

    public void setToolSchemas(String toolSchemas) {
        this.toolSchemas = toolSchemas;
    }

    /**
     * Convenience for tests / internal callers that want to assert the
     * registry-key trio is present before persisting.
     */
    public boolean hasRegistryKey() {
        return Objects.nonNull(tenantId) && Objects.nonNull(agentId) && Objects.nonNull(capability);
    }
}
