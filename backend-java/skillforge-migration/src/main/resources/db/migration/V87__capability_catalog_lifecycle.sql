-- V87: Capability Catalog 生命周期约束。
-- V86 建立权威表；本迁移补充发布 CAS 所依赖的身份守卫和
-- 数据库注释。平台/租户事务上下文由 core-shared Catalog service 显式绑定。

CREATE OR REPLACE FUNCTION public.guard_published_capability_version() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    -- Draft 可由明确的 revision/If-Match 更新路径编辑；一旦进入任一 released
    -- 状态，身份、正文、哈希和创建时间永久不可更新。
    IF OLD.status IN ('PUBLISHED', 'DEPRECATED', 'ARCHIVED') AND (
        NEW.definition_id IS DISTINCT FROM OLD.definition_id
        OR NEW.tenant_id IS DISTINCT FROM OLD.tenant_id
        OR NEW.capability_type IS DISTINCT FROM OLD.capability_type
        OR NEW.version_key IS DISTINCT FROM OLD.version_key
        OR NEW.name IS DISTINCT FROM OLD.name
        OR NEW.description IS DISTINCT FROM OLD.description
        OR NEW.trust_level IS DISTINCT FROM OLD.trust_level
        OR NEW.descriptor IS DISTINCT FROM OLD.descriptor
        OR NEW.content_hash IS DISTINCT FROM OLD.content_hash
        OR NEW.created_at IS DISTINCT FROM OLD.created_at
    ) THEN
        RAISE EXCEPTION 'published capability version content is immutable';
    END IF;

    -- 四态只允许沿生命周期向前推进：DRAFT -> PUBLISHED ->
    -- DEPRECATED -> ARCHIVED；PUBLISHED 也可直接 ARCHIVED。
    IF OLD.status = 'DRAFT' AND NEW.status NOT IN ('DRAFT', 'PUBLISHED') THEN
        RAISE EXCEPTION 'draft capability version has invalid lifecycle transition';
    ELSIF OLD.status = 'PUBLISHED' AND NEW.status NOT IN
            ('PUBLISHED', 'DEPRECATED', 'ARCHIVED') THEN
        RAISE EXCEPTION 'published capability version has invalid lifecycle transition';
    ELSIF OLD.status = 'DEPRECATED' AND NEW.status NOT IN ('DEPRECATED', 'ARCHIVED') THEN
        RAISE EXCEPTION 'deprecated capability version has invalid lifecycle transition';
    ELSIF OLD.status = 'ARCHIVED' AND NEW.status <> 'ARCHIVED' THEN
        RAISE EXCEPTION 'archived capability version cannot change lifecycle';
    END IF;

    IF OLD.status = 'DRAFT' AND NEW.status = 'PUBLISHED'
        AND NOT EXISTS (
            SELECT 1 FROM public.nw_capability_definitions definition
            WHERE definition.id = NEW.definition_id
              AND definition.status = 'PUBLISHED') THEN
        RAISE EXCEPTION 'capability definition must be published before its version';
    END IF;
    RETURN NEW;
END;
$$;

CREATE OR REPLACE FUNCTION public.guard_published_capability_definition() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    -- Definition 是稳定身份；任一 released 状态后不能改 key、类型、归属或
    -- 展示元数据。
    IF OLD.status IN ('PUBLISHED', 'DEPRECATED', 'ARCHIVED') AND (
        NEW.tenant_id IS DISTINCT FROM OLD.tenant_id
        OR NEW.capability_type IS DISTINCT FROM OLD.capability_type
        OR NEW.capability_key IS DISTINCT FROM OLD.capability_key
        OR NEW.name IS DISTINCT FROM OLD.name
        OR NEW.description IS DISTINCT FROM OLD.description
        OR NEW.trust_level IS DISTINCT FROM OLD.trust_level
        OR NEW.created_at IS DISTINCT FROM OLD.created_at
    ) THEN
        RAISE EXCEPTION 'published capability definition identity is immutable';
    END IF;

    -- Definition 与版本使用同一组四态的最小单向生命周期；不存在回到 DRAFT
    -- 或从 DEPRECATED/ARCHIVED 恢复发布的旁路。
    IF OLD.status = 'DRAFT' AND NEW.status NOT IN ('DRAFT', 'PUBLISHED') THEN
        RAISE EXCEPTION 'draft capability definition has invalid lifecycle transition';
    ELSIF OLD.status = 'PUBLISHED' AND NEW.status NOT IN
            ('PUBLISHED', 'DEPRECATED', 'ARCHIVED') THEN
        RAISE EXCEPTION 'published capability definition has invalid lifecycle transition';
    ELSIF OLD.status = 'DEPRECATED' AND NEW.status NOT IN ('DEPRECATED', 'ARCHIVED') THEN
        RAISE EXCEPTION 'deprecated capability definition has invalid lifecycle transition';
    ELSIF OLD.status = 'ARCHIVED' AND NEW.status <> 'ARCHIVED' THEN
        RAISE EXCEPTION 'archived capability definition cannot change lifecycle';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER capability_definition_immutable
    BEFORE UPDATE ON public.nw_capability_definitions
    FOR EACH ROW EXECUTE FUNCTION public.guard_published_capability_definition();

COMMENT ON COLUMN public.nw_capability_definitions.revision IS
    '发布 CAS 版本；每次发布成功由 expected If-Match revision 原子递增。';
COMMENT ON COLUMN public.nw_capability_versions.status IS
    'DRAFT 可通过 Definition revision/If-Match 编辑；PUBLISHED/DEPRECATED/ARCHIVED 正文与身份不可变，状态只可单向推进。';
COMMENT ON COLUMN public.nw_tool_deployments.revision IS
    '启停 CAS 版本；每次状态改变必须携带 expected If-Match revision。';
