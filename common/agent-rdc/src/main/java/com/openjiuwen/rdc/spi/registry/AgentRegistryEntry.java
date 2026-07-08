package com.openjiuwen.rdc.spi.registry;

import java.util.Objects;

import org.a2aproject.sdk.spec.AgentCard;

/**
 * Registry entry — the request body for {@code POST /api/registry/register}.
 *
 * <p>Renamed from {@code AgentCard} in REQ-2026-001 to disambiguate from the
 * A2A standard {@link org.a2aproject.sdk.spec.AgentCard} (served by the
 * runtime at {@code /.well-known/agent-card.json}). This POJO is the
 * <em>registry</em>'s view of a registered agent — routing/contract fields
 * + selection hints + an embedded A2A card as metadata. The A2A SDK card
 * is pure Java (no Jackson annotations) so it can live in the SPI package
 * without breaking ADR-0160 decision 1's "no Jackson annotation crosses
 * the SPI boundary" rule; Jackson (used by {@code MvpRegistryController}
 * in {@code registry.runtime.api}, where Spring imports are allowed)
 * deserializes the JSON request via reflection on this POJO and on the
 * record's canonical constructor.
 *
 * <p>Required fields (registry key + routing index): {@code tenantId},
 * {@code agentId}, {@code agentName}, {@code agentType}, {@code capability},
 * {@code routeKey}, {@code contractVersion}, {@code capabilityVersion},
 * {@code endpointUrl}. Optional fields (selection hint / A2A metadata):
 * {@code maxConcurrency}, {@code weight}, {@code region}, {@code a2aAgentCard}.
 *
 * <p>REQ-2026-001 removed {@code serviceId} (redundant with {@code agentId};
 * registry PK is {@code (tenant_id, agent_id)}), {@code toolSchemas}
 * (overlapped with A2A {@link AgentCard#skills()}), {@code capabilityKeywords}
 * (overlapped with A2A {@code skills[].tags}), and {@code systemProfile}
 * (semantically vague; overlapped with A2A {@code capabilities} +
 * {@code supportedInterfaces}). The A2A standard card now carries the
 * equivalent metadata as {@link #a2aAgentCard}.
 *
 * <p>The MVP controller persists the entry verbatim into
 * {@code agent_registry_mvp}; the A2A card is serialized to JSONB in the
 * {@code a2a_agent_card} column, and the {@code search_tsv} GENERATED column
 * is rebuilt from {@code a2a_agent_card->>'description'} (weight A) +
 * {@code a2a_agent_card->>'name'} (weight B).
 */
public final class AgentRegistryEntry {

    private String tenantId;
    private String agentId;
    private String agentName;
    private String agentType;
    private String capability;
    private String routeKey;
    private String contractVersion;
    private String capabilityVersion;
    private String endpointUrl;
    private Integer maxConcurrency;
    private Integer weight;
    private String region;
    private AgentCard a2aAgentCard;

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

    public AgentCard getA2aAgentCard() {
        return a2aAgentCard;
    }

    public void setA2aAgentCard(AgentCard a2aAgentCard) {
        this.a2aAgentCard = a2aAgentCard;
    }

    /**
     * Convenience for tests / internal callers that want to assert the
     * registry-key trio is present before persisting. The registry PK is
     * {@code (tenant_id, agent_id)}; {@code capability} is the routing
     * index, not part of the PK, but required for discovery.
     */
    public boolean hasRegistryKey() {
        return Objects.nonNull(tenantId) && Objects.nonNull(agentId) && Objects.nonNull(capability);
    }
}
