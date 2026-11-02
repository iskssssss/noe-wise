CREATE TABLE public.nw_knowledge_revision_publish_receipts (
    tenant_id bigint NOT NULL,
    request_id uuid NOT NULL,
    kb_id bigint NOT NULL,
    actor_ref varchar(128) NOT NULL,
    request_fingerprint varchar(64) NOT NULL,
    status varchar(16) NOT NULL,
    response jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    completed_at timestamptz,
    PRIMARY KEY (tenant_id, request_id),
    CHECK (request_fingerprint ~ '^[0-9a-f]{64}$'),
    CHECK ((status = 'PROCESSING' AND response IS NULL AND completed_at IS NULL) OR
        (status IN ('SUCCEEDED', 'REJECTED') AND response IS NOT NULL AND completed_at IS NOT NULL
         AND jsonb_typeof(response) = 'object' AND response->>'status' = status
         AND response->>'request_id' = request_id::text))
);
ALTER TABLE public.nw_knowledge_revision_publish_receipts ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.nw_knowledge_revision_publish_receipts FORCE ROW LEVEL SECURITY;
CREATE POLICY knowledge_revision_publish_receipts_tenant ON public.nw_knowledge_revision_publish_receipts
    USING (tenant_id::text = current_setting('app.tenant_id', true))
    WITH CHECK (tenant_id::text = current_setting('app.tenant_id', true));
GRANT SELECT, INSERT, UPDATE ON public.nw_knowledge_revision_publish_receipts TO skillforge;
COMMENT ON TABLE public.nw_knowledge_revision_publish_receipts IS '知识库组合发布原命令及回执；成功与业务共同提交，处理中不得重派';
