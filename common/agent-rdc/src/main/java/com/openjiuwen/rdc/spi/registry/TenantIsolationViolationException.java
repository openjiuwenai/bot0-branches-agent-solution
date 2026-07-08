package com.openjiuwen.rdc.spi.registry;

/**
 * Raised when a discovery / resolve caller's tenant context does not match
 * the tenant id encoded in the request or route handle (HD3-003 tenant
 * isolation). The MVP implementation
 * {@code PgMvpDiscoveryServiceImpl} throws this exception BEFORE issuing any
 * query so cross-tenant data never reaches the result set.
 *
 * <p>Authority: ADR-0160 (Stage 4 Registry SPI Runtime Promotion) + HD3-003.
 * The exception is a {@link RuntimeException} so it crosses the SPI boundary
 * without forcing callers to declare checked exceptions; the
 * {@code MvpRegistryController} maps it to HTTP 400
 * {@code tenant_isolation_violation}.
 */
public class TenantIsolationViolationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String requestedTenant;
    private final String currentTenant;

    /**
     * @param requestedTenant tenant id appearing in the request path / route handle
     * @param currentTenant   tenant id bound to the caller's {@link TenantContext}
     */
    public TenantIsolationViolationException(String requestedTenant, String currentTenant) {
        super("tenant_isolation_violation: requested=" + requestedTenant
                + ", current=" + currentTenant);
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
