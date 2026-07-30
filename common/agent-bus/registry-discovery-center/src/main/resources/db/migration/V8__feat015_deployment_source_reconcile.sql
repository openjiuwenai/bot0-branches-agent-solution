-- V8: Feat-015 P1 — deployment source tracking + route target + draining grace

ALTER TABLE agent_registry_mvp ADD COLUMN IF NOT EXISTS source_id VARCHAR(64);
ALTER TABLE agent_registry_mvp ADD COLUMN IF NOT EXISTS source_revision BIGINT;
ALTER TABLE agent_registry_mvp ADD COLUMN IF NOT EXISTS route_target JSONB;
ALTER TABLE agent_registry_mvp ADD COLUMN IF NOT EXISTS draining_since TIMESTAMPTZ;

UPDATE agent_registry_mvp SET source_id = 'legacy-push' WHERE source_id IS NULL;
UPDATE agent_registry_mvp SET source_revision = 0 WHERE source_revision IS NULL;

ALTER TABLE agent_registry_mvp ALTER COLUMN source_id SET NOT NULL;
ALTER TABLE agent_registry_mvp ALTER COLUMN source_revision SET NOT NULL;

CREATE INDEX IF NOT EXISTS idx_agent_registry_mvp_source_instance
    ON agent_registry_mvp (source_id, tenant_id, instance_id);

CREATE INDEX IF NOT EXISTS idx_agent_registry_mvp_draining
    ON agent_registry_mvp (lifecycle_status, draining_since)
    WHERE lifecycle_status = 'DRAINING';
