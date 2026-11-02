-- 工单领取与解决的原命令回执，与状态更新在同一事务提交。
CREATE TABLE public.nw_handoff_action_receipts (
    tenant_id bigint NOT NULL,
    command_id uuid NOT NULL,
    ticket_id varchar(64) NOT NULL,
    operator_id bigint NOT NULL,
    action varchar(16) NOT NULL CHECK (action IN ('accept', 'resolve')),
    expected_revision bigint NOT NULL CHECK (expected_revision > 0),
    fingerprint varchar(64) NOT NULL CHECK (fingerprint ~ '^[0-9a-f]{64}$'),
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, command_id),
    FOREIGN KEY (tenant_id, ticket_id) REFERENCES public.nw_human_handoff_tickets(tenant_id, ticket_id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, operator_id) REFERENCES public.nw_tenant_members(tenant_id, id)
);
ALTER TABLE public.nw_handoff_action_receipts ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.nw_handoff_action_receipts FORCE ROW LEVEL SECURITY;
CREATE POLICY handoff_action_tenant ON public.nw_handoff_action_receipts
    USING (tenant_id::text = current_setting('app.tenant_id', true))
    WITH CHECK (tenant_id::text = current_setting('app.tenant_id', true));
GRANT SELECT, INSERT ON public.nw_handoff_action_receipts TO skillforge;
COMMENT ON TABLE public.nw_handoff_action_receipts IS '工单状态变更的原命令成功回执；与状态变更原子提交';
COMMENT ON COLUMN public.nw_handoff_action_receipts.command_id IS '客户端稳定命令标识';
COMMENT ON COLUMN public.nw_handoff_action_receipts.fingerprint IS '租户、成员、工单、动作、修订及解决说明的 SHA-256';
COMMENT ON COLUMN public.nw_handoff_action_receipts.tenant_id IS '工单所属租户';
COMMENT ON COLUMN public.nw_handoff_action_receipts.ticket_id IS '原工单标识';
COMMENT ON COLUMN public.nw_handoff_action_receipts.operator_id IS '原操作的认证成员';
COMMENT ON COLUMN public.nw_handoff_action_receipts.action IS 'accept 领取或 resolve 解决';
COMMENT ON COLUMN public.nw_handoff_action_receipts.expected_revision IS '原命令提交时的工单修订';
COMMENT ON COLUMN public.nw_handoff_action_receipts.created_at IS '原命令成功时间';
