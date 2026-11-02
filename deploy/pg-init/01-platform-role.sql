-- 仅用于 Compose 首次空库；生产角色与秘密由 DBA 创建。
\set ON_ERROR_STOP on
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'skillforge_platform') THEN
        CREATE ROLE skillforge_platform LOGIN PASSWORD 'skillforge_platform_dev' NOSUPERUSER BYPASSRLS;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'skillforge_institution') THEN
        CREATE ROLE skillforge_institution LOGIN PASSWORD 'skillforge_institution_dev' NOSUPERUSER NOBYPASSRLS;
    END IF;
END
$$;

\set migration_owner skillforge
\set platform_role skillforge_platform
\set institution_role skillforge_institution
\ir roles/runtime-grants.sql
