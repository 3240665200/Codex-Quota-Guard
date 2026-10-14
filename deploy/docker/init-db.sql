-- Executed only for a new PostgreSQL volume. Never embedded in the app image.
-- psql quotes the environment value as a SQL literal; no string-built SQL.
\getenv app_password APP_DB_PASSWORD
CREATE ROLE quota_guard LOGIN PASSWORD :'app_password';
ALTER DATABASE quota_guard OWNER TO quota_guard;
ALTER SCHEMA public OWNER TO quota_guard;
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
