CREATE INDEX idx_memberships_user_tenant ON memberships(user_id, tenant_id);
CREATE INDEX idx_memberships_tenant_user ON memberships(tenant_id, user_id);
CREATE INDEX idx_repositories_tenant_id ON repositories(tenant_id, id);
CREATE INDEX idx_audit_events_tenant_sequence ON audit_events(tenant_id, sequence);
CREATE INDEX idx_outbox_pending ON outbox_events(occurred_at, id) WHERE published_at IS NULL;
CREATE INDEX idx_job_claim ON job_queue(status, available_at, id);
CREATE INDEX idx_job_tenant_type_dedupe ON job_queue(tenant_id, job_type, dedupe_key);

REVOKE CREATE ON SCHEMA public FROM guidein_app;
ALTER DEFAULT PRIVILEGES REVOKE ALL ON TABLES FROM guidein_app;
