-- 全库重建保留旧来源版本，手动发布才切换；不为旧版本推断或回填模型身份。
ALTER TABLE nw_knowledge_document_versions ADD COLUMN embedding_model_config_id bigint NOT NULL,
 ADD COLUMN embedding_model_revision bigint NOT NULL CHECK(embedding_model_revision>0),
 ADD COLUMN embedding_dimensions integer NOT NULL CHECK(embedding_dimensions>0);
ALTER TABLE nw_knowledge_base_revisions ADD COLUMN embedding_model_config_id bigint,
 ADD COLUMN embedding_model_revision bigint CHECK(embedding_model_revision>0),
 ADD COLUMN embedding_dimensions integer CHECK(embedding_dimensions>0);
ALTER TABLE nw_knowledge_bases ADD COLUMN rebuild_job_id bigint, ADD COLUMN control_revision bigint NOT NULL DEFAULT 0 CHECK(control_revision>=0);
CREATE TABLE nw_knowledge_rebuild_jobs(
 id bigint PRIMARY KEY, tenant_id bigint NOT NULL REFERENCES nw_tenants(id) ON DELETE RESTRICT,
 kb_id bigint NOT NULL REFERENCES nw_knowledge_bases(id) ON DELETE RESTRICT,
 idempotency_key varchar(128) NOT NULL, fingerprint varchar(64) NOT NULL,
 status varchar(32) NOT NULL CHECK(status IN('QUEUED','RUNNING','READY','FAILED','UNKNOWN','CANCELLED','PUBLISHING','PUBLISHED')),
 revision bigint NOT NULL CHECK(revision>0), fence bigint NOT NULL CHECK(fence>=0), lease_until timestamptz,
 target_model_id bigint NOT NULL, target_model_revision bigint NOT NULL CHECK(target_model_revision>0),
 target_dimensions integer NOT NULL CHECK(target_dimensions>0),
 original_active_revision_id bigint, original_model_id bigint NOT NULL, original_model_revision bigint NOT NULL,
 error_code varchar(100), published_revision bigint,
 created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(tenant_id,kb_id,idempotency_key), UNIQUE(tenant_id,id));
CREATE UNIQUE INDEX knowledge_rebuild_single_active ON nw_knowledge_rebuild_jobs(tenant_id,kb_id)
 WHERE status IN('QUEUED','RUNNING','READY','PUBLISHING');
CREATE INDEX knowledge_rebuild_due ON nw_knowledge_rebuild_jobs(tenant_id,status,lease_until);
CREATE TABLE nw_knowledge_rebuild_items(
 id bigint PRIMARY KEY, tenant_id bigint NOT NULL, job_id bigint NOT NULL,
 document_id bigint NOT NULL REFERENCES nw_knowledge_documents(id) ON DELETE RESTRICT,
 source_version varchar(128) NOT NULL, target_source_version varchar(128) NOT NULL,
 status varchar(20) NOT NULL CHECK(status IN('PENDING','READY')), chunk_count integer NOT NULL CHECK(chunk_count>0),
 FOREIGN KEY(tenant_id,job_id) REFERENCES nw_knowledge_rebuild_jobs(tenant_id,id) ON DELETE RESTRICT,
 FOREIGN KEY(tenant_id,document_id,source_version) REFERENCES nw_knowledge_document_versions(tenant_id,document_id,source_version) ON DELETE RESTRICT,
 UNIQUE(tenant_id,job_id,document_id), UNIQUE(tenant_id,target_source_version), UNIQUE(tenant_id,id));
CREATE TABLE nw_knowledge_rebuild_batches(
 id bigint PRIMARY KEY, tenant_id bigint NOT NULL, job_id bigint NOT NULL, item_id bigint NOT NULL,
 batch_index integer NOT NULL CHECK(batch_index>=0), invocation_id varchar(128) NOT NULL,
 status varchar(20) NOT NULL CHECK(status IN('PENDING','STARTED','READY','UNKNOWN','FAILED')),
 vectors jsonb, receipt_history jsonb, error_code varchar(100),
 FOREIGN KEY(tenant_id,job_id) REFERENCES nw_knowledge_rebuild_jobs(tenant_id,id) ON DELETE RESTRICT,
 FOREIGN KEY(tenant_id,item_id) REFERENCES nw_knowledge_rebuild_items(tenant_id,id) ON DELETE RESTRICT,
 UNIQUE(tenant_id,item_id,batch_index), UNIQUE(tenant_id,invocation_id));
ALTER TABLE nw_knowledge_bases ADD CONSTRAINT knowledge_base_rebuild_job_fk FOREIGN KEY(tenant_id,rebuild_job_id)
 REFERENCES nw_knowledge_rebuild_jobs(tenant_id,id) ON DELETE RESTRICT;
-- 锁与冻结检查位于存储提交边界，覆盖治理投影和在重建启动前已准备的迟到写入。
CREATE FUNCTION guard_knowledge_rebuild_write() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE base nw_knowledge_bases; job nw_knowledge_rebuild_jobs; row_value jsonb; kb bigint; owner bigint; target boolean;
BEGIN
 row_value=CASE WHEN TG_OP='DELETE' THEN to_jsonb(OLD) ELSE to_jsonb(NEW) END;
 kb=CASE WHEN TG_TABLE_NAME='nw_knowledge_bases' THEN (row_value->>'id')::bigint ELSE (row_value->>'kb_id')::bigint END;
 owner=(row_value->>'tenant_id')::bigint;
 SELECT * INTO base FROM nw_knowledge_bases WHERE tenant_id=owner AND id=kb FOR UPDATE;
 IF base.rebuild_job_id IS NOT NULL THEN
  SELECT * INTO job FROM nw_knowledge_rebuild_jobs WHERE tenant_id=owner AND id=base.rebuild_job_id;
  IF TG_TABLE_NAME='nw_knowledge_bases' THEN
   IF job.status<>'PUBLISHING' AND TG_OP='UPDATE' AND
       (to_jsonb(NEW)-'rebuild_job_id'-'updated_at'-'control_revision') IS DISTINCT FROM (to_jsonb(OLD)-'rebuild_job_id'-'updated_at'-'control_revision') THEN
    RAISE EXCEPTION 'knowledge_rebuild_write_locked' USING ERRCODE='55000';
   END IF;
   IF TG_OP='DELETE' THEN RAISE EXCEPTION 'knowledge_rebuild_write_locked' USING ERRCODE='55000'; END IF;
  ELSIF TG_TABLE_NAME='nw_knowledge_document_versions' THEN
   SELECT EXISTS(SELECT 1 FROM nw_knowledge_rebuild_items i WHERE i.tenant_id=owner AND i.job_id=job.id
     AND i.document_id=(row_value->>'document_id')::bigint AND i.target_source_version=row_value->>'source_version') INTO target;
   IF NOT target THEN RAISE EXCEPTION 'knowledge_rebuild_write_locked' USING ERRCODE='55000'; END IF;
  ELSIF job.status<>'PUBLISHING' THEN
   RAISE EXCEPTION 'knowledge_rebuild_write_locked' USING ERRCODE='55000';
  END IF;
 END IF;
 IF TG_TABLE_NAME='nw_knowledge_document_versions' AND TG_OP='INSERT' THEN
  IF target IS TRUE THEN
   IF NEW.embedding_model_config_id<>job.target_model_id OR NEW.embedding_model_revision<>job.target_model_revision
      OR NEW.embedding_dimensions<>job.target_dimensions THEN RAISE EXCEPTION 'knowledge_rebuild_model_mismatch'; END IF;
  ELSIF NEW.embedding_model_config_id<>base.embedding_model_config_id OR NEW.embedding_model_revision<>base.embedding_model_revision
     OR NEW.embedding_dimensions<>base.embedding_dimensions THEN RAISE EXCEPTION 'knowledge_source_model_stale'; END IF;
 END IF;
 IF TG_TABLE_NAME='nw_knowledge_bases' AND TG_OP='UPDATE' THEN NEW.control_revision=OLD.control_revision+1; END IF;
 IF TG_TABLE_NAME='nw_knowledge_documents' THEN
  UPDATE nw_knowledge_bases SET control_revision=control_revision+1 WHERE tenant_id=owner AND id=kb;
 END IF;
 IF TG_OP='DELETE' THEN RETURN OLD; END IF;
 RETURN NEW;
END; $$;
CREATE TRIGGER skillforge_knowledge_bases_rebuild_frozen BEFORE INSERT OR UPDATE OR DELETE ON nw_knowledge_bases
 FOR EACH ROW EXECUTE FUNCTION guard_knowledge_rebuild_write();
CREATE TRIGGER skillforge_knowledge_documents_rebuild_frozen BEFORE INSERT OR UPDATE OR DELETE ON nw_knowledge_documents
 FOR EACH ROW EXECUTE FUNCTION guard_knowledge_rebuild_write();
CREATE TRIGGER skillforge_knowledge_document_versions_rebuild_frozen BEFORE INSERT OR UPDATE OR DELETE ON nw_knowledge_document_versions
 FOR EACH ROW EXECUTE FUNCTION guard_knowledge_rebuild_write();
CREATE TRIGGER skillforge_knowledge_base_revisions_rebuild_frozen BEFORE INSERT OR UPDATE OR DELETE ON nw_knowledge_base_revisions
 FOR EACH ROW EXECUTE FUNCTION guard_knowledge_rebuild_write();
ALTER TABLE nw_knowledge_rebuild_jobs ENABLE ROW LEVEL SECURITY;
ALTER TABLE nw_knowledge_rebuild_jobs FORCE ROW LEVEL SECURITY;
CREATE POLICY skillforge_knowledge_rebuild_jobs_tenant ON nw_knowledge_rebuild_jobs USING(tenant_id=NULLIF(current_setting('app.tenant_id',true),'')::bigint)
 WITH CHECK(tenant_id=NULLIF(current_setting('app.tenant_id',true),'')::bigint);
COMMENT ON TABLE nw_knowledge_rebuild_jobs IS 'Knowledge全库Embedding重建持久事实；保留源版本及外调未知结果，失败不切换活动版本';
ALTER TABLE nw_knowledge_rebuild_items ENABLE ROW LEVEL SECURITY;
ALTER TABLE nw_knowledge_rebuild_items FORCE ROW LEVEL SECURITY;
CREATE POLICY skillforge_knowledge_rebuild_items_tenant ON nw_knowledge_rebuild_items USING(tenant_id=NULLIF(current_setting('app.tenant_id',true),'')::bigint)
 WITH CHECK(tenant_id=NULLIF(current_setting('app.tenant_id',true),'')::bigint);
COMMENT ON TABLE nw_knowledge_rebuild_items IS 'Knowledge全库Embedding重建持久事实；保留源版本及外调未知结果，失败不切换活动版本';
ALTER TABLE nw_knowledge_rebuild_batches ENABLE ROW LEVEL SECURITY;
ALTER TABLE nw_knowledge_rebuild_batches FORCE ROW LEVEL SECURITY;
CREATE POLICY skillforge_knowledge_rebuild_batches_tenant ON nw_knowledge_rebuild_batches USING(tenant_id=NULLIF(current_setting('app.tenant_id',true),'')::bigint)
 WITH CHECK(tenant_id=NULLIF(current_setting('app.tenant_id',true),'')::bigint);
COMMENT ON TABLE nw_knowledge_rebuild_batches IS 'Knowledge全库Embedding重建持久事实；保留源版本及外调未知结果，失败不切换活动版本';
COMMENT ON COLUMN nw_knowledge_rebuild_jobs.id IS '操作身份';
COMMENT ON COLUMN nw_knowledge_rebuild_jobs.tenant_id IS '所属租户';
COMMENT ON COLUMN nw_knowledge_rebuild_jobs.kb_id IS '冻结的知识库';
COMMENT ON COLUMN nw_knowledge_rebuild_jobs.idempotency_key IS '用户提交幂等键';
COMMENT ON COLUMN nw_knowledge_rebuild_jobs.fingerprint IS '完整创建参数摘要';
COMMENT ON COLUMN nw_knowledge_rebuild_jobs.status IS '重建准备与手动发布状态';
COMMENT ON COLUMN nw_knowledge_rebuild_jobs.revision IS '用户CAS修订';
COMMENT ON COLUMN nw_knowledge_rebuild_jobs.fence IS 'worker写权代次';
COMMENT ON COLUMN nw_knowledge_rebuild_jobs.lease_until IS 'worker租约到期时间';
COMMENT ON COLUMN nw_knowledge_rebuild_jobs.target_model_id IS '目标平台Embedding模型ID';
COMMENT ON COLUMN nw_knowledge_rebuild_jobs.target_model_revision IS '目标不可变模型版本';
COMMENT ON COLUMN nw_knowledge_rebuild_jobs.target_dimensions IS '目标向量维度';
COMMENT ON COLUMN nw_knowledge_rebuild_jobs.original_active_revision_id IS '启动时活动revision指针';
COMMENT ON COLUMN nw_knowledge_rebuild_jobs.original_model_id IS '启动时默认模型ID';
COMMENT ON COLUMN nw_knowledge_rebuild_jobs.original_model_revision IS '启动时默认模型版本';
COMMENT ON COLUMN nw_knowledge_rebuild_jobs.error_code IS '稳定安全错误码';
COMMENT ON COLUMN nw_knowledge_rebuild_jobs.published_revision IS '成功发布的KB revision号';
COMMENT ON COLUMN nw_knowledge_rebuild_jobs.created_at IS '创建时间';
COMMENT ON COLUMN nw_knowledge_rebuild_jobs.updated_at IS '最后状态迁移时间';
COMMENT ON COLUMN nw_knowledge_rebuild_items.id IS '文档重建身份';
COMMENT ON COLUMN nw_knowledge_rebuild_items.tenant_id IS '所属租户';
COMMENT ON COLUMN nw_knowledge_rebuild_items.job_id IS '所属重建操作';
COMMENT ON COLUMN nw_knowledge_rebuild_items.document_id IS '稳定文档身份';
COMMENT ON COLUMN nw_knowledge_rebuild_items.source_version IS '启动时当前已提交来源版本';
COMMENT ON COLUMN nw_knowledge_rebuild_items.target_source_version IS '预分配新来源版本';
COMMENT ON COLUMN nw_knowledge_rebuild_items.status IS '目标版本准备状态';
COMMENT ON COLUMN nw_knowledge_rebuild_items.chunk_count IS '原来源完整块数量';
COMMENT ON COLUMN nw_knowledge_rebuild_batches.id IS '批次身份';
COMMENT ON COLUMN nw_knowledge_rebuild_batches.tenant_id IS '所属租户';
COMMENT ON COLUMN nw_knowledge_rebuild_batches.job_id IS '所属操作';
COMMENT ON COLUMN nw_knowledge_rebuild_batches.item_id IS '所属文档重建';
COMMENT ON COLUMN nw_knowledge_rebuild_batches.batch_index IS '每20条分块的批次序号';
COMMENT ON COLUMN nw_knowledge_rebuild_batches.invocation_id IS '持久逻辑外调身份';
COMMENT ON COLUMN nw_knowledge_rebuild_batches.status IS '调用前事实与结果回执状态';
COMMENT ON COLUMN nw_knowledge_rebuild_batches.vectors IS '完整有序向量结果快照；READY才可用于来源版本';
COMMENT ON COLUMN nw_knowledge_rebuild_batches.error_code IS '稳定错误码';
COMMENT ON COLUMN nw_knowledge_document_versions.embedding_model_config_id IS '该不可变版本的Embedding空间身份；不得用当前默认模型替代';
COMMENT ON COLUMN nw_knowledge_document_versions.embedding_model_revision IS '该不可变版本的Embedding空间身份；不得用当前默认模型替代';
COMMENT ON COLUMN nw_knowledge_document_versions.embedding_dimensions IS '该不可变版本的Embedding空间身份；不得用当前默认模型替代';
COMMENT ON COLUMN nw_knowledge_base_revisions.embedding_model_config_id IS '该不可变版本的Embedding空间身份；不得用当前默认模型替代';
COMMENT ON COLUMN nw_knowledge_base_revisions.embedding_model_revision IS '该不可变版本的Embedding空间身份；不得用当前默认模型替代';
COMMENT ON COLUMN nw_knowledge_base_revisions.embedding_dimensions IS '该不可变版本的Embedding空间身份；不得用当前默认模型替代';
COMMENT ON COLUMN nw_knowledge_bases.rebuild_job_id IS '非空时冻结内容编辑与普通发布；检索仍读取旧已发布版本';
COMMENT ON COLUMN nw_knowledge_rebuild_batches.receipt_history IS '显式重算前追加的原UNKNOWN不可变回执；原invocation仍保留在外调账本';

CREATE FUNCTION guard_knowledge_rebuild_receipt() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF OLD.status='READY' AND NEW IS DISTINCT FROM OLD THEN RAISE EXCEPTION 'knowledge_rebuild_ready_batch_immutable'; END IF;
 IF COALESCE(jsonb_array_length(NEW.receipt_history),0)<COALESCE(jsonb_array_length(OLD.receipt_history),0) OR
 EXISTS(SELECT 1 FROM jsonb_array_elements(COALESCE(OLD.receipt_history,'[]'::jsonb)) WITH ORDINALITY r(value,idx)
 WHERE NEW.receipt_history->(r.idx::integer-1) IS DISTINCT FROM r.value) THEN RAISE EXCEPTION 'knowledge_rebuild_receipt_history_immutable'; END IF;
 RETURN NEW;
END; $$;
CREATE TRIGGER knowledge_rebuild_receipt_immutable BEFORE UPDATE ON nw_knowledge_rebuild_batches
 FOR EACH ROW EXECUTE FUNCTION guard_knowledge_rebuild_receipt();
CREATE FUNCTION guard_knowledge_revision_model() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF OLD.status<>'draft' AND (NEW.embedding_model_config_id,NEW.embedding_model_revision,NEW.embedding_dimensions)
 IS DISTINCT FROM (OLD.embedding_model_config_id,OLD.embedding_model_revision,OLD.embedding_dimensions) THEN
 RAISE EXCEPTION 'knowledge_revision_model_immutable'; END IF;
 RETURN NEW;
END; $$;
CREATE TRIGGER knowledge_revision_model_immutable BEFORE UPDATE ON nw_knowledge_base_revisions
 FOR EACH ROW EXECUTE FUNCTION guard_knowledge_revision_model();
COMMENT ON COLUMN nw_knowledge_bases.control_revision IS '配置、文档身份与current来源指针变更单调推进的控制CAS修订；staging不推进';
