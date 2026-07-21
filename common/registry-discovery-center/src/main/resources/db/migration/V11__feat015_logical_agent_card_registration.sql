-- V11: Feat-015 logical AgentCardRegistration catalog + source references
--
-- Separates FEAT-015 logical Card directory from FEAT-016 instance route index
-- (agent_registry_mvp remains the instance-level route store).

CREATE TABLE agent_card_registration (
    registration_id   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         VARCHAR(64)  NOT NULL,
    agent_id          VARCHAR(128) NOT NULL,
    service_id        VARCHAR(128) NOT NULL,
    card_digest       VARCHAR(64)  NOT NULL,
    contract_version  VARCHAR(32)  NOT NULL,
    capability_version VARCHAR(32) NOT NULL,
    registration_status VARCHAR(16) NOT NULL,
    freshness         VARCHAR(16)  NOT NULL,
    last_validated_at TIMESTAMPTZ,
    revision          BIGINT       NOT NULL DEFAULT 1,
    a2a_agent_card    JSONB,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_agent_card_registration_identity
        UNIQUE (tenant_id, service_id, capability_version, card_digest)
);

CREATE TABLE agent_card_source_ref (
    tenant_id           VARCHAR(64)  NOT NULL,
    service_id          VARCHAR(128) NOT NULL,
    instance_id         VARCHAR(64)  NOT NULL,
    source_id           VARCHAR(64)  NOT NULL,
    source_revision     BIGINT       NOT NULL,
    internal_base_url   TEXT         NOT NULL,
    deployment_version  VARCHAR(32),
    readiness           VARCHAR(16)  NOT NULL,
    observed_at         TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    registration_id     UUID         NOT NULL REFERENCES agent_card_registration(registration_id) ON DELETE CASCADE,
    PRIMARY KEY (tenant_id, service_id, instance_id)
);

CREATE INDEX idx_acr_tenant_agent ON agent_card_registration (tenant_id, agent_id);
CREATE INDEX idx_acr_tenant_service ON agent_card_registration (tenant_id, service_id);
CREATE INDEX idx_acr_status ON agent_card_registration (tenant_id, registration_status);
CREATE INDEX idx_acr_discover_skill ON agent_card_registration
    USING GIN ((a2a_agent_card -> 'skills'));
CREATE INDEX idx_acr_source_ref_registration ON agent_card_source_ref (registration_id);

ALTER TABLE agent_card_registration ENABLE ROW LEVEL SECURITY;
ALTER TABLE agent_card_source_ref ENABLE ROW LEVEL SECURITY;

CREATE POLICY agent_card_registration_tenant_isolation ON agent_card_registration
    USING (tenant_id = current_setting('app.tenant_id', true));

CREATE POLICY agent_card_source_ref_tenant_isolation ON agent_card_source_ref
    USING (tenant_id = current_setting('app.tenant_id', true));
