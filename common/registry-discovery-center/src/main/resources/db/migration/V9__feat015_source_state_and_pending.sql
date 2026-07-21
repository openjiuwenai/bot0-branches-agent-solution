-- V9: source revision tracking + deployment service id for discovery queries

CREATE TABLE IF NOT EXISTS registry_source_state (
    source_id              VARCHAR(64) PRIMARY KEY,
    last_processed_revision BIGINT      NOT NULL DEFAULT 0,
    last_success_at        TIMESTAMPTZ
);

ALTER TABLE agent_registry_mvp ADD COLUMN IF NOT EXISTS deployment_service_id VARCHAR(128);

UPDATE agent_registry_mvp
SET deployment_service_id = agent_id
WHERE deployment_service_id IS NULL;

CREATE INDEX IF NOT EXISTS idx_agent_registry_mvp_deployment_service
    ON agent_registry_mvp (tenant_id, deployment_service_id);
