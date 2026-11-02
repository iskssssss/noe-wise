-- 令牌轮换回执与凭据更新同事务提交，不记录明文令牌。
CREATE TABLE public.nw_channel_token_receipts (
    tenant_id bigint NOT NULL,
    request_id uuid NOT NULL,
    actor_ref varchar(64) NOT NULL,
    channel_id bigint NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, request_id),
    FOREIGN KEY (tenant_id, channel_id) REFERENCES public.nw_channels(tenant_id, id) ON DELETE CASCADE
);
CREATE INDEX idx_channel_token_receipts_channel ON public.nw_channel_token_receipts (tenant_id, channel_id);
ALTER TABLE public.nw_channel_token_receipts ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.nw_channel_token_receipts FORCE ROW LEVEL SECURITY;
CREATE POLICY channel_token_receipts_tenant ON public.nw_channel_token_receipts
    USING (tenant_id::text = current_setting('app.tenant_id', true))
    WITH CHECK (tenant_id::text = current_setting('app.tenant_id', true));
GRANT SELECT, INSERT ON public.nw_channel_token_receipts TO skillforge;
COMMENT ON TABLE public.nw_channel_token_receipts IS '渠道令牌轮换原请求的成功回执，不含明文令牌';
COMMENT ON COLUMN public.nw_channel_token_receipts.tenant_id IS '所属租户';
COMMENT ON COLUMN public.nw_channel_token_receipts.request_id IS '客户端稳定请求标识';
COMMENT ON COLUMN public.nw_channel_token_receipts.actor_ref IS '原操作的机构成员身份';
COMMENT ON COLUMN public.nw_channel_token_receipts.channel_id IS '原操作的渠道标识';
COMMENT ON COLUMN public.nw_channel_token_receipts.created_at IS '令牌轮换成功时间';
