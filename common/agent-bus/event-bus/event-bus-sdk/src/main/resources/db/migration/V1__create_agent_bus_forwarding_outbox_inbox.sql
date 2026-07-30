-- agent-bus C3 forwarding substrate — outbox / inbox tables (Stage 12, MI12-003).
--
-- Owner: agent-bus (decision §4 / §MI12-003: migration·adapter owned by agent-bus,
-- Spring JDBC). This is agent-bus's own Flyway sequence; if agent-bus later shares
-- a production schema_history with another module, re-version this entry via an ADR.
--
-- The DDL is the executed form of the design draft in
-- architecture/L2-Low-Level-Design/agent-bus/forwarding-persistence.md §7, including
-- the MI9-006 condition-field CHECK constraints that mirror the Java record
-- invariants (MI9-003) at the DB layer, the claim index, and §7.3 RLS. RLS is
-- applied AFTER table creation per §7.3 (build table → ENABLE ROW LEVEL SECURITY
-- → CREATE POLICY); application-layer tenant_id filtering stays the primary path
-- and RLS is the defence-in-depth fallback (Rule R-C.c).

-- ===== agent_bus_forwarding_outbox — sender durable queue =====
CREATE TABLE agent_bus_forwarding_outbox (
    tenant_id          VARCHAR(128)  NOT NULL,
    message_id         VARCHAR(128)  NOT NULL,
    source_service_id  VARCHAR(128)  NOT NULL,
    target_service_id  VARCHAR(128)  NOT NULL,
    route_handle       VARCHAR(512)  NOT NULL,
    payload_ref        VARCHAR(1024),
    status             VARCHAR(32)   NOT NULL,
    attempt_count      INTEGER       NOT NULL DEFAULT 0,
    next_attempt_at    BIGINT,
    created_at         BIGINT        NOT NULL,
    updated_at         BIGINT        NOT NULL,
    last_failure_code  VARCHAR(32),
    lease_owner        VARCHAR(128),
    lease_until        BIGINT,
    CONSTRAINT pk_outbox PRIMARY KEY (tenant_id, message_id),
    CONSTRAINT ck_outbox_status CHECK (
        status IN ('PENDING','DISPATCHING','ACKED','RETRY_SCHEDULED','DLQ','EXPIRED')),
    -- MI9-006: condition-field CHECK constraints mirror the Java record
    -- invariants (MI9-003) at the DB layer, so a buggy adapter cannot persist
    -- an illegal row even if it bypasses the constructor.
    CONSTRAINT ck_outbox_attempt_count CHECK (attempt_count >= 0),
    CONSTRAINT ck_outbox_lease_paired CHECK (
        (lease_owner IS NULL AND lease_until IS NULL)
        OR (lease_owner IS NOT NULL AND lease_until IS NOT NULL)),
    CONSTRAINT ck_outbox_retry_has_next_attempt CHECK (
        status <> 'RETRY_SCHEDULED' OR next_attempt_at IS NOT NULL),
    CONSTRAINT ck_outbox_failure_code CHECK (
        (status = 'ACKED' AND last_failure_code IS NULL)
        OR (status IN ('DLQ','EXPIRED','RETRY_SCHEDULED') AND last_failure_code IS NOT NULL)
        OR (status IN ('PENDING','DISPATCHING'))),
    CONSTRAINT ck_outbox_lease_status CHECK (
        (status IN ('PENDING','ACKED','RETRY_SCHEDULED','DLQ','EXPIRED') AND lease_owner IS NULL)
        OR (status = 'DISPATCHING' AND lease_owner IS NOT NULL))
);

-- claim due: non-terminal + due + not held by a live lease; Postgres SKIP LOCKED
-- (§7.1) lets concurrent dispatchers skip rows a peer already locked.
CREATE INDEX ix_outbox_claim_due
    ON agent_bus_forwarding_outbox (tenant_id, next_attempt_at)
    WHERE status IN ('PENDING', 'RETRY_SCHEDULED');

-- ===== agent_bus_forwarding_inbox — receiver dedup / audit =====
CREATE TABLE agent_bus_forwarding_inbox (
    tenant_id           VARCHAR(128) NOT NULL,
    message_id          VARCHAR(128) NOT NULL,
    consumer_service_id VARCHAR(128) NOT NULL,
    status              VARCHAR(32)  NOT NULL,
    received_at         BIGINT       NOT NULL,
    consumed_at         BIGINT,
    failure_code        VARCHAR(32),
    CONSTRAINT pk_inbox PRIMARY KEY (tenant_id, message_id, consumer_service_id),
    CONSTRAINT ck_inbox_status CHECK (
        status IN ('RECEIVED','DUPLICATE_SUPPRESSED','CONSUMED','REJECTED')),
    -- MI9-006: condition-field CHECK constraints mirror the Java record (MI9-003).
    CONSTRAINT ck_inbox_consumed_at CHECK (
        status <> 'CONSUMED' OR consumed_at IS NOT NULL),
    CONSTRAINT ck_inbox_failure_code CHECK (
        (status IN ('REJECTED','DUPLICATE_SUPPRESSED') AND failure_code IS NOT NULL)
        OR (status IN ('RECEIVED','CONSUMED') AND failure_code IS NULL)),
    CONSTRAINT ck_inbox_dup_code CHECK (
        status <> 'DUPLICATE_SUPPRESSED' OR failure_code = 'duplicate_suppressed')
);

-- ===== Row-Level Security (§7.3) — defence-in-depth tenant isolation =====
-- Applied after table creation. current_setting('app.tenant_id', true) returns
-- NULL when the session has not set the tenant → tenant_id = NULL is never true
-- → the row is invisible (fail-closed). Application-layer WHERE tenant_id = ?
-- remains the primary isolation; this is the DB fallback. RLS does not bind the
-- table owner (Flyway / migrations run as owner and bypass RLS by default).
ALTER TABLE agent_bus_forwarding_outbox ENABLE ROW LEVEL SECURITY;
CREATE POLICY agent_bus_forwarding_outbox_tenant_isolation
    ON agent_bus_forwarding_outbox
    USING (tenant_id = current_setting('app.tenant_id', true));

ALTER TABLE agent_bus_forwarding_inbox ENABLE ROW LEVEL SECURITY;
CREATE POLICY agent_bus_forwarding_inbox_tenant_isolation
    ON agent_bus_forwarding_inbox
    USING (tenant_id = current_setting('app.tenant_id', true));
