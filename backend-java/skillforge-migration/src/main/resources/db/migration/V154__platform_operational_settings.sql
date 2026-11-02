CREATE TABLE public.nw_platform_operational_setting_versions (
    group_key varchar(64) NOT NULL,
    revision bigint NOT NULL,
    values jsonb NOT NULL,
    actor_id bigint,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT skillforge_platform_operational_setting_versions_pkey PRIMARY KEY (group_key, revision),
    CONSTRAINT skillforge_platform_operational_setting_versions_gro_f673982aa1
        CHECK (group_key ~ '^[a-z][a-z0-9]*(_[a-z0-9]+)*$'),
    CONSTRAINT skillforge_platform_operational_setting_versions_revision_check CHECK (revision > 0),
    CONSTRAINT skillforge_platform_operational_setting_versions_values_check
        CHECK (jsonb_typeof(values) = 'object'),
    CONSTRAINT skillforge_platform_operational_setting_versions_actor_check
        CHECK (actor_id IS NOT NULL OR revision = 1),
    CONSTRAINT skillforge_platform_operational_setting_versions_actor_fkey
        FOREIGN KEY (actor_id) REFERENCES public.nw_platform_members(id) ON DELETE RESTRICT
);

COMMENT ON TABLE public.nw_platform_operational_setting_versions
    IS '平台运营配置的不可变 JSONB 快照；每次更新或回滚均追加新版本';
COMMENT ON COLUMN public.nw_platform_operational_setting_versions.group_key
    IS '注册分组键，必须是 snake_case';
COMMENT ON COLUMN public.nw_platform_operational_setting_versions.revision
    IS '分组内单调递增的正整数版本';
COMMENT ON COLUMN public.nw_platform_operational_setting_versions.values
    IS '经注册表类型校验后的完整快照；敏感字段以 AES-GCM 密文保存';
COMMENT ON COLUMN public.nw_platform_operational_setting_versions.actor_id
    IS '创建版本的平台成员；代码默认版本允许为空';
COMMENT ON COLUMN public.nw_platform_operational_setting_versions.created_at
    IS '版本创建时间';

CREATE TABLE public.nw_platform_operational_setting_groups (
    group_key varchar(64) NOT NULL,
    current_revision bigint NOT NULL,
    updated_by bigint,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT skillforge_platform_operational_setting_groups_pkey PRIMARY KEY (group_key),
    CONSTRAINT skillforge_platform_operational_setting_groups_group_key_check
        CHECK (group_key ~ '^[a-z][a-z0-9]*(_[a-z0-9]+)*$'),
    CONSTRAINT skillforge_platform_operational_setting_groups_revision_check CHECK (current_revision > 0),
    CONSTRAINT skillforge_platform_operational_setting_groups_current_fkey
        FOREIGN KEY (group_key, current_revision)
        REFERENCES public.nw_platform_operational_setting_versions(group_key, revision)
        DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT skillforge_platform_operational_setting_groups_actor_fkey
        FOREIGN KEY (updated_by) REFERENCES public.nw_platform_members(id) ON DELETE RESTRICT
);

COMMENT ON TABLE public.nw_platform_operational_setting_groups
    IS '平台运营配置分组的当前版本指针，用于乐观并发 compare-and-set';
COMMENT ON COLUMN public.nw_platform_operational_setting_groups.group_key
    IS '注册分组键，必须是 snake_case';
COMMENT ON COLUMN public.nw_platform_operational_setting_groups.current_revision
    IS '当前生效的不可变版本号';
COMMENT ON COLUMN public.nw_platform_operational_setting_groups.updated_by
    IS '最近变更的平台成员；代码默认登记允许为空';
COMMENT ON COLUMN public.nw_platform_operational_setting_groups.updated_at
    IS '当前版本指针更新时间';

CREATE TABLE public.nw_platform_operational_setting_audits (
    id bigint GENERATED ALWAYS AS IDENTITY,
    group_key varchar(64) NOT NULL,
    operation varchar(16) NOT NULL,
    from_revision bigint NOT NULL,
    to_revision bigint NOT NULL,
    target_revision bigint,
    actor_id bigint NOT NULL,
    payload jsonb DEFAULT '{}'::jsonb NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT skillforge_platform_operational_setting_audits_pkey PRIMARY KEY (id),
    CONSTRAINT skillforge_platform_operational_setting_audits_operation_check
        CHECK (operation IN ('update', 'rollback')),
    CONSTRAINT skillforge_platform_operational_setting_audits_revision_check
        CHECK (from_revision > 0 AND to_revision = from_revision + 1
            AND (target_revision IS NULL OR target_revision > 0)),
    CONSTRAINT skillforge_platform_operational_setting_audits_target_check
        CHECK ((operation = 'update' AND target_revision IS NULL)
            OR (operation = 'rollback' AND target_revision IS NOT NULL)),
    CONSTRAINT skillforge_platform_operational_setting_audits_payload_check
        CHECK (jsonb_typeof(payload) = 'object'),
    CONSTRAINT skillforge_platform_operational_setting_audits_actor_fkey
        FOREIGN KEY (actor_id) REFERENCES public.nw_platform_members(id) ON DELETE RESTRICT,
    CONSTRAINT skillforge_platform_operational_setting_audits_version_fkey
        FOREIGN KEY (group_key, to_revision)
        REFERENCES public.nw_platform_operational_setting_versions(group_key, revision)
        DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT skillforge_platform_operational_setting_audits_targe_77e1b60c02
        FOREIGN KEY (group_key, target_revision)
        REFERENCES public.nw_platform_operational_setting_versions(group_key, revision)
        DEFERRABLE INITIALLY DEFERRED
);

COMMENT ON TABLE public.nw_platform_operational_setting_audits
    IS '平台级运营配置审计账本，与版本和当前指针在同一事务提交';
COMMENT ON COLUMN public.nw_platform_operational_setting_audits.id IS '数据库生成的审计主键';
COMMENT ON COLUMN public.nw_platform_operational_setting_audits.group_key IS '发生变更的运营配置分组';
COMMENT ON COLUMN public.nw_platform_operational_setting_audits.operation IS '操作类型：update 或 rollback';
COMMENT ON COLUMN public.nw_platform_operational_setting_audits.from_revision IS '变更前版本';
COMMENT ON COLUMN public.nw_platform_operational_setting_audits.to_revision IS '本次生成的新版本';
COMMENT ON COLUMN public.nw_platform_operational_setting_audits.target_revision IS '回滚来源版本；普通更新为空';
COMMENT ON COLUMN public.nw_platform_operational_setting_audits.actor_id IS '已验证的平台成员 ID';
COMMENT ON COLUMN public.nw_platform_operational_setting_audits.payload IS '不含敏感值的审计附加信息';
COMMENT ON COLUMN public.nw_platform_operational_setting_audits.created_at IS '审计发生时间';

CREATE INDEX idx_skillforge_platform_operational_setting_audits_actor_time
    ON public.nw_platform_operational_setting_audits (actor_id, created_at DESC);
CREATE INDEX idx_skillforge_platform_operational_setting_audits_group_time
    ON public.nw_platform_operational_setting_audits (group_key, created_at DESC);

CREATE FUNCTION public.reject_platform_operational_setting_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION '% is append-only', TG_TABLE_NAME USING ERRCODE = '55000';
END;
$$;

CREATE TRIGGER skillforge_platform_operational_setting_versions_immutable
    BEFORE UPDATE OR DELETE ON public.nw_platform_operational_setting_versions
    FOR EACH ROW EXECUTE FUNCTION public.reject_platform_operational_setting_mutation();

CREATE TRIGGER skillforge_platform_operational_setting_audits_immutable
    BEFORE UPDATE OR DELETE ON public.nw_platform_operational_setting_audits
    FOR EACH ROW EXECUTE FUNCTION public.reject_platform_operational_setting_mutation();
