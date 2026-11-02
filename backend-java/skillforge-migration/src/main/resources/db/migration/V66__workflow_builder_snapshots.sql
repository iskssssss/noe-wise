-- V66: Builder 布局与视口属于设计态 Snapshot，必须引用可变 draft revision，不能写入运行定义。

CREATE TABLE public.nw_workflow_builder_snapshots (
    id             bigint      NOT NULL,
    tenant_id      bigint      NOT NULL,
    container_id   bigint      NOT NULL REFERENCES public.nw_workflow_containers(id) ON DELETE CASCADE,
    draft_revision bigint      NOT NULL,
    layout         jsonb       NOT NULL DEFAULT '{}'::jsonb,
    viewport       jsonb       NOT NULL DEFAULT '{}'::jsonb,
    created_at     timestamptz NOT NULL DEFAULT now(),
    updated_at     timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT skillforge_workflow_builder_snapshots_pkey PRIMARY KEY (id),
    CONSTRAINT uq_skillforge_workflow_builder_snapshots_draft UNIQUE (container_id, draft_revision),
    CONSTRAINT ck_skillforge_workflow_builder_snapshots_revision CHECK (draft_revision > 0)
);

CREATE INDEX idx_skillforge_workflow_builder_snapshots_tenant_container
    ON public.nw_workflow_builder_snapshots (tenant_id, container_id, draft_revision DESC);

ALTER TABLE public.nw_workflow_builder_snapshots ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.nw_workflow_builder_snapshots FORCE ROW LEVEL SECURITY;
CREATE POLICY skillforge_workflow_builder_snapshots_tenant_isolation ON public.nw_workflow_builder_snapshots
    USING ((tenant_id)::text = COALESCE(current_setting('app.tenant_id', true), ''))
    WITH CHECK ((tenant_id)::text = COALESCE(current_setting('app.tenant_id', true), ''));

COMMENT ON TABLE public.nw_workflow_builder_snapshots IS
    'Builder 设计态布局与视口；每行绑定 WorkflowContainer 的一个 draft revision，不属于运行定义';
COMMENT ON COLUMN public.nw_workflow_builder_snapshots.layout IS
    '节点画布位置快照 JSON；仅前端编辑布局使用，运行时不得读取';
COMMENT ON COLUMN public.nw_workflow_builder_snapshots.viewport IS
    '画布视口快照 JSON（zoom/pan）；仅前端编辑布局使用，运行时不得读取';
