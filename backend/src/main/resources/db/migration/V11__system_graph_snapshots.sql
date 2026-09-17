CREATE TABLE graph_snapshots (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    repository_id uuid NOT NULL,
    source_sha varchar(64) NOT NULL CHECK (source_sha ~ '^([0-9a-f]{40}|[0-9a-f]{64})$'),
    input_identity varchar(64) NOT NULL CHECK (input_identity ~ '^[0-9a-f]{64}$'),
    builder_version text NOT NULL,
    extractor_versions jsonb NOT NULL,
    configuration_digest varchar(64) NOT NULL,
    guidein_config_digest varchar(64),
    status text NOT NULL DEFAULT 'QUEUED' CHECK (status IN ('QUEUED','BUILDING','READY','PARTIAL','FAILED')),
    canonical_digest varchar(64),
    node_count integer NOT NULL DEFAULT 0 CHECK (node_count >= 0),
    edge_count integer NOT NULL DEFAULT 0 CHECK (edge_count >= 0),
    gap_count integer NOT NULL DEFAULT 0 CHECK (gap_count >= 0),
    failure_category text,
    job_id uuid,
    created_at timestamptz NOT NULL DEFAULT now(),
    published_at timestamptz,
    UNIQUE (tenant_id,id),
    UNIQUE (tenant_id,input_identity),
    FOREIGN KEY (tenant_id,repository_id) REFERENCES repositories(tenant_id,id)
);
CREATE TABLE graph_nodes (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    snapshot_id uuid NOT NULL,
    node_key text NOT NULL CHECK (octet_length(node_key) BETWEEN 1 AND 2048),
    node_type text NOT NULL CHECK (node_type IN ('REPOSITORY','MODULE','PACKAGE','FILE','SYMBOL','SERVICE','API','ENDPOINT','EXTERNAL_PROVIDER','DEPLOYMENT','CONFIGURATION')),
    display_name text NOT NULL,
    criticality text NOT NULL CHECK (criticality IN ('UNKNOWN','LOW','MEDIUM','HIGH','CRITICAL')),
    source_identity text NOT NULL,
    metadata jsonb NOT NULL,
    UNIQUE (tenant_id,snapshot_id,id),
    UNIQUE (tenant_id,snapshot_id,id,node_key),
    UNIQUE (tenant_id,snapshot_id,node_key),
    FOREIGN KEY (tenant_id,snapshot_id) REFERENCES graph_snapshots(tenant_id,id)
);
CREATE TABLE graph_edges (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    snapshot_id uuid NOT NULL,
    from_node_id uuid NOT NULL,
    to_node_id uuid NOT NULL,
    from_node_key text NOT NULL CHECK (octet_length(from_node_key) BETWEEN 1 AND 2048),
    to_node_key text NOT NULL CHECK (octet_length(to_node_key) BETWEEN 1 AND 2048),
    edge_type text NOT NULL CHECK (edge_type IN ('CONTAINS','IMPORTS','DECLARES','DEPENDS_ON','EXPOSES','DEPLOYED_AS')),
    evidence_digest varchar(64) NOT NULL CHECK (evidence_digest ~ '^[0-9a-f]{64}$'),
    UNIQUE (tenant_id,snapshot_id,id),
    UNIQUE (tenant_id,snapshot_id,from_node_id,edge_type,to_node_id),
    UNIQUE (tenant_id,snapshot_id,from_node_key,edge_type,to_node_key),
    FOREIGN KEY (tenant_id,snapshot_id) REFERENCES graph_snapshots(tenant_id,id),
    FOREIGN KEY (tenant_id,snapshot_id,from_node_id,from_node_key) REFERENCES graph_nodes(tenant_id,snapshot_id,id,node_key),
    FOREIGN KEY (tenant_id,snapshot_id,to_node_id,to_node_key) REFERENCES graph_nodes(tenant_id,snapshot_id,id,node_key)
);
CREATE TABLE graph_edge_evidence (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    snapshot_id uuid NOT NULL,
    edge_id uuid NOT NULL,
    evidence_key varchar(64) NOT NULL,
    source_type text NOT NULL CHECK (source_type IN ('GUIDEIN','MAVEN','GRADLE','NPM','JAVA','OPENAPI','COMPOSE','DEPLOYMENT','FILE_TREE')),
    source_path text NOT NULL,
    source_locator text NOT NULL,
    source_digest varchar(64) NOT NULL CHECK (source_digest ~ '^[0-9a-f]{64}$'),
    extractor_version text NOT NULL,
    observation_type text NOT NULL,
    trust_class text NOT NULL CHECK (trust_class IN ('EXPLICIT','RESOLVED')),
    confidence numeric(3,2) NOT NULL CHECK (confidence BETWEEN 0 AND 1),
    metadata jsonb NOT NULL,
    UNIQUE (tenant_id,snapshot_id,edge_id,evidence_key),
    FOREIGN KEY (tenant_id,snapshot_id,edge_id) REFERENCES graph_edges(tenant_id,snapshot_id,id)
);
CREATE TABLE graph_extraction_gaps (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    snapshot_id uuid NOT NULL,
    category text NOT NULL CHECK (octet_length(category)<=128),
    source_path text NOT NULL CHECK (octet_length(source_path)<=1024),
    source_locator text NOT NULL CHECK (octet_length(source_locator)<=1024),
    UNIQUE (tenant_id,snapshot_id,category,source_path,source_locator),
    FOREIGN KEY (tenant_id,snapshot_id) REFERENCES graph_snapshots(tenant_id,id)
);
CREATE INDEX idx_graph_repository_revision ON graph_snapshots(tenant_id,repository_id,source_sha,status);
CREATE INDEX idx_graph_node_type ON graph_nodes(tenant_id,snapshot_id,node_type,node_key);
CREATE INDEX idx_graph_edge_forward ON graph_edges(tenant_id,snapshot_id,from_node_id,edge_type,to_node_id);
CREATE INDEX idx_graph_edge_reverse ON graph_edges(tenant_id,snapshot_id,to_node_id,edge_type,from_node_id);
CREATE INDEX idx_graph_evidence_edge ON graph_edge_evidence(tenant_id,snapshot_id,edge_id);

DO $rls$
DECLARE relation text;
BEGIN
    FOREACH relation IN ARRAY ARRAY['graph_snapshots','graph_nodes','graph_edges','graph_edge_evidence','graph_extraction_gaps'] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', relation);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', relation);
        EXECUTE format('CREATE POLICY tenant_isolation ON %I USING (tenant_id=guidein_current_tenant()) WITH CHECK (tenant_id=guidein_current_tenant())', relation);
        EXECUTE format('GRANT SELECT,INSERT ON %I TO guidein_app', relation);
    END LOOP;
END
$rls$;
GRANT UPDATE ON graph_snapshots TO guidein_app;
-- Rebuilding an unpublished attempt may remove its internal rows. Published products remain immutable.
GRANT DELETE ON graph_nodes,graph_edges,graph_edge_evidence,graph_extraction_gaps TO guidein_app;

CREATE FUNCTION guidein_graph_child_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE target_tenant uuid; target_snapshot uuid; snapshot_state text;
BEGIN
    IF TG_OP='DELETE' THEN target_tenant:=OLD.tenant_id; target_snapshot:=OLD.snapshot_id;
    ELSE target_tenant:=NEW.tenant_id; target_snapshot:=NEW.snapshot_id; END IF;
    SELECT status INTO snapshot_state FROM graph_snapshots
      WHERE tenant_id=target_tenant AND id=target_snapshot FOR SHARE;
    IF snapshot_state IS DISTINCT FROM 'BUILDING' THEN
        RAISE EXCEPTION 'Graph content requires an unpublished build' USING ERRCODE='55000';
    END IF;
    IF TG_OP='DELETE' THEN RETURN OLD; ELSE RETURN NEW; END IF;
END
$$;
DO $guards$
DECLARE relation text;
BEGIN
    FOREACH relation IN ARRAY ARRAY['graph_nodes','graph_edges','graph_edge_evidence','graph_extraction_gaps'] LOOP
        EXECUTE format('CREATE TRIGGER graph_content_guard BEFORE INSERT OR UPDATE OR DELETE ON %I FOR EACH ROW EXECUTE FUNCTION guidein_graph_child_guard()',relation);
    END LOOP;
END
$guards$;

CREATE FUNCTION guidein_graph_snapshot_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE actual_nodes bigint; actual_edges bigint; actual_gaps bigint; evidenced_edges bigint;
BEGIN
    IF TG_OP='INSERT' THEN
        IF NEW.status <> 'QUEUED' OR NEW.canonical_digest IS NOT NULL OR NEW.published_at IS NOT NULL THEN
            RAISE EXCEPTION 'Graph must begin queued' USING ERRCODE='55000';
        END IF;
        RETURN NEW;
    END IF;
    IF OLD.status IN ('READY','PARTIAL') THEN
        RAISE EXCEPTION 'Published graph is immutable' USING ERRCODE='55000';
    END IF;
    IF (NEW.tenant_id,NEW.id,NEW.repository_id,NEW.source_sha,NEW.input_identity,NEW.builder_version,NEW.extractor_versions,NEW.configuration_digest)
        IS DISTINCT FROM
       (OLD.tenant_id,OLD.id,OLD.repository_id,OLD.source_sha,OLD.input_identity,OLD.builder_version,OLD.extractor_versions,OLD.configuration_digest) THEN
        RAISE EXCEPTION 'Graph input identity is immutable' USING ERRCODE='55000';
    END IF;
    IF NEW.status IN ('READY','PARTIAL') THEN
        IF OLD.status <> 'BUILDING' OR NEW.canonical_digest IS NULL OR NEW.canonical_digest !~ '^[0-9a-f]{64}$' OR NEW.published_at IS NULL THEN
            RAISE EXCEPTION 'Graph publication requires a complete build' USING ERRCODE='55000';
        END IF;
        SELECT count(*) INTO actual_nodes FROM graph_nodes WHERE tenant_id=NEW.tenant_id AND snapshot_id=NEW.id;
        SELECT count(*) INTO actual_edges FROM graph_edges WHERE tenant_id=NEW.tenant_id AND snapshot_id=NEW.id;
        SELECT count(*) INTO actual_gaps FROM graph_extraction_gaps WHERE tenant_id=NEW.tenant_id AND snapshot_id=NEW.id;
        IF actual_nodes=0 OR NEW.node_count<>actual_nodes OR NEW.edge_count<>actual_edges OR NEW.gap_count<>actual_gaps
             OR (NEW.status='READY' AND actual_gaps<>0) OR (NEW.status='PARTIAL' AND actual_gaps=0) THEN
            RAISE EXCEPTION 'Graph publication counts or completeness invalid' USING ERRCODE='55000';
        END IF;
        -- The composite foreign key already proves that every evidence row belongs to
        -- an edge in this tenant and snapshot. Counting distinct covered edge IDs is
        -- therefore equivalent to an anti-join, but remains linear when a large new
        -- snapshot has no planner statistics until its transaction commits.
        SELECT count(DISTINCT edge_id) INTO evidenced_edges FROM graph_edge_evidence
            WHERE tenant_id=NEW.tenant_id AND snapshot_id=NEW.id;
        IF evidenced_edges<>actual_edges THEN
            RAISE EXCEPTION 'Graph edge evidence missing' USING ERRCODE='55000';
        END IF;
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER graph_snapshot_guard BEFORE INSERT OR UPDATE ON graph_snapshots
FOR EACH ROW EXECUTE FUNCTION guidein_graph_snapshot_guard();
