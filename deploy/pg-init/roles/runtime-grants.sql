-- 使用 psql -v migration_owner=... -v platform_role=... -v institution_role=... 执行。
-- 当前连接必须是管理员；角色和数据库须已存在，不创建、改密或提升机构权限。
\set ON_ERROR_STOP on
BEGIN;
SELECT set_config('skillforge.bootstrap.migration_owner', :'migration_owner', true) AS bootstrap_owner,
       set_config('skillforge.bootstrap.platform_role', :'platform_role', true) AS bootstrap_platform,
       set_config('skillforge.bootstrap.institution_role', :'institution_role', true) AS bootstrap_institution \gset
DO $$
DECLARE
    owner_name text := current_setting('skillforge.bootstrap.migration_owner');
    platform_name text := current_setting('skillforge.bootstrap.platform_role');
    institution_name text := current_setting('skillforge.bootstrap.institution_role');
BEGIN
    IF owner_name = platform_name OR owner_name = institution_name OR platform_name = institution_name THEN
        RAISE EXCEPTION '迁移 owner、平台和机构角色必须分别配置';
    END IF;
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = owner_name)
        OR NOT EXISTS (SELECT FROM pg_roles WHERE rolname = platform_name AND rolbypassrls AND rolcanlogin)
        OR NOT EXISTS (SELECT FROM pg_roles WHERE rolname = institution_name AND rolcanlogin AND NOT rolsuper AND NOT rolbypassrls AND NOT rolcreaterole) THEN
        RAISE EXCEPTION '需要已存在的 Flyway owner、BYPASSRLS 平台登录角色和 NOSUPERUSER/NOBYPASSRLS 机构登录角色';
    END IF;
    IF pg_has_role(institution_name, owner_name, 'MEMBER')
        OR pg_has_role(institution_name, platform_name, 'MEMBER')
        OR EXISTS (SELECT FROM pg_database WHERE datname = current_database() AND pg_has_role(institution_name, datdba, 'MEMBER'))
        OR EXISTS (SELECT FROM pg_namespace WHERE nspname = 'public' AND pg_has_role(institution_name, nspowner, 'MEMBER'))
        OR EXISTS (SELECT FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                   WHERE n.nspname = 'public' AND pg_has_role(institution_name, c.relowner, 'MEMBER'))
        OR EXISTS (SELECT FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
                   WHERE n.nspname = 'public' AND pg_has_role(institution_name, p.proowner, 'MEMBER'))
        OR EXISTS (SELECT FROM pg_roles r
                   WHERE pg_has_role(institution_name, r.oid, 'MEMBER') AND (r.rolsuper OR r.rolbypassrls OR r.rolcreaterole))
        OR has_schema_privilege(institution_name, 'public', 'CREATE')
        OR has_database_privilege(institution_name, current_database(), 'CREATE') THEN
        RAISE EXCEPTION '机构角色不得拥有 public 对象、数据库或继承迁移 owner/特权角色';
    END IF;
    IF EXISTS (SELECT FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
               WHERE n.nspname = 'public' AND c.relkind IN ('r', 'p')
                 AND has_table_privilege(institution_name, c.oid, 'TRUNCATE,REFERENCES,TRIGGER'))
        OR EXISTS (SELECT FROM pg_default_acl d CROSS JOIN LATERAL aclexplode(d.defaclacl) a
                   WHERE d.defaclrole = owner_name::regrole AND d.defaclobjtype = 'r'
                     AND d.defaclnamespace IN (0, 'public'::regnamespace)
                     AND a.privilege_type IN ('TRUNCATE', 'REFERENCES', 'TRIGGER')
                     AND CASE WHEN a.grantee = 0 THEN true ELSE pg_has_role(institution_name, a.grantee, 'MEMBER') END) THEN
        RAISE EXCEPTION '机构角色已有 TRUNCATE/REFERENCES/TRIGGER 对象或默认权限；须由 DBA 核对来源并撤销，准备入口不静默保留或撤权';
    END IF;
END
$$;
GRANT CONNECT ON DATABASE :"DBNAME" TO :"platform_role", :"institution_role";
GRANT USAGE ON SCHEMA public TO :"platform_role", :"institution_role";
GRANT ALL ON ALL TABLES IN SCHEMA public TO :"platform_role";
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO :"institution_role";
-- BEGIN MODEL TIME PRICING READ ONLY
-- 机构只能读取平台管理的峰谷配置；准备入口重跑后也不得获得写权。
-- 首次迁移前这些表可能尚未创建，只调整实际存在的精确对象。
DO $$
DECLARE
    institution_name text := current_setting('skillforge.bootstrap.institution_role');
    pricing_table text;
BEGIN
    FOREACH pricing_table IN ARRAY ARRAY[
        'nw_model_time_pricing_rules',
        'nw_model_time_pricing_weekdays',
        'nw_model_time_pricing_periods',
        'nw_model_time_pricing_dates',
        'nw_model_time_pricing_scopes',
        'nw_model_time_pricing_presets',
        'nw_model_time_pricing_preset_versions'
    ] LOOP
        IF to_regclass(format('public.%I', pricing_table)) IS NOT NULL THEN
            EXECUTE format('REVOKE INSERT, UPDATE, DELETE ON TABLE public.%I FROM %I', pricing_table, institution_name);
        END IF;
    END LOOP;
END
$$;
-- END MODEL TIME PRICING READ ONLY
GRANT ALL ON ALL SEQUENCES IN SCHEMA public TO :"platform_role";
GRANT USAGE, SELECT, UPDATE ON ALL SEQUENCES IN SCHEMA public TO :"institution_role";
GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA public TO :"platform_role", :"institution_role";
-- 默认权限必须归实际创建对象的 Flyway owner，不能归当前执行管理员。
ALTER DEFAULT PRIVILEGES FOR ROLE :"migration_owner" IN SCHEMA public GRANT ALL ON TABLES TO :"platform_role";
ALTER DEFAULT PRIVILEGES FOR ROLE :"migration_owner" IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO :"institution_role";
ALTER DEFAULT PRIVILEGES FOR ROLE :"migration_owner" IN SCHEMA public GRANT ALL ON SEQUENCES TO :"platform_role";
ALTER DEFAULT PRIVILEGES FOR ROLE :"migration_owner" IN SCHEMA public GRANT USAGE, SELECT, UPDATE ON SEQUENCES TO :"institution_role";
ALTER DEFAULT PRIVILEGES FOR ROLE :"migration_owner" IN SCHEMA public GRANT EXECUTE ON FUNCTIONS TO :"platform_role", :"institution_role";
COMMIT;
