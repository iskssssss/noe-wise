CREATE TABLE public.nw_knowledge_upload_receipts (
    tenant_id bigint NOT NULL,
    request_id uuid NOT NULL,
    kb_id bigint NOT NULL,
    actor_ref varchar(128) NOT NULL,
    request_fingerprint varchar(64) NOT NULL,
    status varchar(16) NOT NULL,
    document_id bigint,
    source_version varchar(128),
    chunks integer,
    error_message text,
    created_at timestamptz NOT NULL DEFAULT now(),
    completed_at timestamptz,
    PRIMARY KEY (tenant_id, request_id),
    CHECK (request_fingerprint ~ '^[0-9a-f]{64}$'),
    CHECK ((status = 'PROCESSING' AND document_id IS NULL AND source_version IS NULL AND chunks IS NULL AND error_message IS NULL AND completed_at IS NULL)
        OR (status = 'SUCCEEDED' AND document_id IS NOT NULL AND source_version IS NOT NULL AND chunks IS NOT NULL AND chunks >= 0 AND error_message IS NULL AND completed_at IS NOT NULL)
        OR (status = 'REJECTED' AND document_id IS NULL AND source_version IS NULL AND chunks IS NULL AND error_message IS NOT NULL AND completed_at IS NOT NULL))
);
ALTER TABLE public.nw_knowledge_upload_receipts ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.nw_knowledge_upload_receipts FORCE ROW LEVEL SECURITY;
CREATE POLICY knowledge_upload_receipts_tenant ON public.nw_knowledge_upload_receipts
    USING (tenant_id::text = current_setting('app.tenant_id', true))
    WITH CHECK (tenant_id::text = current_setting('app.tenant_id', true));
GRANT SELECT, INSERT, UPDATE ON public.nw_knowledge_upload_receipts TO skillforge;
COMMENT ON TABLE public.nw_knowledge_upload_receipts IS '知识文档上传原请求及回执；成功回执与文档在同一事务提交';
