-- agent_registry_mvp schema refactor — REQ-2026-001
--
-- Drops 4 legacy columns that overlapped with the A2A standard AgentCard
-- or were semantically redundant, and adds an a2a_agent_card jsonb column
-- carrying the full A2A card as registry metadata. The full-text search
-- vector (search_tsv) is rebuilt to source from the A2A card's description
-- + name fields instead of the dropped capability_keywords + system_profile.
--
-- Authority: REQ-2026-001 (PRD .harness/version-intents/REQ-2026-001/step-1-PRD.md)
-- Decision R-1(a): rebuild search_tsv from a2a_agent_card->>'description'
-- (weight A) + a2a_agent_card->>'name' (weight B).
--
-- IRREVERSIBLE — DROP COLUMN cannot be rolled back. Review before production
-- deploy. The previous V2 schema is preserved in git history.
--
-- Columns removed:
--   service_id          — redundant with agent_id (registry PK is
--                         (tenant_id, agent_id); serviceId was never part of
--                         the PK or hasRegistryKey() validation)
--   capability_keywords — overlapped with A2A AgentCard.skills[].tags
--   system_profile      — semantically vague; overlapped with A2A
--                         AgentCard.capabilities + supportedInterfaces
--   tool_schemas        — overlapped with A2A AgentCard.skills
--
-- Column added:
--   a2a_agent_card      — JSONB serialization of org.a2aproject.sdk.spec.AgentCard
--                         (the A2A standard card served by runtime at
--                         /.well-known/agent-card.json). Nullable — callers
--                         may register without an A2A card; search_tsv
--                         degrades to empty tsvector via coalesce.
--
-- Column rebuilt:
--   search_tsv          — GENERATED ALWAYS AS (
--                          setweight(to_tsvector('simple', coalesce(a2a_agent_card->>'description','')), 'A') ||
--                          setweight(to_tsvector('simple', coalesce(a2a_agent_card->>'name','')), 'B')
--                         ) STORED
--                         Source changes from (capability_keywords + system_profile)
--                         to (a2a_agent_card->>'description' + ->>'name').

-- ===== 1. Drop old full-text index + GENERATED column (must precede source column drops) =====
DROP INDEX IF EXISTS idx_agent_registry_mvp_search_tsv;
ALTER TABLE agent_registry_mvp DROP COLUMN IF EXISTS search_tsv;

-- ===== 2. Drop legacy columns =====
ALTER TABLE agent_registry_mvp DROP COLUMN IF EXISTS service_id;
ALTER TABLE agent_registry_mvp DROP COLUMN IF EXISTS capability_keywords;
ALTER TABLE agent_registry_mvp DROP COLUMN IF EXISTS system_profile;
ALTER TABLE agent_registry_mvp DROP COLUMN IF EXISTS tool_schemas;

-- ===== 3. Add A2A card column =====
ALTER TABLE agent_registry_mvp ADD COLUMN a2a_agent_card jsonb;

-- ===== 4. Rebuild search_tsv from A2A card JSON =====
ALTER TABLE agent_registry_mvp ADD COLUMN search_tsv TSVECTOR
    GENERATED ALWAYS AS (
        setweight(to_tsvector('simple', coalesce(a2a_agent_card->>'description', '')), 'A') ||
        setweight(to_tsvector('simple', coalesce(a2a_agent_card->>'name', '')), 'B')
    ) STORED;

CREATE INDEX idx_agent_registry_mvp_search_tsv
    ON agent_registry_mvp
    USING GIN (search_tsv);
