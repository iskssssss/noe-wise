-- 保存原安装请求的窄回执，不复制配置和绑定。
CREATE TABLE public.nw_workflow_install_receipts (
    tenant_id bigint NOT NULL,
    request_id uuid NOT NULL,
    actor_ref varchar(128) NOT NULL,
    workflow_id varchar(128) NOT NULL,
    instance_id varchar(160) NOT NULL,
    revision bigint NOT NULL CHECK (revision > 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, request_id)
);
ALTER TABLE public.nw_workflow_install_receipts ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.nw_workflow_install_receipts FORCE ROW LEVEL SECURITY;
CREATE POLICY workflow_install_receipts_tenant ON public.nw_workflow_install_receipts
    USING (tenant_id::text = current_setting('app.tenant_id', true))
    WITH CHECK (tenant_id::text = current_setting('app.tenant_id', true));
GRANT SELECT, INSERT ON public.nw_workflow_install_receipts TO skillforge;
