-- V77: Knowledge Base 不可变 revision、ready/published 状态与 active 指针。
CREATE TABLE public.nw_knowledge_base_revisions (
    id              bigint       NOT NULL,
    tenant_id       bigint       NOT NULL,
    kb_id           bigint       NOT NULL,
    revision        bigint       NOT NULL,
    status          varchar(16)  NOT NULL DEFAULT 'draft',
    source_versions jsonb        NOT NULL DEFAULT '[]'::jsonb,
    created_at      timestamptz  NOT NULL DEFAULT now(),
    ready_at        timestamptz,
    published_at    timestamptz,
    CONSTRAINT skillforge_knowledge_base_revisions_pkey PRIMARY KEY (id),
    CONSTRAINT uq_skillforge_knowledge_base_revisions_revision UNIQUE (tenant_id, kb_id, revision),
    CONSTRAINT ck_skillforge_knowledge_base_revisions_status CHECK (
        status IN ('draft', 'ready', 'published', 'archived')),
    CONSTRAINT ck_skillforge_knowledge_base_revisions_revision CHECK (revision > 0),
    CONSTRAINT ck_skillforge_knowledge_base_revisions_source_versions_array CHECK (
        jsonb_typeof(source_versions) = 'array')
);

CREATE UNIQUE INDEX uq_skillforge_knowledge_base_revisions_published
    ON public.nw_knowledge_base_revisions (tenant_id, kb_id)
    WHERE status = 'published';
CREATE INDEX idx_skillforge_knowledge_base_revisions_tenant_kb
    ON public.nw_knowledge_base_revisions (tenant_id, kb_id, revision DESC);

ALTER TABLE public.nw_knowledge_bases
    ADD COLUMN active_revision_id bigint;

ALTER TABLE public.nw_knowledge_base_revisions ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.nw_knowledge_base_revisions FORCE ROW LEVEL SECURITY;
CREATE POLICY skillforge_knowledge_base_revisions_tenant_isolation
    ON public.nw_knowledge_base_revisions
    USING ((tenant_id)::text = COALESCE(current_setting('app.tenant_id', true), ''))
    WITH CHECK ((tenant_id)::text = COALESCE(current_setting('app.tenant_id', true), ''));

COMMENT ON TABLE public.nw_knowledge_base_revisions IS
    'Knowledge Base 不可变内容 revision；ready 只能通过租户/知识库/版本 CAS 发布为 active';
COMMENT ON COLUMN public.nw_knowledge_base_revisions.source_versions IS
    '该 revision 的不可变 source_version manifest；只允许当前租户知识库已有版本';
COMMENT ON COLUMN public.nw_knowledge_bases.active_revision_id IS
    '当前 active published revision；发布事务原子切换，null 表示尚未发布';
