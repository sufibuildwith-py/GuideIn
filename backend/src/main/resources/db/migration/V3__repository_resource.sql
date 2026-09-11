CREATE TABLE repositories (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES tenants(id),
    provider text NOT NULL CHECK (provider IN ('GITHUB')),
    external_id text NOT NULL,
    owner text NOT NULL,
    name text NOT NULL,
    status text NOT NULL CHECK (status IN ('ACTIVE', 'DISABLED')),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (tenant_id, provider, external_id)
);

CREATE TABLE membership_repository_scopes (
    tenant_id uuid NOT NULL REFERENCES tenants(id),
    membership_id uuid NOT NULL REFERENCES memberships(id),
    repository_id uuid NOT NULL REFERENCES repositories(id),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (membership_id, repository_id),
    UNIQUE (tenant_id, membership_id, repository_id)
);

GRANT SELECT, INSERT, UPDATE ON repositories, membership_repository_scopes TO guidein_app;

