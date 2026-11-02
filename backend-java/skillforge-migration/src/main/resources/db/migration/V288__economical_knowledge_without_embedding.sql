-- 经济知识库只有关键词索引，模型三元组明确为空；非经济库仍必须冻结完整向量空间。
ALTER TABLE nw_knowledge_bases
 ALTER COLUMN embedding_model_config_id DROP NOT NULL,
 ALTER COLUMN embedding_model_revision DROP NOT NULL,
 ALTER COLUMN embedding_dimensions DROP NOT NULL,
 ADD CONSTRAINT knowledge_base_embedding_space_complete CHECK (
   num_nonnulls(embedding_model_config_id,embedding_model_revision,embedding_dimensions)=3
   OR (indexing_mode IS NOT DISTINCT FROM 'economical' AND num_nonnulls(embedding_model_config_id,embedding_model_revision,embedding_dimensions)=0));
ALTER TABLE nw_knowledge_document_versions
 ALTER COLUMN embedding_model_config_id DROP NOT NULL,
 ALTER COLUMN embedding_model_revision DROP NOT NULL,
 ALTER COLUMN embedding_dimensions DROP NOT NULL,
 ADD CONSTRAINT knowledge_document_embedding_space_complete CHECK (
   num_nonnulls(embedding_model_config_id,embedding_model_revision,embedding_dimensions) IN (0,3));
ALTER TABLE nw_knowledge_base_revisions ADD CONSTRAINT knowledge_revision_embedding_space_complete CHECK (
 num_nonnulls(embedding_model_config_id,embedding_model_revision,embedding_dimensions) IN (0,3));
-- 原重建与冻结检查继续生效；NULL模型使用IS DISTINCT FROM，不能借SQL三值逻辑绕过原空间。
CREATE OR REPLACE FUNCTION guard_knowledge_rebuild_write() RETURNS trigger LANGUAGE plpgsql AS $$
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
  IF NEW.embedding_model_config_id IS NULL AND base.indexing_mode IS DISTINCT FROM 'economical' THEN
   RAISE EXCEPTION 'knowledge_embedding_model_required';
  END IF;
  IF target IS TRUE THEN
   IF (NEW.embedding_model_config_id, NEW.embedding_model_revision, NEW.embedding_dimensions)
      IS DISTINCT FROM (job.target_model_id, job.target_model_revision, job.target_dimensions) THEN RAISE EXCEPTION 'knowledge_rebuild_model_mismatch'; END IF;
  ELSIF (NEW.embedding_model_config_id, NEW.embedding_model_revision, NEW.embedding_dimensions)
     IS DISTINCT FROM (base.embedding_model_config_id, base.embedding_model_revision, base.embedding_dimensions) THEN RAISE EXCEPTION 'knowledge_source_model_stale'; END IF;
 END IF;
 IF TG_TABLE_NAME='nw_knowledge_bases' AND TG_OP='UPDATE' THEN NEW.control_revision=OLD.control_revision+1; END IF;
 IF TG_TABLE_NAME='nw_knowledge_documents' THEN
  UPDATE nw_knowledge_bases SET control_revision=control_revision+1 WHERE tenant_id=owner AND id=kb;
 END IF;
 IF TG_OP='DELETE' THEN RETURN OLD; END IF;
 RETURN NEW;
END; $$;
