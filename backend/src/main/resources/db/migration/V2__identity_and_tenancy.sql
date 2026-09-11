CREATE TABLE users (
    id uuid PRIMARY KEY,
    auth_issuer text NOT NULL,
    external_subject text NOT NULL,
    email_normalized text,
    display_name text,
    status text NOT NULL CHECK (status IN ('ACTIVE', 'SUSPENDED', 'DISABLED')),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (auth_issuer, external_subject)
);

CREATE TABLE tenants (
    id uuid PRIMARY KEY,
    slug text NOT NULL UNIQUE,
    name text NOT NULL,
    status text NOT NULL CHECK (status IN ('ACTIVE', 'SUSPENDED', 'DISABLED')),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);

CREATE TABLE memberships (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES tenants(id),
    user_id uuid NOT NULL REFERENCES users(id),
    role text NOT NULL CHECK (role IN ('OWNER', 'ADMIN', 'SECURITY', 'RELEASE_MANAGER', 'ENGINEER', 'AUDITOR', 'VIEWER')),
    scope_mode text NOT NULL CHECK (scope_mode IN ('ALL_REPOSITORIES', 'SELECTED_REPOSITORIES')),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (tenant_id, user_id)
);

GRANT SELECT, INSERT, UPDATE ON users TO guidein_app;
GRANT SELECT ON tenants, memberships TO guidein_app;
GRANT INSERT, UPDATE ON tenants, memberships TO guidein_app;

