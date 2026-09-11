CREATE TABLE audit_heads (
    tenant_id uuid PRIMARY KEY REFERENCES tenants(id),
    last_sequence bigint NOT NULL DEFAULT 0 CHECK (last_sequence >= 0),
    last_hash bytea
);

CREATE TABLE audit_events (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES tenants(id),
    sequence bigint NOT NULL CHECK (sequence > 0),
    actor_type text NOT NULL CHECK (actor_type IN ('USER', 'SERVICE', 'SYSTEM')),
    actor_id uuid,
    action text NOT NULL,
    resource_type text NOT NULL,
    resource_id uuid,
    correlation_id uuid NOT NULL,
    occurred_at timestamptz NOT NULL,
    canonical_payload jsonb NOT NULL,
    canonical_payload_hash bytea NOT NULL,
    previous_hash bytea,
    event_hash bytea NOT NULL,
    UNIQUE (tenant_id, sequence)
);

CREATE OR REPLACE FUNCTION reject_audit_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'audit history is immutable' USING ERRCODE = '42501';
END
$$;

CREATE TRIGGER audit_events_immutable
BEFORE UPDATE OR DELETE ON audit_events
FOR EACH ROW EXECUTE FUNCTION reject_audit_mutation();

ALTER TABLE audit_heads ENABLE ROW LEVEL SECURITY;
ALTER TABLE audit_heads FORCE ROW LEVEL SECURITY;
CREATE POLICY audit_head_isolation ON audit_heads
    USING (tenant_id = guidein_current_tenant())
    WITH CHECK (tenant_id = guidein_current_tenant());

ALTER TABLE audit_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE audit_events FORCE ROW LEVEL SECURITY;
CREATE POLICY audit_event_select ON audit_events FOR SELECT
    USING (tenant_id = guidein_current_tenant());
CREATE POLICY audit_event_insert ON audit_events FOR INSERT
    WITH CHECK (tenant_id = guidein_current_tenant());

GRANT SELECT, INSERT, UPDATE ON audit_heads TO guidein_app;
GRANT SELECT, INSERT ON audit_events TO guidein_app;
REVOKE UPDATE, DELETE ON audit_events FROM guidein_app;

