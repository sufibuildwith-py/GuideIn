DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'guidein_app') THEN
        CREATE ROLE guidein_app NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS;
    END IF;
END
$$;

CREATE OR REPLACE FUNCTION guidein_current_user_nullable()
RETURNS uuid
LANGUAGE sql
STABLE
PARALLEL SAFE
AS $$
    SELECT NULLIF(current_setting('guidein.user_id', true), '')::uuid
$$;

CREATE OR REPLACE FUNCTION guidein_current_tenant_nullable()
RETURNS uuid
LANGUAGE sql
STABLE
PARALLEL SAFE
AS $$
    SELECT NULLIF(current_setting('guidein.tenant_id', true), '')::uuid
$$;

CREATE OR REPLACE FUNCTION guidein_current_user()
RETURNS uuid
LANGUAGE plpgsql
STABLE
PARALLEL SAFE
AS $$
DECLARE value uuid;
BEGIN
    value := guidein_current_user_nullable();
    IF value IS NULL THEN
        RAISE EXCEPTION 'authenticated user context is missing' USING ERRCODE = '28000';
    END IF;
    RETURN value;
END
$$;

CREATE OR REPLACE FUNCTION guidein_current_tenant()
RETURNS uuid
LANGUAGE plpgsql
STABLE
PARALLEL SAFE
AS $$
DECLARE value uuid;
BEGIN
    value := guidein_current_tenant_nullable();
    IF value IS NULL THEN
        RAISE EXCEPTION 'tenant context is missing' USING ERRCODE = '28000';
    END IF;
    RETURN value;
END
$$;

GRANT EXECUTE ON FUNCTION guidein_current_user_nullable() TO guidein_app;
GRANT EXECUTE ON FUNCTION guidein_current_tenant_nullable() TO guidein_app;
GRANT EXECUTE ON FUNCTION guidein_current_user() TO guidein_app;
GRANT EXECUTE ON FUNCTION guidein_current_tenant() TO guidein_app;

