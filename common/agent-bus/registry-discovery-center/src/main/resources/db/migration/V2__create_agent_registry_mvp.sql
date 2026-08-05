-- agent-bus C4 registry / discovery MVP substrate — agent_registry_mvp table
-- (Stage 4, ADR-0160).
--
-- Owner: agent-bus (decision §3.2.1: migration·adapter owned by agent-bus,
-- Spring JDBC). This is agent-bus's own Flyway sequence alongside the existing
-- V1__create_agent_bus_forwarding_outbox_inbox.sql entry. If agent-bus later
-- shares a production schema_history with another module, re-version this
-- entry via an ADR.
--
-- The DDL is the executed form of the design draft in
-- architecture/L2-Low-Level-Design/agent-bus/registry-discovery-runtime-design.cn.md
-- §3.2, including:
--   * (tenant_id, agent_id) composite PK — HD3-003 tenant_id as mandatory
--     registry-key dimension;
--   * search_tsv GENERATED ALWAYS STORED — full-text vector synthesized from
--     capability_keywords (weight A) + system_profile (weight B) so the index
--     never drifts from the source columns;
--   * GIN index (tenant_id, capability, search_tsv) — Method A/B discovery
--     both filter by tenant + capability first, then rank by tsvector;
--   * partial index on last_heartbeat WHERE status='ONLINE' — lease/TTL
--     scan due window (HD3-004) reads only ONLINE rows;
--   * CHECK on status — ONLINE/DEGRADED/DRAINING/OFFLINE lifecycle values;
--   * Row-Level Security (defence-in-depth tenant isolation, mirroring V1
--     §7.3). Application-layer WHERE tenant_id = ? remains the primary path
--     (Rule R-C.c); RLS is the DB fallback. RLS does not bind the table
--     owner (Flyway / migrations run as owner and bypass RLS by default).

-- ===== agent_registry_mvp — static asset + simple health, one table =====
CREATE TABLE agent_registry_mvp (
    tenant_id            VARCHAR(64)  NOT NULL,
    agent_id             VARCHAR(64)  NOT NULL,
    service_id           VARCHAR(64)  NOT NULL,
    agent_name           VARCHAR(128) NOT NULL,
    agent_type           VARCHAR(32)  NOT NULL,
    capability           VARCHAR(64)  NOT NULL,
    capability_keywords  TEXT,
    system_profile       TEXT         NOT NULL,
    route_key            VARCHAR(64)  NOT NULL,
    contract_version     VARCHAR(16)  NOT NULL,
    capability_version   VARCHAR(16)  NOT NULL,
    endpoint_url         VARCHAR(256) NOT NULL,
    max_concurrency      INT          NOT NULL DEFAULT 10,
    weight               INT          NOT NULL DEFAULT 100,
    region               VARCHAR(32),
    tool_schemas         JSONB,
    -- MVP stand-in for Consul health status
    status               VARCHAR(16)  NOT NULL DEFAULT 'ONLINE',
    last_heartbeat       TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    -- full-text vector (capability_keywords weight A + system_profile weight B)
    search_tsv           TSVECTOR
    GENERATED ALWAYS AS (
        setweight(to_tsvector('simple', coalesce(capability_keywords, '')), 'A') ||
        setweight(to_tsvector('simple', coalesce(system_profile,      '')), 'B')
    ) STORED,
    PRIMARY KEY (tenant_id, agent_id),
    CONSTRAINT ck_agent_registry_mvp_status CHECK (
        status IN ('ONLINE', 'DEGRADED', 'DRAINING', 'OFFLINE')),
    CONSTRAINT ck_agent_registry_mvp_weight CHECK (weight >= 0),
    CONSTRAINT ck_agent_registry_mvp_max_concurrency CHECK (max_concurrency >= 0)
);

-- Method A/B discovery: filter tenant + capability, then rank by tsvector.
-- PostgreSQL GIN has no default operator class for varchar, so the search
-- index is split: a GIN index on the tsvector (ranked retrieval) and a BTREE
-- composite index on (tenant_id, capability) for the filter prefix. The
-- planner combines both via BitmapAnd at query time.
CREATE INDEX idx_agent_registry_mvp_search_tsv
    ON agent_registry_mvp
    USING GIN (search_tsv);

CREATE INDEX idx_agent_registry_mvp_tenant_capability
    ON agent_registry_mvp (tenant_id, capability);

-- Health probe lease/TTL scan: ONLINE rows are due for re-probe, and DEGRADED
-- rows are also due so a recovered agent can be restored to ONLINE (PR #389
-- review issue #4 — otherwise DEGRADED is an unrecoverable terminal state).
-- HD3-004 lease_expired path: a row whose last_heartbeat falls outside the
-- visibility window is filtered out by the discovery SQL WHERE clause
-- (status IN ('ONLINE','DEGRADED') AND last_heartbeat >= NOW() - INTERVAL '15 seconds');
-- the scheduler additionally downgrades long-stale ONLINE rows to DEGRADED.
CREATE INDEX ix_agent_registry_mvp_heartbeat_due
    ON agent_registry_mvp (last_heartbeat)
    WHERE status IN ('ONLINE', 'DEGRADED');

-- ===== Row-Level Security (defence-in-depth tenant isolation, mirroring V1) =====
-- Applied after table creation. current_setting('app.tenant_id', true) returns
-- NULL when the session has not set the tenant → tenant_id = NULL is never true
-- → the row is invisible (fail-closed). Application-layer WHERE tenant_id = ?
-- remains the primary isolation (Rule R-C.c); this is the DB fallback. RLS
-- does not bind the table owner (Flyway / migrations run as owner and bypass
-- RLS by default). The JdbcAgentRegistryRepository adapter additionally calls
-- set_config('app.tenant_id', :tenantId, true) inside each transaction so a
-- restricted (non-owner) connection is filtered by tenant.
ALTER TABLE agent_registry_mvp ENABLE ROW LEVEL SECURITY;
CREATE POLICY agent_registry_mvp_tenant_isolation
    ON agent_registry_mvp
    USING (tenant_id = current_setting('app.tenant_id', true));
