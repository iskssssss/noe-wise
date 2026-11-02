CREATE TABLE public.nw_platform_model_create_receipts (
    request_id uuid PRIMARY KEY,
    actor_ref varchar(128) NOT NULL,
    request_fingerprint varchar(64) NOT NULL CHECK (request_fingerprint ~ '^[0-9a-f]{64}$'),
    model_id bigint,
    created_at timestamptz NOT NULL DEFAULT now()
);
ALTER TABLE public.nw_platform_model_create_receipts ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.nw_platform_model_create_receipts FORCE ROW LEVEL SECURITY;
CREATE POLICY platform_model_create_receipts_admin ON public.nw_platform_model_create_receipts
    USING (current_setting('app.platform_admin', true) = 'true')
    WITH CHECK (current_setting('app.platform_admin', true) = 'true');
GRANT SELECT, INSERT, UPDATE ON public.nw_platform_model_create_receipts TO skillforge;
COMMENT ON TABLE public.nw_platform_model_create_receipts IS '平台模型创建原请求摘要及结果，与模型草稿同事务提交';
