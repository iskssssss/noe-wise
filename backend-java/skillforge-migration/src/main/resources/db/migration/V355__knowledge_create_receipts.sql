CREATE TABLE public.nw_knowledge_create_receipts (
    tenant_id bigint NOT NULL,
    request_id uuid NOT NULL,
    actor_ref varchar(128) NOT NULL,
    request_fingerprint varchar(64) NOT NULL,
    kb_id bigint,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, request_id),
    CHECK (request_fingerprint ~ '^[0-9a-f]{64}$')
);
ALTER TABLE public.nw_knowledge_create_receipts ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.nw_knowledge_create_receipts FORCE ROW LEVEL SECURITY;
CREATE POLICY knowledge_create_receipts_tenant ON public.nw_knowledge_create_receipts
    USING (tenant_id::text = current_setting('app.tenant_id', true))
    WITH CHECK (tenant_id::text = current_setting('app.tenant_id', true));
GRANT SELECT, INSERT, UPDATE ON public.nw_knowledge_create_receipts TO skillforge;
COMMENT ON TABLE public.nw_knowledge_create_receipts IS '知识库创建原请求身份及结果，与知识库在同一事务提交';
