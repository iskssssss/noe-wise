-- V88: Workflow Producer 发布物与 Catalog WORKFLOW CapabilityVersion 的原子关联。
--
-- 该迁移故意不为历史 DefinitionVersion 生成治理默认值或旧格式回退。预发布基线
-- 若存在未关联的旧发布物，迁移直接失败，要求以当前 Builder producer 重新发布。

ALTER TABLE public.nw_workflow_definition_versions
    ADD COLUMN capability_version_id bigint;

ALTER TABLE public.nw_workflow_definition_versions
    ADD CONSTRAINT fk_skillforge_workflow_definition_versions_capability_version
    FOREIGN KEY (capability_version_id)
    REFERENCES public.nw_capability_versions(id)
    ON DELETE RESTRICT;

CREATE INDEX idx_skillforge_workflow_definition_versions_capability_version
    ON public.nw_workflow_definition_versions (capability_version_id);

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM public.nw_workflow_definition_versions
        WHERE capability_version_id IS NULL
    ) THEN
        RAISE EXCEPTION
            'V88 refuses unassociated Workflow DefinitionVersion; republish through Builder producer';
    END IF;
    ALTER TABLE public.nw_workflow_definition_versions
        ALTER COLUMN capability_version_id SET NOT NULL;
END
$$;

CREATE FUNCTION public.validate_workflow_definition_capability_version()
RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    catalog_tenant bigint;
    catalog_type varchar(32);
    catalog_status varchar(32);
BEGIN
    SELECT tenant_id, capability_type, status
      INTO catalog_tenant, catalog_type, catalog_status
      FROM public.nw_capability_versions
     WHERE id = NEW.capability_version_id;

    IF NOT FOUND
        OR catalog_type <> 'WORKFLOW'
        OR catalog_status <> 'PUBLISHED'
        OR catalog_tenant IS DISTINCT FROM NEW.tenant_id THEN
        RAISE EXCEPTION
            'Workflow DefinitionVersion requires a same-tenant published WORKFLOW CapabilityVersion';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER workflow_definition_capability_version_identity
    BEFORE INSERT OR UPDATE ON public.nw_workflow_definition_versions
    FOR EACH ROW EXECUTE FUNCTION public.validate_workflow_definition_capability_version();

CREATE FUNCTION public.guard_workflow_definition_version_immutable()
RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.container_id IS DISTINCT FROM NEW.container_id
        OR OLD.tenant_id IS DISTINCT FROM NEW.tenant_id
        OR OLD.definition_version IS DISTINCT FROM NEW.definition_version
        OR OLD.source_draft_id IS DISTINCT FROM NEW.source_draft_id
        OR OLD.capability_version_id IS DISTINCT FROM NEW.capability_version_id
        OR OLD.definition IS DISTINCT FROM NEW.definition
        OR OLD.created_at IS DISTINCT FROM NEW.created_at THEN
        RAISE EXCEPTION 'published Workflow DefinitionVersion is immutable';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER workflow_definition_version_immutable
    BEFORE UPDATE ON public.nw_workflow_definition_versions
    FOR EACH ROW EXECUTE FUNCTION public.guard_workflow_definition_version_immutable();

CREATE FUNCTION public.guard_workflow_definition_version_delete()
RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'published Workflow DefinitionVersion cannot be deleted';
END;
$$;

CREATE TRIGGER workflow_definition_version_delete_guard
    BEFORE DELETE ON public.nw_workflow_definition_versions
    FOR EACH ROW EXECUTE FUNCTION public.guard_workflow_definition_version_delete();

COMMENT ON COLUMN public.nw_workflow_definition_versions.capability_version_id IS
    '不可变 Builder Producer 关联的已发布 WORKFLOW capability_versions.id；发布与 DefinitionVersion 同事务提交';
