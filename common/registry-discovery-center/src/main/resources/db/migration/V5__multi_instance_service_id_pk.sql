-- V5: REQ-2026-006 baseline-breaking PK rebuild for multi-instance support
--
-- The registry PK evolves from (tenant_id, agent_id) to
-- (tenant_id, agent_id, service_id) so the same agentId can host N runtime
-- instances (horizontal scaling). service_id is server-derived from
-- endpoint_url (host-port) so callers cannot forge or collide it.
--
-- IRREVERSIBLE: DROP CONSTRAINT + ADD PRIMARY KEY is non-recoverable.
-- H4 checkpoint required before prod deploy (H2-5 decision).
--
-- Backfill precision (OQ-2, H2-2 accepted):
--   The SQL regexp extracts the authority substring (host[:port]) from
--   endpoint_url and replaces ':' with '-'. It does NOT fill in scheme-
--   default ports (http→80, https→443) — that logic lives in
--   ServiceIdCodec.derive() (app layer). Rows whose endpoint_url omits the
--   port get service_id = host (no -port suffix); the app derivation would
--   produce host-80/host-443. This mismatch is accepted for MVP scale:
--   the next re-registration (agent restart) overwrites the row with the
--   app-derived service_id, and the health-probe scheduler cleans up any
--   orphaned stale rows within one probe cycle (5s).

-- ===== Step 1: ADD COLUMN service_id (nullable during backfill) =====

ALTER TABLE agent_registry_mvp ADD COLUMN service_id VARCHAR(64);

-- ===== Step 2: BACKFILL service_id from endpoint_url =====
-- Extract host[:port] from '://authority' substring, replace ':' with '-'.
-- endpoint_url formats in the wild:
--   http://10.0.0.1:8080  →  10.0.0.1-8080
--   https://host:443      →  host-443
--   http://host           →  host          (no port — app derives host-80)

UPDATE agent_registry_mvp SET service_id = regexp_replace(
    coalesce(substring(endpoint_url from '://([^/]+)'), endpoint_url),
    ':', '-', 'g'
);

-- ===== Step 3: SET NOT NULL =====

ALTER TABLE agent_registry_mvp ALTER COLUMN service_id SET NOT NULL;

-- ===== Step 4: DROP old PK + ADD new PK =====

ALTER TABLE agent_registry_mvp DROP CONSTRAINT IF EXISTS agent_registry_mvp_pkey;

ALTER TABLE agent_registry_mvp ADD PRIMARY KEY (tenant_id, agent_id, service_id);
