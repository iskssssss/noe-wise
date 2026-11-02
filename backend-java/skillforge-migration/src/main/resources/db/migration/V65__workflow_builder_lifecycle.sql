-- V65: Workflow Builder 的容器、可并发草稿与不可变发布版本。
-- Builder 可变设计态存于独立草稿修订，
-- 发布后才生成独立的 DefinitionVersion；每次草稿保存以 revision CAS 防止静默覆盖。

CREATE TABLE public.nw_workflow_containers (
    id                           bigint       NOT NULL,
    tenant_id                    bigint       NOT NULL,
    workflow_id                  varchar(128) NOT NULL,
    status                       varchar(16)  NOT NULL DEFAULT 'draft',
    current_draft_revision       bigint       NOT NULL DEFAULT 1,
    active_definition_version_id bigint,
    created_at                   timestamptz  NOT NULL DEFAULT now(),
    updated_at                   timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT skillforge_workflow_containers_pkey PRIMARY KEY (id),
    CONSTRAINT uq_skillforge_workflow_containers_tenant_workflow UNIQUE (tenant_id, workflow_id),
    CONSTRAINT ck_skillforge_workflow_containers_status CHECK (status IN ('draft', 'published', 'archived')),
    CONSTRAINT ck_skillforge_workflow_containers_draft_revision CHECK (current_draft_revision > 0)
);

CREATE TABLE public.nw_workflow_draft_revisions (
    id             bigint      NOT NULL,
    container_id   bigint      NOT NULL REFERENCES public.nw_workflow_containers(id) ON DELETE CASCADE,
    tenant_id      bigint      NOT NULL,
    revision       bigint      NOT NULL,
    definition     jsonb       NOT NULL,
    created_at     timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT skillforge_workflow_draft_revisions_pkey PRIMARY KEY (id),
    CONSTRAINT uq_skillforge_workflow_draft_revisions_container_revision UNIQUE (container_id, revision),
    CONSTRAINT ck_skillforge_workflow_draft_revisions_revision CHECK (revision > 0)
);

CREATE TABLE public.nw_workflow_definition_versions (
    id                 bigint      NOT NULL,
    container_id       bigint      NOT NULL REFERENCES public.nw_workflow_containers(id) ON DELETE CASCADE,
    tenant_id          bigint      NOT NULL,
    definition_version bigint      NOT NULL,
    source_draft_id    bigint      NOT NULL REFERENCES public.nw_workflow_draft_revisions(id) ON DELETE RESTRICT,
    definition         jsonb       NOT NULL,
    created_at         timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT skillforge_workflow_definition_versions_pkey PRIMARY KEY (id),
    CONSTRAINT uq_skillforge_workflow_definition_versions_container_version UNIQUE (container_id, definition_version),
    CONSTRAINT uq_skillforge_workflow_definition_versions_source_draft UNIQUE (source_draft_id),
    CONSTRAINT ck_skillforge_workflow_definition_versions_version CHECK (definition_version > 0)
);

CREATE INDEX idx_skillforge_workflow_draft_revisions_tenant_container
    ON public.nw_workflow_draft_revisions (tenant_id, container_id, revision DESC);
CREATE INDEX idx_skillforge_workflow_definition_versions_tenant_container
    ON public.nw_workflow_definition_versions (tenant_id, container_id, definition_version DESC);

ALTER TABLE public.nw_workflow_containers ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.nw_workflow_containers FORCE ROW LEVEL SECURITY;
CREATE POLICY skillforge_workflow_containers_tenant_isolation ON public.nw_workflow_containers
    USING ((tenant_id)::text = COALESCE(current_setting('app.tenant_id', true), ''))
    WITH CHECK ((tenant_id)::text = COALESCE(current_setting('app.tenant_id', true), ''));

ALTER TABLE public.nw_workflow_draft_revisions ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.nw_workflow_draft_revisions FORCE ROW LEVEL SECURITY;
CREATE POLICY skillforge_workflow_draft_revisions_tenant_isolation ON public.nw_workflow_draft_revisions
    USING ((tenant_id)::text = COALESCE(current_setting('app.tenant_id', true), ''))
    WITH CHECK ((tenant_id)::text = COALESCE(current_setting('app.tenant_id', true), ''));

ALTER TABLE public.nw_workflow_definition_versions ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.nw_workflow_definition_versions FORCE ROW LEVEL SECURITY;
CREATE POLICY skillforge_workflow_definition_versions_tenant_isolation ON public.nw_workflow_definition_versions
    USING ((tenant_id)::text = COALESCE(current_setting('app.tenant_id', true), ''))
    WITH CHECK ((tenant_id)::text = COALESCE(current_setting('app.tenant_id', true), ''));

COMMENT ON TABLE public.nw_workflow_containers IS
    'Builder 工作流容器：保存当前草稿 revision 和已发布 DefinitionVersion 指针；不含可变运行定义';
COMMENT ON TABLE public.nw_workflow_draft_revisions IS
    'Builder 不可变草稿快照：由 If-Match revision CAS 线性追加，允许存在未解决校验问题';
COMMENT ON TABLE public.nw_workflow_definition_versions IS
    '发布后的不可变 Workflow DefinitionVersion；仅可由已通过验证的草稿生成';
