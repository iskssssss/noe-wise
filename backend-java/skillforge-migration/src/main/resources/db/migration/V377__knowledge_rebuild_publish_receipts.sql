-- 同一原请求的成功回执或封存与发布切换原子提交。
CREATE TABLE nw_knowledge_rebuild_publish_receipts (
 tenant_id bigint NOT NULL REFERENCES nw_tenants(id), request_id uuid NOT NULL,
 actor_ref varchar(160) NOT NULL CHECK (actor_ref ~ '^service_principal:[1-9][0-9]*$' OR actor_ref ~ ('^tenant:' || tenant_id::text || ':member:[1-9][0-9]*$')),
 kb_id bigint NOT NULL, job_id bigint NOT NULL, fingerprint char(64) NOT NULL,
 status varchar(16) NOT NULL CHECK(status IN ('SUCCEEDED','FENCED')),
 snapshot jsonb, created_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY (tenant_id, actor_ref, request_id),
 FOREIGN KEY (tenant_id, job_id) REFERENCES nw_knowledge_rebuild_jobs(tenant_id, id) ON DELETE RESTRICT,
 CHECK ((status='SUCCEEDED' AND snapshot IS NOT NULL) OR (status='FENCED' AND snapshot IS NULL))
);
ALTER TABLE nw_knowledge_rebuild_publish_receipts ENABLE ROW LEVEL SECURITY;
ALTER TABLE nw_knowledge_rebuild_publish_receipts FORCE ROW LEVEL SECURITY;
CREATE POLICY knowledge_rebuild_publish_receipt_tenant ON nw_knowledge_rebuild_publish_receipts
 USING (tenant_id=NULLIF(current_setting('app.tenant_id',true),'')::bigint)
 WITH CHECK (tenant_id=NULLIF(current_setting('app.tenant_id',true),'')::bigint AND EXISTS (
  SELECT 1 FROM nw_knowledge_rebuild_jobs j WHERE j.tenant_id=nw_knowledge_rebuild_publish_receipts.tenant_id AND j.id=nw_knowledge_rebuild_publish_receipts.job_id AND j.kb_id=nw_knowledge_rebuild_publish_receipts.kb_id));
GRANT SELECT, INSERT ON nw_knowledge_rebuild_publish_receipts TO skillforge;
CREATE FUNCTION immutable_knowledge_rebuild_publish_receipt() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'knowledge_rebuild_publish_receipt_immutable'; END $$;
CREATE TRIGGER knowledge_rebuild_publish_receipt_immutable BEFORE UPDATE OR DELETE ON nw_knowledge_rebuild_publish_receipts
 FOR EACH ROW EXECUTE FUNCTION immutable_knowledge_rebuild_publish_receipt();
