CREATE TABLE job_queue (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES tenants(id),
    job_type text NOT NULL,
    dedupe_key text NOT NULL,
    payload_json jsonb NOT NULL,
    status text NOT NULL CHECK (status IN ('READY', 'RUNNING', 'SUCCEEDED', 'FAILED', 'DEAD')),
    available_at timestamptz NOT NULL,
    lease_until timestamptz,
    lease_token uuid,
    attempt_count integer NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    max_attempts integer NOT NULL CHECK (max_attempts > 0),
    last_error_code text,
    correlation_id uuid NOT NULL,
    causation_id uuid,
    created_at timestamptz NOT NULL,
    completed_at timestamptz,
    CHECK ((status = 'RUNNING' AND lease_until IS NOT NULL AND lease_token IS NOT NULL)
        OR (status <> 'RUNNING' AND lease_until IS NULL AND lease_token IS NULL)),
    CHECK ((status IN ('SUCCEEDED', 'FAILED', 'DEAD')) = (completed_at IS NOT NULL))
);

CREATE UNIQUE INDEX uq_job_active_dedupe
    ON job_queue (tenant_id, job_type, dedupe_key)
    WHERE status IN ('READY', 'RUNNING');

ALTER TABLE job_queue ENABLE ROW LEVEL SECURITY;
ALTER TABLE job_queue FORCE ROW LEVEL SECURITY;
CREATE POLICY job_isolation ON job_queue
    USING (tenant_id = guidein_current_tenant())
    WITH CHECK (tenant_id = guidein_current_tenant());

GRANT SELECT, INSERT, UPDATE ON job_queue TO guidein_app;
