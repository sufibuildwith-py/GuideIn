CREATE TABLE changes (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES tenants(id),
    repository_id uuid NOT NULL,
    provider text NOT NULL CHECK (provider='GITHUB'),
    provider_change_id text NOT NULL,
    change_type text NOT NULL CHECK (change_type IN ('PULL_REQUEST','DIRECT_PUSH','BRANCH_DELETION')),
    base_sha varchar(64),
    head_sha varchar(64) NOT NULL,
    ref text,
    forced boolean NOT NULL DEFAULT false,
    title text,
    state text NOT NULL,
    reported_file_count integer,
    fetched_file_count integer NOT NULL CHECK (fetched_file_count >= 0),
    file_set_status text NOT NULL CHECK (file_set_status IN ('COMPLETE','INCOMPLETE_PROVIDER_LIMIT','INCOMPLETE_PAGE_BUDGET','UNKNOWN','NOT_APPLICABLE')),
    file_set_reason text,
    provider_updated_at timestamptz,
    normalized_at timestamptz NOT NULL,
    UNIQUE (tenant_id,id),
    UNIQUE (tenant_id,repository_id,provider_change_id,head_sha),
    FOREIGN KEY (tenant_id,repository_id) REFERENCES repositories(tenant_id,id)
);
CREATE TABLE change_current_revisions (
    tenant_id uuid NOT NULL,
    repository_id uuid NOT NULL,
    provider_change_id text NOT NULL,
    change_id uuid NOT NULL,
    head_sha varchar(64) NOT NULL,
    observed_at timestamptz NOT NULL,
    PRIMARY KEY (tenant_id,repository_id,provider_change_id),
    FOREIGN KEY (tenant_id,change_id) REFERENCES changes(tenant_id,id),
    FOREIGN KEY (tenant_id,repository_id) REFERENCES repositories(tenant_id,id)
);
CREATE TABLE change_files (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    change_id uuid NOT NULL,
    path text NOT NULL,
    previous_path text,
    status text NOT NULL,
    additions integer,
    deletions integer,
    changes integer,
    blob_after_sha varchar(64),
    blob_before_sha varchar(64),
    language text,
    UNIQUE (tenant_id,change_id,path),
    FOREIGN KEY (tenant_id,change_id) REFERENCES changes(tenant_id,id)
);
CREATE TABLE provenance_records (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    change_id uuid NOT NULL,
    provider text NOT NULL,
    repository_external_id bigint NOT NULL,
    installation_external_id bigint NOT NULL,
    subject_digest varchar(64) NOT NULL,
    actor_external_id text,
    actor_type text NOT NULL CHECK (actor_type IN ('USER','BOT','APP','SYSTEM','GHOST','UNKNOWN')),
    signature_verified boolean,
    signature_reason text,
    signature_verified_at timestamptz,
    provider_request_id text,
    transport_verified boolean NOT NULL,
    trust_class text NOT NULL,
    observed_at timestamptz NOT NULL,
    attestation_ref text,
    UNIQUE (tenant_id,change_id,subject_digest),
    FOREIGN KEY (tenant_id,change_id) REFERENCES changes(tenant_id,id)
);
CREATE TABLE ci_observations (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    repository_id uuid NOT NULL,
    head_sha varchar(64) NOT NULL,
    provider text NOT NULL,
    source_kind text NOT NULL CHECK (source_kind IN ('CHECK_RUN','COMMIT_STATUS')),
    external_check_id text NOT NULL,
    source_app_id text NOT NULL,
    name text NOT NULL,
    status text NOT NULL,
    conclusion text,
    started_at timestamptz,
    completed_at timestamptz,
    details_url text,
    observed_at timestamptz NOT NULL,
    provider_updated_at timestamptz,
    UNIQUE (tenant_id,repository_id,head_sha,source_kind,external_check_id,source_app_id),
    FOREIGN KEY (tenant_id,repository_id) REFERENCES repositories(tenant_id,id)
);
CREATE INDEX idx_change_revision_lookup ON changes(tenant_id,repository_id,head_sha);
CREATE INDEX idx_provenance_change ON provenance_records(tenant_id,change_id);
CREATE INDEX idx_ci_source_sha ON ci_observations(tenant_id,repository_id,head_sha);

DO $rls$
DECLARE relation text;
BEGIN
    FOREACH relation IN ARRAY ARRAY['changes','change_current_revisions','change_files','provenance_records','ci_observations'] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',relation);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',relation);
        EXECUTE format('CREATE POLICY tenant_isolation ON %I USING (tenant_id=guidein_current_tenant()) WITH CHECK (tenant_id=guidein_current_tenant())',relation);
        EXECUTE format('GRANT SELECT,INSERT ON %I TO guidein_app',relation);
    END LOOP;
END
$rls$;
GRANT UPDATE ON change_current_revisions,ci_observations TO guidein_app;
-- Runtime cannot mutate immutable revisions, files or provenance after insertion.
