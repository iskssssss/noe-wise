-- V89: Knowledge revision 治理快照与 PUBLISHED KNOWLEDGE_SOURCE CapabilityVersion
-- 的不可变、租户同域关联。ready/publish 由 core-knowledge 与 Catalog bridge
-- 在同一 Spring 事务中完成；本迁移只增加数据库一致性守卫，不提供旧格式回退。

ALTER TABLE public.nw_knowledge_base_revisions
    ADD COLUMN capability_policy jsonb NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN capability_version_id bigint;

ALTER TABLE public.nw_knowledge_base_revisions
    ADD CONSTRAINT ck_knowledge_revision_capability_policy_object
        CHECK (jsonb_typeof(capability_policy) = 'object');

-- capability_versions.id 已是主键；该同租户唯一键让 revision FK 同时约束租户归属。
ALTER TABLE public.nw_capability_versions
    ADD CONSTRAINT uq_skillforge_capability_versions_id_tenant UNIQUE (id, tenant_id);

ALTER TABLE public.nw_knowledge_base_revisions
    ADD CONSTRAINT knowledge_revision_capability_version_fkey
        FOREIGN KEY (capability_version_id, tenant_id)
        REFERENCES public.nw_capability_versions (id, tenant_id)
        ON DELETE RESTRICT;

CREATE INDEX idx_knowledge_revision_capability_version
    ON public.nw_knowledge_base_revisions (tenant_id, capability_version_id);

CREATE FUNCTION public.validate_knowledge_revision_policy(policy jsonb)
RETURNS void
LANGUAGE plpgsql AS $$
BEGIN
    IF policy IS NULL OR jsonb_typeof(policy) IS DISTINCT FROM 'object' THEN
        RAISE EXCEPTION 'knowledge revision capability_policy must be a JSON object';
    END IF;
    IF (SELECT count(*) FROM jsonb_object_keys(policy)) <> 5
        OR NOT (policy ?& ARRAY[
            'model_invocable', 'user_invocable', 'allowed_operators',
            'freshness_policy', 'citation_required']) THEN
        RAISE EXCEPTION 'knowledge revision capability_policy must contain exactly five known fields';
    END IF;
    IF jsonb_typeof(policy -> 'model_invocable') IS DISTINCT FROM 'boolean'
        OR jsonb_typeof(policy -> 'user_invocable') IS DISTINCT FROM 'boolean'
        OR jsonb_typeof(policy -> 'citation_required') IS DISTINCT FROM 'boolean' THEN
        RAISE EXCEPTION 'knowledge revision capability policy invocation fields must be boolean';
    END IF;
    IF jsonb_typeof(policy -> 'freshness_policy') IS DISTINCT FROM 'string'
        OR btrim(policy ->> 'freshness_policy') = '' THEN
        RAISE EXCEPTION 'knowledge revision freshness_policy must be a non-empty string';
    END IF;
    IF jsonb_typeof(policy -> 'allowed_operators') IS DISTINCT FROM 'array' THEN
        RAISE EXCEPTION 'knowledge revision allowed_operators must be a JSON array';
    END IF;
    IF jsonb_array_length(policy -> 'allowed_operators') = 0 THEN
        RAISE EXCEPTION 'knowledge revision allowed_operators must be non-empty';
    END IF;
    IF EXISTS (
        SELECT 1
          FROM jsonb_array_elements(policy -> 'allowed_operators') AS operator(value)
         WHERE jsonb_typeof(operator.value) IS DISTINCT FROM 'string'
            OR btrim(operator.value #>> '{}') = '') THEN
        RAISE EXCEPTION 'knowledge revision allowed_operators must contain non-empty strings';
    END IF;
END;
$$;

CREATE FUNCTION public.validate_knowledge_revision_capability()
RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    version_tenant bigint;
    version_type varchar(32);
    version_status varchar(32);
BEGIN
    IF NEW.status IN ('ready', 'published', 'archived') THEN
        PERFORM public.validate_knowledge_revision_policy(NEW.capability_policy);
    END IF;
    IF NEW.capability_version_id IS NULL THEN
        IF NEW.status IN ('published', 'archived') THEN
            RAISE EXCEPTION 'published knowledge revision requires a capability version';
        END IF;
        RETURN NEW;
    END IF;

    SELECT tenant_id, capability_type, status
      INTO version_tenant, version_type, version_status
      FROM public.nw_capability_versions
     WHERE id = NEW.capability_version_id;
    IF NOT FOUND
        OR version_tenant IS DISTINCT FROM NEW.tenant_id
        OR version_type <> 'KNOWLEDGE_SOURCE'
        OR version_status <> 'PUBLISHED' THEN
        RAISE EXCEPTION 'knowledge revision requires a same-tenant PUBLISHED KNOWLEDGE_SOURCE version';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER knowledge_revision_capability_guard
    BEFORE INSERT OR UPDATE ON public.nw_knowledge_base_revisions
    FOR EACH ROW EXECUTE FUNCTION public.validate_knowledge_revision_capability();

CREATE FUNCTION public.guard_knowledge_revision_immutable()
RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    -- Draft -> Ready -> Published -> Archived is the only lifecycle. The
    -- capability FK may be attached once while the row is still Ready; after
    -- publication it is permanently pinned.
    IF OLD.status = 'draft' AND NEW.status NOT IN ('draft', 'ready') THEN
        RAISE EXCEPTION 'draft knowledge revision has invalid lifecycle transition';
    ELSIF OLD.status = 'ready' AND NEW.status NOT IN ('ready', 'published') THEN
        RAISE EXCEPTION 'ready knowledge revision has invalid lifecycle transition';
    ELSIF OLD.status = 'published' AND NEW.status NOT IN ('published', 'archived') THEN
        RAISE EXCEPTION 'published knowledge revision has invalid lifecycle transition';
    ELSIF OLD.status = 'archived' AND NEW.status <> 'archived' THEN
        RAISE EXCEPTION 'archived knowledge revision cannot change lifecycle';
    END IF;

    IF OLD.status IN ('ready', 'published', 'archived') AND (
        NEW.source_versions IS DISTINCT FROM OLD.source_versions
        OR NEW.capability_policy IS DISTINCT FROM OLD.capability_policy
    ) THEN
        RAISE EXCEPTION 'ready knowledge revision manifest and capability policy are immutable';
    END IF;

    IF OLD.status IN ('published', 'archived')
        AND NEW.capability_version_id IS DISTINCT FROM OLD.capability_version_id THEN
        RAISE EXCEPTION 'published knowledge revision capability association is immutable';
    END IF;

    -- A ready row can only gain its capability association; it cannot replace
    -- an existing association or attach one while moving to another state.
    IF OLD.status = 'ready'
        AND OLD.capability_version_id IS NOT NULL
        AND NEW.capability_version_id IS DISTINCT FROM OLD.capability_version_id THEN
        RAISE EXCEPTION 'ready knowledge revision capability association is immutable once attached';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER knowledge_revision_immutable_guard
    BEFORE UPDATE ON public.nw_knowledge_base_revisions
    FOR EACH ROW EXECUTE FUNCTION public.guard_knowledge_revision_immutable();

COMMENT ON COLUMN public.nw_knowledge_base_revisions.capability_policy IS
    'ready 时冻结的完整 Knowledge Source policy：model_invocable、user_invocable、allowed_operators、freshness_policy、citation_required；不从 KB 设置推导';
COMMENT ON COLUMN public.nw_knowledge_base_revisions.capability_version_id IS
    '发布事务内关联的同租户 PUBLISHED KNOWLEDGE_SOURCE capability_versions.id；发布后不可变';
