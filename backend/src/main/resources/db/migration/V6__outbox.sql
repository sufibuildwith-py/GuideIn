CREATE TABLE outbox_events (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES tenants(id),
    aggregate_type text NOT NULL,
    aggregate_id uuid NOT NULL,
    event_type text NOT NULL,
    event_version integer NOT NULL CHECK (event_version > 0),
    payload_json jsonb NOT NULL,
    payload_hash bytea NOT NULL,
    correlation_id uuid NOT NULL,
    causation_id uuid,
    occurred_at timestamptz NOT NULL,
    published_at timestamptz,
    attempt_count integer NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    locked_until timestamptz,
    lock_token uuid,
    last_error_code text,
    CHECK ((lock_token IS NULL) = (locked_until IS NULL))
);

ALTER TABLE outbox_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE outbox_events FORCE ROW LEVEL SECURITY;
CREATE POLICY outbox_isolation ON outbox_events
    USING (tenant_id = guidein_current_tenant())
    WITH CHECK (tenant_id = guidein_current_tenant());

GRANT SELECT, INSERT, UPDATE ON outbox_events TO guidein_app;

