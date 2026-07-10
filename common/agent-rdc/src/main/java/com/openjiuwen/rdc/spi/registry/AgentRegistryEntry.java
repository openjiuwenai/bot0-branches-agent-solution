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
 * {@code agentId}, {@code agentName}, {@code frameworkType}, {@code routeKey},
 * {@code contractVersion}, {@code capabilityVersion}, {@code endpointUrl}.
 * Optional fields (selection hint / A2A metadata): {@code maxConcurrency},
 * {@code weight}, {@code region}, {@code a2aAgentCard}.
 *
 * <p>REQ-2026-004 changes (baseline-breaking):
 * <ul>
 *   <li>Removed {@code capability} field — A2A AgentCard carries no such
 *       concept, pull-based registration cannot derive it. Discovery
 *       collapses to {@link AgentDiscoveryService#searchByAgentId} single
 *       value point lookup.</li>
 *   <li>Renamed {@code agentType} (String) → {@code frameworkType}
 *       ({@link FrameworkType} enum) — closed vocabulary replaces free
 *       text; pull config / push body must declare a concrete framework.</li>
 *   <li>{@link #hasRegistryKey()} narrowed to {@code tenantId + agentId}
 *       (was {@code tenantId + agentId + capability}).</li>
 * </ul>
 *
 * <p>REQ-2026-006 changes (baseline-breaking):
 * <ul>
 *   <li>Added {@code serviceId} field — server-derived from
 *       {@code endpointUrl} via {@link ServiceIdCodec#derive(String)} so the
 *       same {@code agentId} can host N runtime instances (horizontal
 *       scaling). The setter is package-private (H2-1 decision, 方案 a) so
 *       HTTP callers cannot forge it; the runtime derivation layer (push
 *       register controller / pull bootstrap) populates it via
 *       {@link ServiceIdCodec#applyTo(AgentRegistryEntry)}.</li>
 *   <li>Registry PK evolves to {@code (tenant_id, agent_id, service_id)} —
 *       {@link #hasRegistryKey()} still checks {@code tenantId + agentId}
 *       (the caller-supplied key pair); {@code serviceId} is server-derived
 *       and not part of the caller-facing registry key.</li>
 *   <li>{@link AgentDiscoveryService#searchByAgentId(String, String)} removed
 *       (single-value lookup cannot represent N instances); replaced by
 *       {@link AgentDiscoveryService#searchInstancesByAgentId(String, String)}
 *       returning {@code List}.</li>
 * </ul>
 *
 * <p>FEAT-016 阶段一 changes (baseline-breaking):
 * <ul>
 *   <li>{@code serviceId} semantics split: was "host-port" (REQ-2026-006),
 *       now "host only" (logical service identifier, caller-overridable). The
 *       setter is now {@code public} so callers may explicitly provide a
 *       {@code serviceId} to override the default host-only derivation in
 *       {@link ServiceIdCodec#derive(String)}.</li>
 *   <li>Added {@code instanceId} field — server-derived host-port from
 *       {@code endpointUrl} via {@link InstanceIdCodec#derive(String)};
 *       distinguishes concrete instances under the same {@code serviceId}.
 *       The setter is package-private so HTTP callers cannot forge it; the
 *       runtime derivation layer populates it via
 *       {@link InstanceIdCodec#applyTo(AgentRegistryEntry)}.</li>
 * </ul>
 *
 * <p>The MVP controller persists the entry verbatim into
 * {@code agent_registry_mvp}; the A2A card is serialized to JSONB in the
 * {@code a2a_agent_card} column. The {@code search_tsv} GENERATED column
 * and {@code capability} column were removed in V4 (REQ-2026-004).
 */
public final class AgentRegistryEntry {

    private String tenantId;
    private String agentId;
    private String serviceId;
    private String instanceId;
    private String agentName;
    private FrameworkType frameworkType;
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

    /**
     * Logical service identifier. Default-derived from {@code endpointUrl}
     * via {@link ServiceIdCodec#derive(String)} (host only) by the runtime
     * derivation layer, but the caller may explicitly provide a
     * {@code serviceId} in the register body to override the default — hence
     * the public setter. Multiple agents / instances can share the same
     * {@code serviceId} to form a logical service group.
     */
    public String getServiceId() {
        return serviceId;
    }

    public void setServiceId(String serviceId) {
        this.serviceId = serviceId;
    }

    /**
     * Server-derived from {@code endpointUrl} via
     * {@link InstanceIdCodec#derive(String)}; populated by
     * {@link InstanceIdCodec#applyTo(AgentRegistryEntry)} at the runtime
     * derivation layer. Package-private setter so HTTP callers cannot forge
     * it — Jackson deserialization in {@code MvpRegistryController} ignores
     * unknown JSON fields via the {@code JsonIgnoreProperties(ignoreUnknown=true)}
     * annotation on the controller side (not on this POJO, so spi.registry
     * stays pure Java).
     */
    public String getInstanceId() {
        return instanceId;
    }

    void setInstanceId(String instanceId) {
        this.instanceId = instanceId;
    }

    public String getAgentName() {
        return agentName;
    }

    public void setAgentName(String agentName) {
        this.agentName = agentName;
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
     * registry-key pair is present before persisting. The registry PK is
     * {@code (tenant_id, agent_id)}; {@code capability} was removed in
     * REQ-2026-004 so the key check narrows to the PK pair.
     */
    public boolean hasRegistryKey() {
        return Objects.nonNull(tenantId) && Objects.nonNull(agentId);
    }
}
