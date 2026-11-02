ALTER TABLE public.nw_workflow_assets
    ADD CONSTRAINT uq_skillforge_workflow_assets_workflow_asset UNIQUE (workflow_id, asset_name);

COMMENT ON CONSTRAINT uq_skillforge_workflow_assets_workflow_asset ON public.nw_workflow_assets IS
    '同一 Workflow 的资产名称唯一';
