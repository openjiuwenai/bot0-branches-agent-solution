package com.openjiuwen.rdc.model;

/**
 * Categorises the runtime framework that backs a registered agent entry.
 * Replaces the free-text {@code agentType} String field removed in
 * REQ-2026-004 — pull-based registration cannot derive a string agentType
 * from the A2A AgentCard (the A2A standard carries no such concept), so
 * the registry now stores a closed enum that the operator pins per runtime
 * in {@code rdc.pull-registration.runtimes[].frameworkType} (pull mode)
 * or the push caller pins in the {@link AgentRegistryEntry#frameworkType}
 * request field.
 *
 * <p>Authority: REQ-2026-004 (capability global removal + agentType→frameworkType
 * rename). The four values cover the runtime families the registry is
 * aware of today; new families are added by editing this enum (a breaking
 * schema change that bumps the contract version). No {@code UNKNOWN}
 * fallback — pull config / push body must declare a concrete framework.
 *
 * <p>Pure Java — no Spring / JDBC / Jackson imports (ADR-0160 decision 1).
 * Jackson (in {@code registry.runtime.api}) deserialises via enum name by
 * default, no annotation needed (ADR-0160 decision 3/5).
 */
public enum FrameworkType {
    /** Jiuwen agent runtime. */
    JIUWEN,
    /** AgentScope runtime. */
    AGENTSCOPE,
    /** Versatile runtime. */
    VERSATILE,
    /** Generic proxy service (no agent runtime behind the endpoint). */
    PROXY_SERVICE
}
