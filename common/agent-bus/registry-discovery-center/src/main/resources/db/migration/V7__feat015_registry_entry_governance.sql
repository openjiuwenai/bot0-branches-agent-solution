-- V7: Feat-015 — registry entry governance columns (post FEAT-016 V6)
--
-- Adds lifecycle, health, freshness, and lease facts required for structured
-- DiscoverAgentCards. instance_id already exists from V6 (FEAT-016); only
-- governance columns are added here.

ALTER TABLE agent_registry_mvp ADD COLUMN IF NOT EXISTS lifecycle_status VARCHAR(16);
ALTER TABLE agent_registry_mvp ADD COLUMN IF NOT EXISTS effective_health VARCHAR(16);
ALTER TABLE agent_registry_mvp ADD COLUMN IF NOT EXISTS freshness VARCHAR(16);
ALTER TABLE agent_registry_mvp ADD COLUMN IF NOT EXISTS lease_expires_at TIMESTAMPTZ;
ALTER TABLE agent_registry_mvp ADD COLUMN IF NOT EXISTS last_validated_at TIMESTAMPTZ;
ALTER TABLE agent_registry_mvp ADD COLUMN IF NOT EXISTS card_digest VARCHAR(64);

UPDATE agent_registry_mvp SET lifecycle_status = CASE status
    WHEN 'ONLINE' THEN 'ACTIVE'
    WHEN 'DEGRADED' THEN 'ACTIVE'
    WHEN 'DRAINING' THEN 'DRAINING'
    ELSE 'REMOVED'
END WHERE lifecycle_status IS NULL;

UPDATE agent_registry_mvp SET effective_health = CASE status
    WHEN 'ONLINE' THEN 'HEALTHY'
    WHEN 'DEGRADED' THEN 'DEGRADED'
    WHEN 'DRAINING' THEN 'HEALTHY'
    ELSE 'UNHEALTHY'
END WHERE effective_health IS NULL;

UPDATE agent_registry_mvp SET freshness = 'FRESH' WHERE freshness IS NULL;

UPDATE agent_registry_mvp SET last_validated_at = last_heartbeat
WHERE last_validated_at IS NULL;

UPDATE agent_registry_mvp SET lease_expires_at = last_heartbeat + INTERVAL '1 hour'
WHERE lease_expires_at IS NULL;

ALTER TABLE agent_registry_mvp ALTER COLUMN lifecycle_status SET NOT NULL;
ALTER TABLE agent_registry_mvp ALTER COLUMN effective_health SET NOT NULL;
ALTER TABLE agent_registry_mvp ALTER COLUMN freshness SET NOT NULL;

ALTER TABLE agent_registry_mvp ADD CONSTRAINT ck_agent_registry_mvp_lifecycle
    CHECK (lifecycle_status IN ('PENDING', 'ACTIVE', 'DRAINING', 'REMOVED'));

ALTER TABLE agent_registry_mvp ADD CONSTRAINT ck_agent_registry_mvp_effective_health
    CHECK (effective_health IN ('HEALTHY', 'DEGRADED', 'UNHEALTHY', 'UNKNOWN'));

ALTER TABLE agent_registry_mvp ADD CONSTRAINT ck_agent_registry_mvp_freshness
    CHECK (freshness IN ('FRESH', 'STALE_SOURCE', 'STALE_CARD'));

CREATE INDEX IF NOT EXISTS idx_agent_registry_mvp_discover
    ON agent_registry_mvp (tenant_id, agent_id, lifecycle_status, lease_expires_at);

CREATE INDEX IF NOT EXISTS idx_agent_registry_mvp_skills
    ON agent_registry_mvp USING GIN ((a2a_agent_card -> 'skills'));
