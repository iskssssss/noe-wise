-- 保留原草稿操作的窄身份，不复制团队配置正文。
CREATE TABLE public.nw_team_draft_receipts (
    tenant_id bigint NOT NULL,
    request_id uuid NOT NULL,
    actor_ref varchar(128) NOT NULL,
    team_id varchar(100) NOT NULL,
    revision bigint NOT NULL CHECK (revision > 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, request_id)
);
ALTER TABLE public.nw_team_draft_receipts ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.nw_team_draft_receipts FORCE ROW LEVEL SECURITY;
CREATE POLICY team_draft_receipts_tenant ON public.nw_team_draft_receipts
    USING (tenant_id::text = current_setting('app.tenant_id', true))
    WITH CHECK (tenant_id::text = current_setting('app.tenant_id', true));
GRANT SELECT, INSERT ON public.nw_team_draft_receipts TO skillforge;
COMMENT ON TABLE public.nw_team_draft_receipts IS '团队草稿原请求的窄回执，保存与团队写入同事务';
