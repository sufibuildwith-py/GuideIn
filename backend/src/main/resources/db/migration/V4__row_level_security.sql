ALTER TABLE tenants ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenants FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tenants
    USING (id = guidein_current_tenant())
    WITH CHECK (id = guidein_current_tenant());

ALTER TABLE memberships ENABLE ROW LEVEL SECURITY;
ALTER TABLE memberships FORCE ROW LEVEL SECURITY;
CREATE POLICY membership_bootstrap_select ON memberships FOR SELECT
    USING (user_id = guidein_current_user() OR tenant_id = guidein_current_tenant_nullable());
CREATE POLICY membership_tenant_insert ON memberships FOR INSERT
    WITH CHECK (tenant_id = guidein_current_tenant());
CREATE POLICY membership_tenant_update ON memberships FOR UPDATE
    USING (tenant_id = guidein_current_tenant())
    WITH CHECK (tenant_id = guidein_current_tenant());

ALTER TABLE repositories ENABLE ROW LEVEL SECURITY;
ALTER TABLE repositories FORCE ROW LEVEL SECURITY;
CREATE POLICY repository_isolation ON repositories
    USING (tenant_id = guidein_current_tenant())
    WITH CHECK (tenant_id = guidein_current_tenant());

ALTER TABLE membership_repository_scopes ENABLE ROW LEVEL SECURITY;
ALTER TABLE membership_repository_scopes FORCE ROW LEVEL SECURITY;
CREATE POLICY membership_scope_isolation ON membership_repository_scopes
    USING (tenant_id = guidein_current_tenant())
    WITH CHECK (tenant_id = guidein_current_tenant());

