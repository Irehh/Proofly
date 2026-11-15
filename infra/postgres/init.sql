-- Local bootstrap roles for Proofly control plane and tenant DB provisioning.
-- Note: Production deploys must inject rotated credentials from Vault / Secret Manager.

DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'proofly_app') THEN
    CREATE ROLE proofly_app WITH LOGIN PASSWORD 'proofly_app_dev_password';
  END IF;
END
$$;

CREATE EXTENSION IF NOT EXISTS postgis;
CREATE EXTENSION IF NOT EXISTS pgcrypto;

GRANT CONNECT ON DATABASE proofly_control TO proofly_app;
GRANT USAGE, CREATE ON SCHEMA public TO proofly_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO proofly_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT USAGE, SELECT ON SEQUENCES TO proofly_app;
