-- 四类能力共用稳定 Definition 与当前发布指针；运行快照继续引用精确版本。
ALTER TABLE public.nw_agent_capability_mounts ADD COLUMN workflow_installation_revision bigint
    CHECK (workflow_installation_revision IS NULL OR workflow_installation_revision > 0);

ALTER TABLE public.nw_capability_versions DROP CONSTRAINT uq_capability_version_key;
ALTER TABLE public.nw_capability_versions DROP CONSTRAINT uq_capability_version_hash;
CREATE UNIQUE INDEX uq_capability_released_version_key
    ON public.nw_capability_versions(definition_id, version_key) WHERE status <> 'DRAFT';

CREATE OR REPLACE FUNCTION public.validate_skill_default_version() RETURNS trigger
    LANGUAGE plpgsql AS $$
DECLARE
    version_type varchar(32);
    version_status varchar(32);
    version_tenant bigint;
BEGIN
    IF NEW.default_version_id IS NULL THEN RETURN NEW; END IF;
    SELECT capability_type, status, tenant_id INTO version_type, version_status, version_tenant
      FROM public.nw_capability_versions
     WHERE id = NEW.default_version_id AND definition_id = NEW.id;
    IF NOT FOUND OR version_type <> NEW.capability_type OR version_status <> 'PUBLISHED'
       OR version_tenant IS DISTINCT FROM NEW.tenant_id THEN
        RAISE EXCEPTION 'current publication must reference a published version of the same definition and owner';
    END IF;
    RETURN NEW;
END;
$$;

COMMENT ON COLUMN public.nw_capability_definitions.default_version_id IS
    '四类 Capability 的当前发布版本；新执行解析该指针，恢复继续使用冻结版本';

CREATE OR REPLACE FUNCTION public.guard_published_capability_definition() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.status IN ('PUBLISHED','DEPRECATED','ARCHIVED') AND (
        NEW.tenant_id IS DISTINCT FROM OLD.tenant_id
        OR NEW.capability_type IS DISTINCT FROM OLD.capability_type
        OR NEW.capability_key IS DISTINCT FROM OLD.capability_key
        OR NEW.trust_level IS DISTINCT FROM OLD.trust_level
        OR NEW.created_at IS DISTINCT FROM OLD.created_at) THEN
        RAISE EXCEPTION 'published capability definition identity is immutable';
    END IF;
    IF OLD.status='DRAFT' AND NEW.status NOT IN ('DRAFT','PUBLISHED')
       OR OLD.status='PUBLISHED' AND NEW.status NOT IN ('PUBLISHED','DEPRECATED','ARCHIVED')
       OR OLD.status='DEPRECATED' AND NEW.status NOT IN ('DEPRECATED','ARCHIVED')
       OR OLD.status='ARCHIVED' AND NEW.status<>'ARCHIVED' THEN
        RAISE EXCEPTION 'capability definition has invalid lifecycle transition';
    END IF;
    RETURN NEW;
END;
$$;

-- 工作草稿身份稳定；发布结果属于 capability_versions，工作草稿可继续保存与恢复。
CREATE OR REPLACE FUNCTION public.guard_skill_editing_draft() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'UPDATE' AND (NEW.id IS DISTINCT FROM OLD.id
        OR NEW.definition_id IS DISTINCT FROM OLD.definition_id
        OR NEW.tenant_id IS DISTINCT FROM OLD.tenant_id) THEN
        RAISE EXCEPTION 'skill editing draft identity is immutable';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM public.nw_capability_definitions d WHERE d.id = NEW.definition_id
        AND d.capability_type = 'SKILL' AND d.tenant_id IS NOT DISTINCT FROM NEW.tenant_id) THEN
        RAISE EXCEPTION 'skill editing draft definition scope mismatch';
    END IF;
    IF NEW.base_version_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM public.nw_capability_versions v
        WHERE v.id = NEW.base_version_id AND v.definition_id = NEW.definition_id
        AND v.tenant_id IS NOT DISTINCT FROM NEW.tenant_id) THEN
        RAISE EXCEPTION 'skill editing draft base version mismatch';
    END IF;
    IF NEW.published_version_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM public.nw_capability_versions v
        WHERE v.id = NEW.published_version_id AND v.definition_id = NEW.definition_id
        AND v.tenant_id IS NOT DISTINCT FROM NEW.tenant_id AND v.status = 'PUBLISHED') THEN
        RAISE EXCEPTION 'skill editing draft publication mismatch';
    END IF;
    RETURN NEW;
END;
$$;
