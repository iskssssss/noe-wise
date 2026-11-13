CREATE TABLE public.nw_knowledge_reindex_receipts (
    tenant_id bigint NOT NULL,
    request_id uuid NOT NULL,
    document_id bigint NOT NULL,
    actor_ref varchar(128) NOT NULL,
    request_fingerprint varchar(64) NOT NULL,
    status varchar(16) NOT NULL,
    source_version varchar(128),
    chunks integer,
    error_message text,
    created_at timestamptz NOT NULL DEFAULT now(),
    completed_at timestamptz,
    PRIMARY KEY (tenant_id, request_id),
    CHECK (request_fingerprint ~ '^[0-9a-f]{64}$'),
    CHECK ((status = 'PROCESSING' AND source_version IS NULL AND chunks IS NULL AND error_message IS NULL AND completed_at IS NULL)
        OR (status = 'SUCCEEDED' AND source_version IS NOT NULL AND chunks IS NOT NULL AND chunks >= 0 AND error_message IS NULL AND completed_at IS NOT NULL)
        OR (status = 'REJECTED' AND source_version IS NULL AND chunks IS NULL AND error_message IS NOT NULL AND completed_at IS NOT NULL))
);
ALTER TABLE public.nw_knowledge_reindex_receipts ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.nw_knowledge_reindex_receipts FORCE ROW LEVEL SECURITY;
CREATE POLICY knowledge_reindex_receipts_tenant ON public.nw_knowledge_reindex_receipts
    USING (tenant_id::text = current_setting('app.tenant_id', true))
    WITH CHECK (tenant_id::text = current_setting('app.tenant_id', true));
GRANT SELECT, INSERT, UPDATE ON public.nw_knowledge_reindex_receipts TO skillforge;
COMMENT ON TABLE public.nw_knowledge_reindex_receipts IS '知识文档重新索引原请求及回执；成功回执与新来源版本在同一事务提交';
