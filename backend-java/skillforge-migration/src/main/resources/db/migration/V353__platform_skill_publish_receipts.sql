CREATE TABLE public.nw_platform_skill_publish_receipts (
    request_id uuid PRIMARY KEY,
    actor_ref varchar(128) NOT NULL,
    request_fingerprint varchar(64) NOT NULL CHECK (request_fingerprint ~ '^[0-9a-f]{64}$'),
    status varchar(16) NOT NULL,
    response jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    completed_at timestamptz,
    CHECK ((status = 'PROCESSING' AND response IS NULL AND completed_at IS NULL) OR
        (status IN ('SUCCEEDED', 'REJECTED') AND response IS NOT NULL AND completed_at IS NOT NULL
         AND jsonb_typeof(response) = 'object' AND response->>'status' = status
         AND response->>'request_id' = request_id::text))
);
ALTER TABLE public.nw_platform_skill_publish_receipts ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.nw_platform_skill_publish_receipts FORCE ROW LEVEL SECURITY;
CREATE POLICY platform_skill_publish_receipts_admin ON public.nw_platform_skill_publish_receipts
    USING (current_setting('app.platform_admin', true) = 'true')
    WITH CHECK (current_setting('app.platform_admin', true) = 'true');
GRANT SELECT, INSERT, UPDATE ON public.nw_platform_skill_publish_receipts TO skillforge;
COMMENT ON TABLE public.nw_platform_skill_publish_receipts IS '平台技能保存发布原请求与回执，成功与业务共同提交';
