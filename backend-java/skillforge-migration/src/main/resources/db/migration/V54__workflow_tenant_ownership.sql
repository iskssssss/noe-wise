ALTER TABLE public.nw_workflow_templates
    ADD COLUMN tenant_id bigint;

CREATE INDEX idx_wt_tenant ON public.nw_workflow_templates USING btree (tenant_id);

COMMENT ON COLUMN public.nw_workflow_templates.tenant_id IS
    '所属租户 ID；NULL 表示平台模板';
