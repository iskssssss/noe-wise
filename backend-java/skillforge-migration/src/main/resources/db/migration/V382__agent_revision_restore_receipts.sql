CREATE TABLE public.nw_agent_revision_restore_receipts (
    tenant_id bigint NOT NULL,
    request_id uuid NOT NULL,
    actor_ref varchar(128) NOT NULL,
    agent_id bigint NOT NULL,
    revision integer NOT NULL CHECK (revision > 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, request_id)
);
ALTER TABLE public.nw_agent_revision_restore_receipts ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.nw_agent_revision_restore_receipts FORCE ROW LEVEL SECURITY;
CREATE POLICY agent_revision_restore_receipts_tenant ON public.nw_agent_revision_restore_receipts
    USING (tenant_id::text=current_setting('app.tenant_id',true))
    WITH CHECK (tenant_id::text=current_setting('app.tenant_id',true));
GRANT SELECT,INSERT ON public.nw_agent_revision_restore_receipts TO skillforge;
COMMENT ON TABLE public.nw_agent_revision_restore_receipts IS '历史修订恢复原请求回执，与草稿和挂载恢复同事务提交，不代表当前草稿状态';
