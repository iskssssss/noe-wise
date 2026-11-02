CREATE TABLE public.nw_skill_create_receipts (
    request_id uuid PRIMARY KEY,
    tenant_id bigint,
    actor_ref varchar(128) NOT NULL,
    request_fingerprint varchar(64) NOT NULL CHECK (request_fingerprint ~ '^[0-9a-f]{64}$'),
    definition_id bigint,
    draft_id bigint,
    draft_revision bigint,
    created_at timestamptz NOT NULL DEFAULT now()
);
ALTER TABLE public.nw_skill_create_receipts ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.nw_skill_create_receipts FORCE ROW LEVEL SECURITY;
CREATE POLICY skill_create_receipts_scope ON public.nw_skill_create_receipts
    USING ((tenant_id IS NULL AND current_setting('app.platform_admin', true) = 'true') OR
           (tenant_id IS NOT NULL AND tenant_id::text = current_setting('app.tenant_id', true)))
    WITH CHECK ((tenant_id IS NULL AND current_setting('app.platform_admin', true) = 'true') OR
                (tenant_id IS NOT NULL AND tenant_id::text = current_setting('app.tenant_id', true)));
GRANT SELECT, INSERT, UPDATE ON public.nw_skill_create_receipts TO skillforge;
COMMENT ON TABLE public.nw_skill_create_receipts IS '技能及初始草稿创建的原请求与结果，与业务对象同事务提交';
