-- V4: REQ-2026-004 baseline-breaking schema refactor
--
-- Three coupled changes:
--   1. DROP free-text search infrastructure (search_tsv column + GIN index)
--      — discovery collapses to searchByAgentId single-value point lookup.
--   2. DROP capability column + its BTREE index — A2A AgentCard carries no
--      such concept, pull-based registration cannot derive it.
--   3. RENAME agent_type → framework_type — closed FrameworkType enum
--      replaces free-text String.
--
-- IRREVERSIBLE: DROP COLUMN is non-recoverable. No prod backfill provided
-- (PRD non-target). H4 checkpoint required before prod deploy.
--
-- D-3 (step-2 anatomy): idx_agent_registry_mvp_tenant_capability was created
-- in V2 alongside the capability column but V3 did not drop it. PRD VR-7
-- missed this index. V4 must DROP it explicitly — otherwise the capability
-- column DROP would leave an orphan index (PG actually auto-drops indexes
-- on the dropped column, but being explicit avoids planner surprises and
-- documents intent).

-- ===== free-text search infrastructure =====

DROP INDEX IF EXISTS idx_agent_registry_mvp_search_tsv;

ALTER TABLE agent_registry_mvp DROP COLUMN IF EXISTS search_tsv;

-- ===== capability column + its BTREE index =====

DROP INDEX IF EXISTS idx_agent_registry_mvp_tenant_capability;

ALTER TABLE agent_registry_mvp DROP COLUMN IF EXISTS capability;

-- ===== agent_type → framework_type rename =====

ALTER TABLE agent_registry_mvp RENAME COLUMN agent_type TO framework_type;
