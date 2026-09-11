#!/bin/sh
set -eu

psql --set ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
  --set=migrator_password="$GUIDEIN_MIGRATOR_PASSWORD" \
  --set=app_password="$GUIDEIN_APP_PASSWORD" <<'SQL'
CREATE ROLE guidein_migrator LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS PASSWORD :'migrator_password';
CREATE ROLE guidein_app LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOBYPASSRLS PASSWORD :'app_password';
GRANT CONNECT ON DATABASE guidein TO guidein_migrator, guidein_app;
GRANT CREATE, USAGE ON SCHEMA public TO guidein_migrator;
GRANT USAGE ON SCHEMA public TO guidein_app;
SQL

