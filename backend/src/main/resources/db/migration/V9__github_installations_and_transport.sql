-- Global rows hold transport routing only, never source/change facts. See ADR-030.
CREATE TABLE github_installation_routes (
    installation_external_id bigint PRIMARY KEY CHECK (installation_external_id > 0),
    tenant_id uuid NOT NULL REFERENCES tenants(id),
    installation_id uuid NOT NULL UNIQUE,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (tenant_id, installation_id, installation_external_id)
);

CREATE TABLE github_installations (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES tenants(id),
    installation_external_id bigint NOT NULL CHECK (installation_external_id > 0),
    account_external_id bigint NOT NULL,
    account_login text NOT NULL,
    permissions_json jsonb NOT NULL,
    status text NOT NULL CHECK (status IN ('PENDING_BINDING','ACTIVE','SUSPENDED','PERMISSION_UPDATE_PENDING','ACCESS_REDUCED','DELETED','ERROR')),
    generation bigint NOT NULL DEFAULT 1,
    last_verified_at timestamptz,
    cooldown_until timestamptz,
    processing_token uuid,
    processing_until timestamptz,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, installation_external_id),
    FOREIGN KEY (tenant_id, id, installation_external_id)
        REFERENCES github_installation_routes(tenant_id, installation_id, installation_external_id),
    CHECK ((processing_token IS NULL) = (processing_until IS NULL))
);

CREATE TABLE github_binding_states (
    state_hash bytea PRIMARY KEY CHECK (octet_length(state_hash)=32),
    tenant_id uuid NOT NULL REFERENCES tenants(id),
    user_id uuid NOT NULL REFERENCES users(id),
    pkce_challenge text NOT NULL,
    expires_at timestamptz NOT NULL,
    consumed_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);

ALTER TABLE repositories ADD CONSTRAINT uq_repository_tenant_identity UNIQUE (tenant_id,id);
CREATE TABLE github_installation_repositories (
    tenant_id uuid NOT NULL REFERENCES tenants(id),
    installation_id uuid NOT NULL,
    repository_id uuid NOT NULL,
    repository_external_id bigint NOT NULL CHECK (repository_external_id > 0),
    status text NOT NULL CHECK (status IN ('ACTIVE','ACCESS_REMOVED')),
    verified_at timestamptz NOT NULL,
    PRIMARY KEY (tenant_id,installation_id,repository_external_id),
    UNIQUE (tenant_id,installation_id,repository_id),
    FOREIGN KEY (tenant_id,installation_id) REFERENCES github_installations(tenant_id,id),
    FOREIGN KEY (tenant_id,repository_id) REFERENCES repositories(tenant_id,id)
);

-- Only selectors needed for canonical refetch are permitted; application builds this object, not raw JSON.
CREATE TABLE github_deliveries (
    id uuid PRIMARY KEY,
    provider text NOT NULL DEFAULT 'GITHUB' CHECK (provider='GITHUB'),
    external_delivery_id uuid NOT NULL,
    hook_id bigint NOT NULL CHECK (hook_id > 0),
    installation_external_id bigint,
    repository_external_id bigint,
    event_type varchar(80) NOT NULL,
    action varchar(80) NOT NULL,
    payload_hash bytea NOT NULL CHECK (octet_length(payload_hash)=32),
    signature_key_version varchar(40) NOT NULL,
    selectors jsonb NOT NULL CHECK (octet_length(selectors::text) <= 16384),
    status text NOT NULL CHECK (status IN ('AWAITING_BINDING','QUEUED','PROCESSING','PROCESSED','IGNORED','RETRYABLE_FAILURE','DEAD')),
    job_id uuid,
    correlation_id uuid NOT NULL,
    received_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    processed_at timestamptz,
    failure_code varchar(80),
    UNIQUE (provider,external_delivery_id)
);
CREATE INDEX idx_github_delivery_pending ON github_deliveries(installation_external_id,received_at)
    WHERE status IN ('AWAITING_BINDING','QUEUED','RETRYABLE_FAILURE');
CREATE INDEX idx_github_binding_expiry ON github_binding_states(expires_at) WHERE consumed_at IS NULL;
CREATE INDEX idx_github_installation_work ON github_installations(tenant_id,status,cooldown_until);

ALTER TABLE github_installations ENABLE ROW LEVEL SECURITY;
ALTER TABLE github_installations FORCE ROW LEVEL SECURITY;
CREATE POLICY github_installation_isolation ON github_installations
    USING (tenant_id=guidein_current_tenant()) WITH CHECK (tenant_id=guidein_current_tenant());
ALTER TABLE github_binding_states ENABLE ROW LEVEL SECURITY;
ALTER TABLE github_binding_states FORCE ROW LEVEL SECURITY;
CREATE POLICY github_binding_isolation ON github_binding_states
    USING (tenant_id=guidein_current_tenant() AND user_id=guidein_current_user())
    WITH CHECK (tenant_id=guidein_current_tenant() AND user_id=guidein_current_user());
ALTER TABLE github_installation_repositories ENABLE ROW LEVEL SECURITY;
ALTER TABLE github_installation_repositories FORCE ROW LEVEL SECURITY;
CREATE POLICY github_repository_access_isolation ON github_installation_repositories
    USING (tenant_id=guidein_current_tenant()) WITH CHECK (tenant_id=guidein_current_tenant());

GRANT SELECT,INSERT,UPDATE ON github_installations,github_binding_states,github_installation_repositories,github_deliveries TO guidein_app;
-- Routing is append-only from runtime. Binding cannot be reassigned by an UPDATE.
GRANT SELECT,INSERT ON github_installation_routes TO guidein_app;
