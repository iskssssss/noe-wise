-- 编辑态与 Runtime 可挂载版本分离；一个定义至多一个未发布编辑草稿。
CREATE TABLE public.nw_skill_editing_drafts (
    id bigint PRIMARY KEY,
    definition_id bigint NOT NULL REFERENCES public.nw_capability_definitions(id),
    tenant_id bigint REFERENCES public.nw_tenants(id),
    base_version_id bigint REFERENCES public.nw_capability_versions(id),
    revision bigint NOT NULL DEFAULT 1 CHECK (revision > 0),
    content jsonb NOT NULL CHECK (jsonb_typeof(content) = 'object'),
    published_version_id bigint REFERENCES public.nw_capability_versions(id),
    published_definition_revision bigint,
    updated_at timestamptz NOT NULL DEFAULT now(),
    CHECK ((published_version_id IS NULL) = (published_definition_revision IS NULL))
);
CREATE UNIQUE INDEX skillforge_skill_editing_drafts_one_active
    ON public.nw_skill_editing_drafts(definition_id) WHERE published_version_id IS NULL;
ALTER TABLE public.nw_skill_editing_drafts ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.nw_skill_editing_drafts FORCE ROW LEVEL SECURITY;
CREATE POLICY skillforge_skill_editing_drafts_owner ON public.nw_skill_editing_drafts
    USING (
        tenant_id::text = COALESCE(current_setting('app.tenant_id', true), '')
        OR (tenant_id IS NULL AND current_setting('app.platform_admin', true) = 'true')
    ) WITH CHECK (
        tenant_id::text = COALESCE(current_setting('app.tenant_id', true), '')
        OR (tenant_id IS NULL AND current_setting('app.platform_admin', true) = 'true')
    );

CREATE FUNCTION public.guard_skill_editing_draft() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'UPDATE' THEN
        IF OLD.published_version_id IS NOT NULL THEN
            RAISE EXCEPTION 'published skill editing draft is immutable';
        END IF;
        IF NEW.id IS DISTINCT FROM OLD.id OR NEW.definition_id IS DISTINCT FROM OLD.definition_id
            OR NEW.tenant_id IS DISTINCT FROM OLD.tenant_id OR NEW.base_version_id IS DISTINCT FROM OLD.base_version_id THEN
            RAISE EXCEPTION 'skill editing draft identity is immutable';
        END IF;
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
CREATE TRIGGER skill_editing_draft_guard BEFORE INSERT OR UPDATE ON public.nw_skill_editing_drafts
    FOR EACH ROW EXECUTE FUNCTION public.guard_skill_editing_draft();
COMMENT ON TABLE public.nw_skill_editing_drafts IS '技能维护台编辑草稿与发布回执；允许未完成内容，不可挂载，不对租户公开平台草稿。';
