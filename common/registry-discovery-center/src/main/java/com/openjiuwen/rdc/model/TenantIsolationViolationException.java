package com.openjiuwen.rdc.model;

/**
 * Raised when a discovery / resolve caller's tenant context does not match
 * the tenant id encoded in the request or route handle (0711
 * {@code TENANT_SCOPE_DENIED}).
 *
 * <p>Authority: ADR-0160 + HD3-003. Kept as a distinct type for backward
 * compatibility with existing audit outcome labels.
 */
public class TenantIsolationViolationException extends RegistryFailureException {

    private static final long serialVersionUID = 1L;

    private final String requestedTenant;
    private final String currentTenant;

    public TenantIsolationViolationException(String requestedTenant, String currentTenant) {
        this(requestedTenant, currentTenant, null);
    }

    public TenantIsolationViolationException(String requestedTenant, String currentTenant, String traceId) {
        super(RegistryFailure.of(
                "TENANT_SCOPE_DENIED",
                "tenant scope denied: requested=" + requestedTenant + ", current=" + currentTenant,
                false,
                traceId != null ? traceId : ""));
        this.requestedTenant = requestedTenant;
        this.currentTenant = currentTenant;
    }

    public String requestedTenant() {
        return requestedTenant;
    }

    public String currentTenant() {
        return currentTenant;
    }
}
