-- V12: tighten Feat-015 logical catalog RLS with WITH CHECK on writes
-- (V11 policies only had USING, so INSERT/UPDATE were unconstrained)

DROP POLICY IF EXISTS agent_card_registration_tenant_isolation ON agent_card_registration;
DROP POLICY IF EXISTS agent_card_source_ref_tenant_isolation ON agent_card_source_ref;

CREATE POLICY agent_card_registration_tenant_isolation ON agent_card_registration
    USING (tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true));

CREATE POLICY agent_card_source_ref_tenant_isolation ON agent_card_source_ref
    USING (tenant_id = current_setting('app.tenant_id', true))
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true));
