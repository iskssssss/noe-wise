ALTER TABLE public.nw_platform_operational_setting_versions
    ADD COLUMN publication_source varchar(24) DEFAULT 'operator' NOT NULL;

ALTER TABLE public.nw_platform_operational_setting_versions
    DROP CONSTRAINT skillforge_platform_operational_setting_versions_actor_check;

ALTER TABLE public.nw_platform_operational_setting_versions
    ADD CONSTRAINT skillforge_platform_operational_setting_versions_actor_check
    CHECK ((publication_source = 'operator' AND (actor_id IS NOT NULL OR revision = 1))
        OR (publication_source = 'system_release' AND actor_id IS NULL));

ALTER TABLE public.nw_platform_operational_setting_versions
    ADD CONSTRAINT skillforge_platform_operational_setting_versions_source_check
    CHECK (publication_source IN ('operator', 'system_release'));

COMMENT ON COLUMN public.nw_platform_operational_setting_versions.actor_id
    IS '创建版本的平台成员；系统发布的默认或迁移版本允许为空';

COMMENT ON COLUMN public.nw_platform_operational_setting_versions.publication_source
    IS '版本发布来源：operator 表示平台管理员操作，system_release 表示受控版本迁移';
