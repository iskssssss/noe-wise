-- Workflow 包资源权威入库，磁盘只作为缓存。

CREATE TABLE public.nw_workflow_assets (
    id          bigint NOT NULL,
    tenant_id   bigint,
    workflow_id character varying(128) NOT NULL,
    asset_name  character varying(256) NOT NULL,
    content     bytea NOT NULL,
    created_at  timestamp with time zone DEFAULT now() NOT NULL,
    updated_at  timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT skillforge_workflow_assets_pkey PRIMARY KEY (id)
);

CREATE INDEX idx_skillforge_workflow_assets_workflow
    ON public.nw_workflow_assets USING btree (workflow_id, asset_name);

ALTER TABLE public.nw_workflow_assets FORCE ROW LEVEL SECURITY;

CREATE POLICY skillforge_workflow_assets_tenant_isolation ON public.nw_workflow_assets
    USING (tenant_id IS NULL
        OR (tenant_id)::text = COALESCE(current_setting('app.tenant_id'::text, true), ''::text))
    WITH CHECK (tenant_id IS NULL
        OR (tenant_id)::text = COALESCE(current_setting('app.tenant_id'::text, true), ''::text));

COMMENT ON TABLE public.nw_workflow_assets IS 'Workflow 包资源；数据库为权威来源，磁盘为缓存；平台级资源 tenant_id 为 NULL';
COMMENT ON COLUMN public.nw_workflow_assets.id IS '主键雪花 ID';
COMMENT ON COLUMN public.nw_workflow_assets.tenant_id IS '所属租户 ID；平台级资源为 NULL';
COMMENT ON COLUMN public.nw_workflow_assets.workflow_id IS 'Workflow 业务 ID';
COMMENT ON COLUMN public.nw_workflow_assets.asset_name IS '资源文件名';
COMMENT ON COLUMN public.nw_workflow_assets.content IS '资源字节内容';
COMMENT ON COLUMN public.nw_workflow_assets.created_at IS '创建时间';
COMMENT ON COLUMN public.nw_workflow_assets.updated_at IS '更新时间';
