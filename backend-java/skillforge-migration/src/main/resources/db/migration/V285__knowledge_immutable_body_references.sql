-- K1-K5 知识领域前向迁移请求；协调者选择最终 Flyway 编号。
-- 只能在已授权的新建/重建测试环境执行。现存环境执行前必须停止知识写入、
-- 检查冻结正文与来源一致、保全数据库与索引快照；本文件不是数据库操作授权。
-- 回滚恢复迁移前数据库及其配套应用包；禁止旧包继续写入此新模型。

CREATE TABLE public.nw_knowledge_storage_budgets (
 tenant_id bigint NOT NULL,
 max_content_bytes bigint,
 max_chunk_bodies bigint,
 CONSTRAINT skillforge_knowledge_storage_budgets_pkey PRIMARY KEY(tenant_id),
 CONSTRAINT skillforge_knowledge_storage_budgets_tenant_fk FOREIGN KEY(tenant_id) REFERENCES nw_tenants(id) ON DELETE RESTRICT,
 CONSTRAINT skillforge_knowledge_storage_budgets_bytes_ck CHECK(max_content_bytes IS NULL OR max_content_bytes>0),
 CONSTRAINT skillforge_knowledge_storage_budgets_chunks_ck CHECK(max_chunk_bodies IS NULL OR max_chunk_bodies>0)
);
CREATE TABLE public.nw_knowledge_document_contents (
 id bigint NOT NULL, tenant_id bigint NOT NULL, document_id bigint NOT NULL,
 format varchar(64) NOT NULL, digest varchar(64) NOT NULL, content text,
 CONSTRAINT skillforge_knowledge_document_contents_pkey PRIMARY KEY(id),
 CONSTRAINT skillforge_knowledge_document_contents_owner_uq UNIQUE(tenant_id,document_id,id),
 CONSTRAINT skillforge_knowledge_document_contents_digest_ck CHECK(digest ~ '^[0-9a-f]{64}$'),
 CONSTRAINT skillforge_knowledge_document_contents_document_fk FOREIGN KEY(tenant_id,document_id) REFERENCES nw_knowledge_documents(tenant_id,id) ON DELETE RESTRICT
);
CREATE TABLE public.nw_knowledge_chunk_contents (
 id bigint NOT NULL, tenant_id bigint NOT NULL, document_id bigint NOT NULL,
 format varchar(64) NOT NULL, digest varchar(64) NOT NULL, content text NOT NULL,
 CONSTRAINT skillforge_knowledge_chunk_contents_pkey PRIMARY KEY(id),
 CONSTRAINT skillforge_knowledge_chunk_contents_tenant_uq UNIQUE(tenant_id,id),
 CONSTRAINT skillforge_knowledge_chunk_contents_owner_uq UNIQUE(tenant_id,document_id,id),
 CONSTRAINT skillforge_knowledge_chunk_contents_digest_ck CHECK(digest ~ '^[0-9a-f]{64}$'),
 CONSTRAINT skillforge_knowledge_chunk_contents_document_fk FOREIGN KEY(tenant_id,document_id) REFERENCES nw_knowledge_documents(tenant_id,id) ON DELETE RESTRICT
);
CREATE TABLE public.nw_knowledge_chunk_embeddings (
 id bigint NOT NULL, tenant_id bigint NOT NULL, content_id bigint NOT NULL,
 model_id bigint NOT NULL, model_revision bigint NOT NULL, dimensions integer NOT NULL,
 digest varchar(64) NOT NULL, embedding vector NOT NULL,
 CONSTRAINT skillforge_knowledge_chunk_embeddings_pkey PRIMARY KEY(id),
 CONSTRAINT skillforge_knowledge_chunk_embeddings_tenant_uq UNIQUE(tenant_id,id),
 CONSTRAINT skillforge_knowledge_chunk_embeddings_content_uq UNIQUE(tenant_id,id,content_id),
 CONSTRAINT skillforge_knowledge_chunk_embeddings_content_fk FOREIGN KEY(tenant_id,content_id) REFERENCES nw_knowledge_chunk_contents(tenant_id,id) ON DELETE RESTRICT,
 CONSTRAINT skillforge_knowledge_chunk_embeddings_revision_ck CHECK(model_revision>0),
 CONSTRAINT skillforge_knowledge_chunk_embeddings_dimensions_ck CHECK(dimensions>0 AND vector_dims(embedding)=dimensions),
 CONSTRAINT skillforge_knowledge_chunk_embeddings_digest_ck CHECK(digest ~ '^[0-9a-f]{64}$')
);
ALTER TABLE nw_knowledge_documents ADD CONSTRAINT skillforge_knowledge_documents_base_owner_uq UNIQUE(tenant_id,kb_id,id);
ALTER TABLE nw_knowledge_bases ADD CONSTRAINT skillforge_knowledge_bases_storage_owner_uq UNIQUE(tenant_id,id);
CREATE TABLE public.nw_knowledge_chunk_sets (
 id bigint NOT NULL, tenant_id bigint NOT NULL, kb_id bigint NOT NULL, document_id bigint NOT NULL,
 CONSTRAINT skillforge_knowledge_chunk_sets_pkey PRIMARY KEY(id),
 CONSTRAINT skillforge_knowledge_chunk_sets_owner_uq UNIQUE(tenant_id,kb_id,document_id,id),
 CONSTRAINT skillforge_knowledge_chunk_sets_tenant_uq UNIQUE(tenant_id,id),
 CONSTRAINT skillforge_knowledge_chunk_sets_document_fk FOREIGN KEY(tenant_id,kb_id,document_id) REFERENCES nw_knowledge_documents(tenant_id,kb_id,id) ON DELETE RESTRICT,
 CONSTRAINT skillforge_knowledge_chunk_sets_base_fk FOREIGN KEY(tenant_id,kb_id) REFERENCES nw_knowledge_bases(tenant_id,id) ON DELETE RESTRICT
);
CREATE INDEX skillforge_knowledge_document_contents_digest ON nw_knowledge_document_contents(tenant_id,document_id,digest);
CREATE INDEX skillforge_knowledge_chunk_contents_digest ON nw_knowledge_chunk_contents(tenant_id,document_id,digest);
CREATE INDEX skillforge_knowledge_chunk_embeddings_lookup ON nw_knowledge_chunk_embeddings(tenant_id,content_id,model_id,model_revision,dimensions,digest);

-- 迁移只改表示，不改版本/分块身份、来源正文或 UNKNOWN 回执。
-- 先拒绝无法精确引用原正文的冻结副本，禁止用当前版本替换旧取证。
DO $$ BEGIN
 IF EXISTS(SELECT 1 FROM nw_runtime_knowledge_chunks r LEFT JOIN nw_knowledge_chunks c
   ON c.tenant_id::text=r.tenant_id AND c.kb_id::text=r.source_id
   AND c.document_id::text=r.document_id AND c.source_version=r.source_version AND c.id::text=r.chunk_id
   WHERE c.id IS NULL OR c.content IS DISTINCT FROM r.content OR c.created_at IS DISTINCT FROM r.created_at OR c.chunk_index<>r.chunk_index)
 THEN RAISE EXCEPTION 'knowledge_frozen_reference_backfill_mismatch'; END IF;
 IF EXISTS(SELECT 1 FROM nw_knowledge_document_versions v WHERE v.status='ready' AND
   (v.chunk_count<=0 OR v.chunk_count<>(SELECT count(*) FROM nw_knowledge_chunks c WHERE c.tenant_id=v.tenant_id AND c.document_id=v.document_id AND c.source_version=v.source_version)
    OR v.chunk_count-1 IS DISTINCT FROM (SELECT max(chunk_index) FROM nw_knowledge_chunks c WHERE c.tenant_id=v.tenant_id AND c.document_id=v.document_id AND c.source_version=v.source_version)))
 THEN RAISE EXCEPTION 'knowledge_source_reference_backfill_incomplete'; END IF;
END $$;
ALTER TABLE nw_knowledge_document_versions DISABLE TRIGGER knowledge_document_version_ready_immutable;
ALTER TABLE nw_knowledge_document_versions DISABLE TRIGGER skillforge_knowledge_document_versions_rebuild_frozen;
ALTER TABLE nw_knowledge_chunks DISABLE TRIGGER knowledge_chunk_ready_immutable;
ALTER TABLE nw_knowledge_rebuild_batches DISABLE TRIGGER knowledge_rebuild_receipt_immutable;
ALTER TABLE nw_knowledge_document_versions ADD COLUMN content_id bigint, ADD COLUMN chunk_set_id bigint;
ALTER TABLE nw_knowledge_chunks ADD COLUMN content_id bigint, ADD COLUMN embedding_id bigint, ADD COLUMN chunk_set_id bigint;

-- 同租户同文档且全值相同才合并，摘要本身不产生去重授权。
INSERT INTO nw_knowledge_document_contents(id,tenant_id,document_id,format,digest,content)
 SELECT id,tenant_id,document_id,'text/plain; charset=utf-8',encode(sha256(convert_to(coalesce(content,''),'UTF8')),'hex'),content
 FROM (SELECT v.*,row_number() OVER(PARTITION BY tenant_id,document_id,content ORDER BY id) AS position FROM nw_knowledge_document_versions v) x WHERE position=1;
INSERT INTO nw_knowledge_chunk_contents(id,tenant_id,document_id,format,digest,content)
 SELECT id,tenant_id,document_id,'text/plain; charset=utf-8',encode(sha256(convert_to(content,'UTF8')),'hex'),content
 FROM (SELECT c.*,row_number() OVER(PARTITION BY tenant_id,document_id,content ORDER BY id) AS position FROM nw_knowledge_chunks c) x WHERE position=1;
INSERT INTO nw_knowledge_chunk_sets(id,tenant_id,kb_id,document_id) SELECT id,tenant_id,kb_id,document_id FROM nw_knowledge_document_versions;
UPDATE nw_knowledge_document_versions v SET content_id=b.id,chunk_set_id=v.id FROM nw_knowledge_document_contents b
 WHERE b.tenant_id=v.tenant_id AND b.document_id=v.document_id AND b.content IS NOT DISTINCT FROM v.content;
UPDATE nw_knowledge_chunks c SET content_id=b.id,chunk_set_id=v.id FROM nw_knowledge_chunk_contents b,nw_knowledge_document_versions v
 WHERE b.tenant_id=c.tenant_id AND b.document_id=c.document_id AND b.content=c.content
 AND v.tenant_id=c.tenant_id AND v.document_id=c.document_id AND v.source_version=c.source_version;
INSERT INTO nw_knowledge_chunk_embeddings(id,tenant_id,content_id,model_id,model_revision,dimensions,digest,embedding)
 SELECT id,tenant_id,content_id,model_id,model_revision,dimensions,encode(sha256(convert_to(vector_text,'UTF8')),'hex'),vector_text::vector
 FROM (SELECT c.id,c.tenant_id,c.content_id,v.embedding_model_config_id AS model_id,v.embedding_model_revision AS model_revision,
       v.embedding_dimensions AS dimensions,c.embedding::text AS vector_text,
       row_number() OVER(PARTITION BY c.tenant_id,c.content_id,v.embedding_model_config_id,v.embedding_model_revision,v.embedding_dimensions,c.embedding::text ORDER BY c.id) AS position
       FROM nw_knowledge_chunks c JOIN nw_knowledge_document_versions v ON v.tenant_id=c.tenant_id AND v.document_id=c.document_id AND v.source_version=c.source_version WHERE c.embedding IS NOT NULL) x WHERE position=1;
UPDATE nw_knowledge_chunks c SET embedding_id=e.id FROM nw_knowledge_chunk_embeddings e,nw_knowledge_document_versions v
 WHERE e.tenant_id=c.tenant_id AND e.content_id=c.content_id AND e.embedding=c.embedding
 AND v.tenant_id=c.tenant_id AND v.document_id=c.document_id AND v.source_version=c.source_version
 AND e.model_id=v.embedding_model_config_id AND e.model_revision=v.embedding_model_revision AND e.dimensions=v.embedding_dimensions;

ALTER TABLE nw_knowledge_chunks DROP CONSTRAINT fk_skillforge_knowledge_chunks_source_version, DROP CONSTRAINT uq_skillforge_knowledge_chunks_source_position,
 DROP COLUMN source_version, DROP COLUMN content, DROP COLUMN embedding;
ALTER TABLE nw_knowledge_document_versions DROP COLUMN content,
 ALTER COLUMN content_id SET NOT NULL, ALTER COLUMN chunk_set_id SET NOT NULL,
 ADD CONSTRAINT skillforge_knowledge_document_versions_content_fk FOREIGN KEY(tenant_id,document_id,content_id) REFERENCES nw_knowledge_document_contents(tenant_id,document_id,id) ON DELETE RESTRICT,
 ADD CONSTRAINT skillforge_knowledge_document_versions_set_fk FOREIGN KEY(tenant_id,kb_id,document_id,chunk_set_id) REFERENCES nw_knowledge_chunk_sets(tenant_id,kb_id,document_id,id) ON DELETE RESTRICT,
 ADD CONSTRAINT skillforge_knowledge_document_versions_source_set_uq UNIQUE(tenant_id,kb_id,source_version,chunk_set_id);
ALTER TABLE nw_knowledge_document_versions ADD CONSTRAINT skillforge_knowledge_document_versions_storage_base_fk
 FOREIGN KEY(tenant_id,kb_id) REFERENCES nw_knowledge_bases(tenant_id,id) ON DELETE RESTRICT;
ALTER TABLE nw_knowledge_chunks ALTER COLUMN content_id SET NOT NULL, ALTER COLUMN chunk_set_id SET NOT NULL,
 ADD CONSTRAINT skillforge_knowledge_chunks_set_fk FOREIGN KEY(tenant_id,kb_id,document_id,chunk_set_id) REFERENCES nw_knowledge_chunk_sets(tenant_id,kb_id,document_id,id) ON DELETE CASCADE,
 ADD CONSTRAINT skillforge_knowledge_chunks_content_fk FOREIGN KEY(tenant_id,document_id,content_id) REFERENCES nw_knowledge_chunk_contents(tenant_id,document_id,id) ON DELETE RESTRICT,
 ADD CONSTRAINT skillforge_knowledge_chunks_embedding_fk FOREIGN KEY(tenant_id,embedding_id,content_id) REFERENCES nw_knowledge_chunk_embeddings(tenant_id,id,content_id) ON DELETE RESTRICT,
 ADD CONSTRAINT skillforge_knowledge_chunks_set_position_uq UNIQUE(tenant_id,chunk_set_id,chunk_index),
 ADD CONSTRAINT skillforge_knowledge_chunks_reference_uq UNIQUE(tenant_id,chunk_set_id,chunk_index,id,document_id),
 ADD CONSTRAINT skillforge_knowledge_chunks_tenant_uq UNIQUE(tenant_id,id),
 ADD CONSTRAINT skillforge_knowledge_chunks_position_ck CHECK(chunk_index>=0);
CREATE INDEX skillforge_knowledge_chunks_content_reference ON nw_knowledge_chunks(tenant_id,content_id);
CREATE INDEX skillforge_knowledge_chunks_embedding_reference ON nw_knowledge_chunks(tenant_id,embedding_id) WHERE embedding_id IS NOT NULL;
CREATE INDEX skillforge_knowledge_document_versions_set_reference ON nw_knowledge_document_versions(tenant_id,chunk_set_id);
CREATE INDEX skillforge_knowledge_document_versions_content_reference ON nw_knowledge_document_versions(tenant_id,content_id);

-- PG16 invoker view 保留底层 RLS，视图仅为版本化检索投影，不形成第二正文所有者。
CREATE VIEW public.knowledge_chunk_values WITH(security_invoker=true) AS
 SELECT c.id,c.tenant_id,c.kb_id,c.document_id,c.chunk_set_id,c.content_id,c.embedding_id,c.chunk_index,
        v.source_version,b.content,e.embedding,c.created_at
 FROM nw_knowledge_document_versions v JOIN nw_knowledge_chunks c ON c.tenant_id=v.tenant_id AND c.document_id=v.document_id AND c.chunk_set_id=v.chunk_set_id
 JOIN nw_knowledge_chunk_contents b ON b.tenant_id=c.tenant_id AND b.document_id=c.document_id AND b.id=c.content_id
 LEFT JOIN nw_knowledge_chunk_embeddings e ON e.tenant_id=c.tenant_id AND e.id=c.embedding_id;

-- Runtime只保存精确引用；同一集合可在两个元数据版本中出现，主键包含精确版本。
ALTER TABLE nw_runtime_knowledge_sources ADD COLUMN source_tenant_id bigint GENERATED ALWAYS AS(tenant_id::bigint) STORED,
 ADD COLUMN source_base_id bigint GENERATED ALWAYS AS(source_id::bigint) STORED, ADD COLUMN chunk_set_id bigint;
ALTER TABLE nw_runtime_knowledge_chunks ADD COLUMN source_tenant_id bigint GENERATED ALWAYS AS(tenant_id::bigint) STORED,
 ADD COLUMN source_chunk_id bigint GENERATED ALWAYS AS(chunk_id::bigint) STORED,
 ADD COLUMN source_document_id bigint GENERATED ALWAYS AS(document_id::bigint) STORED, ADD COLUMN chunk_set_id bigint;
ALTER TABLE nw_runtime_knowledge_sources DISABLE TRIGGER knowledge_source_immutable;
ALTER TABLE nw_runtime_knowledge_chunks DISABLE TRIGGER knowledge_chunk_immutable;
UPDATE nw_runtime_knowledge_sources s SET chunk_set_id=v.chunk_set_id FROM nw_knowledge_document_versions v
 WHERE v.tenant_id=s.source_tenant_id AND v.kb_id=s.source_base_id AND v.source_version=s.source_version;
UPDATE nw_runtime_knowledge_chunks c SET chunk_set_id=s.chunk_set_id FROM nw_runtime_knowledge_sources s
 WHERE s.tenant_id=c.tenant_id AND s.binding_id=c.binding_id AND s.source_id=c.source_id AND s.source_version=c.source_version;
ALTER TABLE nw_runtime_knowledge_sources ALTER COLUMN chunk_set_id SET NOT NULL,
 ADD CONSTRAINT runtime_knowledge_source_set_uq UNIQUE(tenant_id,binding_id,source_id,source_version,chunk_set_id),
 ADD CONSTRAINT runtime_knowledge_source_version_fk FOREIGN KEY(source_tenant_id,source_base_id,source_version,chunk_set_id)
 REFERENCES nw_knowledge_document_versions(tenant_id,kb_id,source_version,chunk_set_id) ON DELETE RESTRICT;
ALTER TABLE nw_runtime_knowledge_chunks DROP COLUMN content, DROP CONSTRAINT nw_runtime_knowledge_chunks_pkey,
 ALTER COLUMN chunk_set_id SET NOT NULL,
 ADD CONSTRAINT skillforge_runtime_knowledge_chunks_pkey PRIMARY KEY(tenant_id,binding_id,source_id,source_version,chunk_id),
 ADD CONSTRAINT runtime_knowledge_chunk_source_set_fk FOREIGN KEY(tenant_id,binding_id,source_id,source_version,chunk_set_id)
 REFERENCES nw_runtime_knowledge_sources(tenant_id,binding_id,source_id,source_version,chunk_set_id) ON DELETE RESTRICT,
 ADD CONSTRAINT runtime_knowledge_chunk_reference_fk FOREIGN KEY(source_tenant_id,chunk_set_id,chunk_index,source_chunk_id,source_document_id)
 REFERENCES nw_knowledge_chunks(tenant_id,chunk_set_id,chunk_index,id,document_id) ON DELETE RESTRICT;
ALTER TABLE nw_runtime_knowledge_sources ENABLE TRIGGER knowledge_source_immutable;
ALTER TABLE nw_runtime_knowledge_chunks ENABLE TRIGGER knowledge_chunk_immutable;
CREATE INDEX runtime_knowledge_source_owner_reference ON nw_runtime_knowledge_sources(source_tenant_id,source_base_id,source_version,chunk_set_id);
CREATE INDEX runtime_knowledge_chunk_owner_reference ON nw_runtime_knowledge_chunks(source_tenant_id,chunk_set_id,chunk_index,source_chunk_id,source_document_id);

ALTER TABLE nw_knowledge_rebuild_batches ADD CONSTRAINT skillforge_knowledge_rebuild_batches_tenant_uq UNIQUE(tenant_id,id);
CREATE TABLE public.nw_knowledge_rebuild_batch_embeddings (
 tenant_id bigint NOT NULL, batch_id bigint NOT NULL, position integer NOT NULL, embedding_id bigint NOT NULL,
 CONSTRAINT skillforge_knowledge_rebuild_batch_embeddings_pkey PRIMARY KEY(tenant_id,batch_id,position),
 CONSTRAINT skillforge_knowledge_rebuild_batch_embeddings_position_ck CHECK(position>=0),
 CONSTRAINT skillforge_knowledge_rebuild_batch_embeddings_batch_fk FOREIGN KEY(tenant_id,batch_id) REFERENCES nw_knowledge_rebuild_batches(tenant_id,id) ON DELETE RESTRICT,
 CONSTRAINT skillforge_knowledge_rebuild_batch_embeddings_embedding_fk FOREIGN KEY(tenant_id,embedding_id) REFERENCES nw_knowledge_chunk_embeddings(tenant_id,id) ON DELETE RESTRICT
);
CREATE INDEX skillforge_knowledge_rebuild_batch_embeddings_reference ON nw_knowledge_rebuild_batch_embeddings(tenant_id,embedding_id);
-- 旧READY批次在保留原调用身份的前提下将向量移入唯一所有者。
-- embedding id 使用专属identity序列的负值，避免与正数雪花身份冲突。
CREATE SEQUENCE knowledge_embedding_backfill_ids AS bigint START WITH -1 INCREMENT BY -1 MAXVALUE -1;
DO $$ DECLARE row_value record; body_id bigint; vector_id bigint; BEGIN
 FOR row_value IN SELECT b.tenant_id,b.id AS batch_id,e.ordinality::integer-1 AS position,e.value #>> '{}' AS vector_text,
      c.content_id,j.target_model_id,j.target_model_revision,j.target_dimensions
      FROM nw_knowledge_rebuild_batches b JOIN nw_knowledge_rebuild_jobs j ON j.tenant_id=b.tenant_id AND j.id=b.job_id
      JOIN nw_knowledge_rebuild_items i ON i.tenant_id=b.tenant_id AND i.id=b.item_id
      CROSS JOIN LATERAL jsonb_array_elements(b.vectors) WITH ORDINALITY e(value,ordinality)
      JOIN knowledge_chunk_values c ON c.tenant_id=i.tenant_id AND c.document_id=i.document_id AND c.source_version=i.source_version
       AND c.chunk_index=b.batch_index*20+e.ordinality::integer-1
      WHERE b.status='READY' LOOP
   SELECT id INTO vector_id FROM nw_knowledge_chunk_embeddings WHERE tenant_id=row_value.tenant_id AND content_id=row_value.content_id
    AND model_id=row_value.target_model_id AND model_revision=row_value.target_model_revision AND dimensions=row_value.target_dimensions
    AND embedding=row_value.vector_text::vector ORDER BY id LIMIT 1;
   IF vector_id IS NULL THEN vector_id=nextval('knowledge_embedding_backfill_ids');
    INSERT INTO nw_knowledge_chunk_embeddings(id,tenant_id,content_id,model_id,model_revision,dimensions,digest,embedding)
    VALUES(vector_id,row_value.tenant_id,row_value.content_id,row_value.target_model_id,row_value.target_model_revision,row_value.target_dimensions,
      encode(sha256(convert_to((row_value.vector_text::vector)::text,'UTF8')),'hex'),row_value.vector_text::vector);
   END IF;
   INSERT INTO nw_knowledge_rebuild_batch_embeddings(tenant_id,batch_id,position,embedding_id) VALUES(row_value.tenant_id,row_value.batch_id,row_value.position,vector_id);
 END LOOP;
 IF EXISTS(SELECT 1 FROM nw_knowledge_rebuild_batches b WHERE b.status='READY'
    AND (b.vectors IS NULL OR jsonb_array_length(b.vectors)<>(SELECT count(*) FROM nw_knowledge_rebuild_batch_embeddings e WHERE e.tenant_id=b.tenant_id AND e.batch_id=b.id)))
 THEN RAISE EXCEPTION 'knowledge_rebuild_vector_backfill_incomplete'; END IF;
END $$;
DROP SEQUENCE knowledge_embedding_backfill_ids;
ALTER TABLE nw_knowledge_rebuild_batches DROP COLUMN vectors;
ALTER TABLE nw_knowledge_rebuild_batches ENABLE TRIGGER knowledge_rebuild_receipt_immutable;
DELETE FROM nw_knowledge_rebuild_batch_embeddings e USING nw_knowledge_rebuild_batches b,nw_knowledge_rebuild_jobs j
 WHERE e.tenant_id=b.tenant_id AND e.batch_id=b.id AND j.tenant_id=b.tenant_id AND j.id=b.job_id AND j.status='PUBLISHED';

-- 外部投影可能在来源提交后进行；进行中和未确认调用保留来源，防止回收后迟到索引复活。
CREATE TABLE nw_knowledge_index_projection_holds (
 id bigint NOT NULL,tenant_id bigint NOT NULL,kb_id bigint NOT NULL,source_version varchar(128) NOT NULL,
 status varchar(16) NOT NULL,created_at timestamptz NOT NULL DEFAULT now(),
 CONSTRAINT skillforge_knowledge_index_projection_holds_pkey PRIMARY KEY(id),
 CONSTRAINT skillforge_knowledge_index_projection_holds_status_ck CHECK(status IN('PENDING','UNKNOWN')),
 CONSTRAINT skillforge_knowledge_index_projection_holds_source_fk FOREIGN KEY(tenant_id,kb_id,source_version)
 REFERENCES nw_knowledge_document_versions(tenant_id,kb_id,source_version) ON DELETE RESTRICT
);
CREATE INDEX skillforge_knowledge_index_projection_holds_source ON nw_knowledge_index_projection_holds(tenant_id,kb_id,source_version);
CREATE TABLE nw_knowledge_index_cleanup_intents (
 tenant_id bigint NOT NULL,kb_id bigint NOT NULL,source_version varchar(128) NOT NULL,
 attempt bigint NOT NULL DEFAULT 0,lease_until timestamptz,next_attempt_at timestamptz NOT NULL DEFAULT now(),
 CONSTRAINT skillforge_knowledge_index_cleanup_intents_pkey PRIMARY KEY(tenant_id,kb_id,source_version),
 CONSTRAINT skillforge_knowledge_index_cleanup_intents_tenant_fk FOREIGN KEY(tenant_id) REFERENCES nw_tenants(id) ON DELETE RESTRICT,
 CONSTRAINT skillforge_knowledge_index_cleanup_intents_attempt_ck CHECK(attempt>=0)
);
CREATE INDEX skillforge_knowledge_index_cleanup_intents_due ON nw_knowledge_index_cleanup_intents(tenant_id,next_attempt_at,kb_id,source_version);

-- ready版本不可原地更新；DELETE只允许同锁内无任何精确消费者的历史版本。
CREATE OR REPLACE FUNCTION public.guard_ready_knowledge_source_version() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='DELETE' THEN
   PERFORM 1 FROM nw_knowledge_bases WHERE tenant_id=OLD.tenant_id AND id=OLD.kb_id FOR UPDATE;
   IF EXISTS(SELECT 1 FROM nw_knowledge_documents d WHERE d.tenant_id=OLD.tenant_id AND d.id=OLD.document_id AND d.current_source_version=OLD.source_version)
      OR EXISTS(SELECT 1 FROM nw_knowledge_base_revisions r WHERE r.tenant_id=OLD.tenant_id AND r.kb_id=OLD.kb_id AND jsonb_exists(r.source_versions,OLD.source_version))
      OR EXISTS(SELECT 1 FROM nw_runtime_knowledge_sources s WHERE s.source_tenant_id=OLD.tenant_id AND s.source_base_id=OLD.kb_id AND s.source_version=OLD.source_version)
      OR EXISTS(SELECT 1 FROM nw_knowledge_rebuild_items i WHERE i.tenant_id=OLD.tenant_id AND i.document_id=OLD.document_id AND (i.source_version=OLD.source_version OR i.target_source_version=OLD.source_version))
      OR EXISTS(SELECT 1 FROM nw_knowledge_index_projection_holds h WHERE h.tenant_id=OLD.tenant_id AND h.kb_id=OLD.kb_id AND h.source_version=OLD.source_version)
   THEN RAISE EXCEPTION 'knowledge_source_version_referenced'; END IF;
   RETURN OLD;
 END IF;
 IF TG_OP='UPDATE' AND OLD.status='ready' THEN RAISE EXCEPTION 'ready knowledge source version is immutable'; END IF;
 IF NEW.status='ready' AND (NEW.chunk_count<=0 OR NEW.chunk_count<>(SELECT count(*) FROM nw_knowledge_chunks c WHERE c.tenant_id=NEW.tenant_id AND c.chunk_set_id=NEW.chunk_set_id)
   OR NEW.chunk_count-1 IS DISTINCT FROM (SELECT max(chunk_index) FROM nw_knowledge_chunks c WHERE c.tenant_id=NEW.tenant_id AND c.chunk_set_id=NEW.chunk_set_id)
   OR EXISTS(SELECT 1 FROM nw_knowledge_chunks c JOIN nw_knowledge_chunk_embeddings e ON e.tenant_id=c.tenant_id AND e.id=c.embedding_id
      WHERE c.tenant_id=NEW.tenant_id AND c.chunk_set_id=NEW.chunk_set_id AND (e.model_id,e.model_revision,e.dimensions)
      IS DISTINCT FROM (NEW.embedding_model_config_id,NEW.embedding_model_revision,NEW.embedding_dimensions)))
 THEN RAISE EXCEPTION 'knowledge_source_content_set_incomplete'; END IF;
 RETURN NEW;
END $$;
CREATE OR REPLACE FUNCTION public.guard_ready_knowledge_chunk() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE row_value nw_knowledge_chunks; BEGIN
 IF TG_OP='UPDATE' THEN RAISE EXCEPTION 'knowledge_chunk_reference_immutable'; END IF;
 row_value=CASE WHEN TG_OP='DELETE' THEN OLD ELSE NEW END;
 PERFORM 1 FROM nw_knowledge_chunk_sets WHERE tenant_id=row_value.tenant_id AND id=row_value.chunk_set_id FOR UPDATE;
 IF EXISTS(SELECT 1 FROM nw_knowledge_document_versions v WHERE v.tenant_id=row_value.tenant_id AND v.chunk_set_id=row_value.chunk_set_id AND v.status='ready')
 THEN RAISE EXCEPTION 'ready knowledge source chunks are immutable'; END IF;
 IF TG_OP='DELETE' THEN RETURN OLD; END IF; RETURN NEW;
END $$;
ALTER TABLE nw_knowledge_document_versions ENABLE TRIGGER knowledge_document_version_ready_immutable;
DROP TRIGGER knowledge_document_version_ready_immutable ON nw_knowledge_document_versions;
CREATE TRIGGER knowledge_document_version_ready_immutable BEFORE INSERT OR UPDATE OR DELETE ON nw_knowledge_document_versions
 FOR EACH ROW EXECUTE FUNCTION public.guard_ready_knowledge_source_version();
ALTER TABLE nw_knowledge_document_versions ENABLE TRIGGER skillforge_knowledge_document_versions_rebuild_frozen;
ALTER TABLE nw_knowledge_chunks ENABLE TRIGGER knowledge_chunk_ready_immutable;

CREATE FUNCTION public.guard_knowledge_content_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'knowledge_content_immutable'; END $$;
DO $$ DECLARE name text; BEGIN
 FOREACH name IN ARRAY ARRAY['nw_knowledge_document_contents','nw_knowledge_chunk_contents','nw_knowledge_chunk_embeddings','nw_knowledge_chunk_sets'] LOOP
  EXECUTE format('CREATE TRIGGER knowledge_content_immutable BEFORE UPDATE ON public.%I FOR EACH ROW EXECUTE FUNCTION public.guard_knowledge_content_immutable()',name);
 END LOOP;
 FOREACH name IN ARRAY ARRAY['nw_knowledge_storage_budgets','nw_knowledge_document_contents','nw_knowledge_chunk_contents','nw_knowledge_chunk_embeddings','nw_knowledge_chunk_sets','nw_knowledge_rebuild_batch_embeddings','nw_knowledge_index_projection_holds','nw_knowledge_index_cleanup_intents'] LOOP
  EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY',name);
  EXECUTE format('ALTER TABLE public.%I FORCE ROW LEVEL SECURITY',name);
  EXECUTE format('CREATE POLICY knowledge_storage_tenant ON public.%I USING(tenant_id=NULLIF(current_setting(''app.tenant_id'',true),'''')::bigint) WITH CHECK(tenant_id=NULLIF(current_setting(''app.tenant_id'',true),'''')::bigint)',name);
 END LOOP;
END $$;

-- 批次READY引用与回执一起封闭；只有PUBLISHED才允许解除暂存，未知/失败/取消保留恢复材料。
CREATE FUNCTION public.guard_knowledge_rebuild_embedding_reference() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE batch nw_knowledge_rebuild_batches; job nw_knowledge_rebuild_jobs; row_value nw_knowledge_rebuild_batch_embeddings; BEGIN
 IF TG_OP='UPDATE' THEN RAISE EXCEPTION 'knowledge_rebuild_vector_reference_immutable'; END IF;
 row_value=CASE WHEN TG_OP='DELETE' THEN OLD ELSE NEW END;
 SELECT * INTO batch FROM nw_knowledge_rebuild_batches WHERE tenant_id=row_value.tenant_id AND id=row_value.batch_id FOR UPDATE;
 SELECT * INTO job FROM nw_knowledge_rebuild_jobs WHERE tenant_id=batch.tenant_id AND id=batch.job_id;
 IF TG_OP='DELETE' THEN
   IF job.status<>'PUBLISHED' THEN RAISE EXCEPTION 'knowledge_rebuild_staging_recovery_protected'; END IF;
   RETURN OLD;
 END IF;
 IF batch.status IS DISTINCT FROM 'STARTED' OR job.status IS DISTINCT FROM 'RUNNING' THEN RAISE EXCEPTION 'knowledge_rebuild_staging_sealed'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER knowledge_rebuild_embedding_reference_immutable BEFORE INSERT OR UPDATE OR DELETE ON nw_knowledge_rebuild_batch_embeddings
 FOR EACH ROW EXECUTE FUNCTION public.guard_knowledge_rebuild_embedding_reference();

COMMENT ON TABLE nw_knowledge_storage_budgets IS '知识领域租户总预算及容量事务锁；NULL额度表示尚未配置，不默认短TTL或隐式删除';
COMMENT ON TABLE nw_knowledge_document_contents IS '同租户同文档原始正文唯一所有者；保留原格式/摘要，版本按content_id精确引用';
COMMENT ON TABLE nw_knowledge_chunk_contents IS '同租户同文档不可变分块正文唯一所有者；摘要命中仍要求完整正文相同';
COMMENT ON TABLE nw_knowledge_chunk_embeddings IS '冻结模型空间的不可变向量唯一所有者；版本和重建批次均只引用精确身份';
COMMENT ON TABLE nw_knowledge_chunk_sets IS '不可变有序分块集合；标题元数据版本复用此身份而不复制集合或正文';
COMMENT ON TABLE nw_knowledge_rebuild_batch_embeddings IS '成功Embedding批次顺序引用；UNKNOWN/失败/取消继续保留，发布后解除暂存';
COMMENT ON VIEW knowledge_chunk_values IS 'PG16 invoker只读知识版本检索投影；RLS及正文权威仍归底层知识领域表';
COMMENT ON COLUMN nw_knowledge_document_versions.content_id IS '精确不可变原始正文身份，标题修订和向量重建复用';
COMMENT ON COLUMN nw_knowledge_document_versions.chunk_set_id IS '精确不可变分块集合身份；检索source_version仍取本版本';
COMMENT ON COLUMN nw_knowledge_chunks.content_id IS '同租户同文档分块正文引用';
COMMENT ON COLUMN nw_knowledge_chunks.embedding_id IS '同租户同正文冻结模型向量引用；NULL为仅关键词分块';
COMMENT ON COLUMN nw_knowledge_chunks.chunk_set_id IS '所属集合；ready之后成员不可原地修改';
COMMENT ON TABLE nw_runtime_knowledge_chunks IS 'Runtime冻结的租户、来源版本、集合和分块精确引用；不再复制完整正文';
COMMENT ON COLUMN nw_runtime_knowledge_chunks.chunk_set_id IS '精确冻结集合，复用正文仍保持版本与出处独立';
COMMENT ON COLUMN nw_runtime_knowledge_sources.chunk_set_id IS '原ready版本集合身份，外键保护历史解析';
COMMENT ON COLUMN nw_knowledge_storage_budgets.tenant_id IS '所属机构身份，也是新增正文与回收的事务行锁';
COMMENT ON COLUMN nw_knowledge_storage_budgets.max_content_bytes IS '唯一原文/分块UTF-8字节及向量浮点载荷总上限；NULL为只观测该维度';
COMMENT ON COLUMN nw_knowledge_storage_budgets.max_chunk_bodies IS '唯一分块正文数量上限；NULL为只观测该维度';
COMMENT ON COLUMN nw_knowledge_document_contents.id IS '不可变原始文档正文身份';
COMMENT ON COLUMN nw_knowledge_document_contents.tenant_id IS '可信所属机构，不能跨机构摘要复用';
COMMENT ON COLUMN nw_knowledge_document_contents.document_id IS '正文所属稳定文档，复用仅限该文档';
COMMENT ON COLUMN nw_knowledge_document_contents.format IS '原始正文编码格式，本契约为text/plain UTF-8';
COMMENT ON COLUMN nw_knowledge_document_contents.digest IS '完整UTF-8正文SHA-256；历史NULL正文以空字节摘要辅助查找但仍按NULL全值区分';
COMMENT ON COLUMN nw_knowledge_document_contents.content IS '不可变完整原文；保留历史NULL与空文本差异';
COMMENT ON COLUMN nw_knowledge_chunk_contents.id IS '不可变分块正文身份';
COMMENT ON COLUMN nw_knowledge_chunk_contents.tenant_id IS '可信所属机构，强制RLS';
COMMENT ON COLUMN nw_knowledge_chunk_contents.document_id IS '正文所属稳定文档，防止越过文档所有权复用';
COMMENT ON COLUMN nw_knowledge_chunk_contents.format IS '不可变分块正文格式，本契约为text/plain UTF-8';
COMMENT ON COLUMN nw_knowledge_chunk_contents.digest IS '完整UTF-8分块正文SHA-256，仅作同范围候选查找';
COMMENT ON COLUMN nw_knowledge_chunk_contents.content IS '不可变完整分块正文；由版本集合引用';
COMMENT ON COLUMN nw_knowledge_chunk_embeddings.id IS '不可变向量正文身份';
COMMENT ON COLUMN nw_knowledge_chunk_embeddings.tenant_id IS '可信所属机构，强制RLS';
COMMENT ON COLUMN nw_knowledge_chunk_embeddings.content_id IS '所属租户分块正文身份，外键拒绝跨租户引用';
COMMENT ON COLUMN nw_knowledge_chunk_embeddings.model_id IS '生成向量的精确模型配置身份';
COMMENT ON COLUMN nw_knowledge_chunk_embeddings.model_revision IS '生成向量的精确不可变模型修订，大于0';
COMMENT ON COLUMN nw_knowledge_chunk_embeddings.dimensions IS '浮点向量维度，必须与实际vector_dims相同';
COMMENT ON COLUMN nw_knowledge_chunk_embeddings.digest IS 'PostgreSQL规范vector文本的SHA-256，归一化数字表示后检索仍核对完整向量';
COMMENT ON COLUMN nw_knowledge_chunk_embeddings.embedding IS '不可变pgvector浮点正文，暂存批次与正式集合共享该所有者';
COMMENT ON COLUMN nw_knowledge_chunk_sets.id IS '不可变集合身份；元数据标题修订复用此身份';
COMMENT ON COLUMN nw_knowledge_chunk_sets.tenant_id IS '所属机构身份，强制RLS';
COMMENT ON COLUMN nw_knowledge_chunk_sets.kb_id IS '所属知识库，与文档归属复合外键一致';
COMMENT ON COLUMN nw_knowledge_chunk_sets.document_id IS '所属稳定文档身份';
COMMENT ON COLUMN nw_knowledge_rebuild_batch_embeddings.tenant_id IS '批次与向量共同所属机构';
COMMENT ON COLUMN nw_knowledge_rebuild_batch_embeddings.batch_id IS '原真实模型调用批次身份；回执仍归原批次';
COMMENT ON COLUMN nw_knowledge_rebuild_batch_embeddings.position IS '批次向量结果顺序，从0开始';
COMMENT ON COLUMN nw_knowledge_rebuild_batch_embeddings.embedding_id IS '唯一知识向量所有者身份，暂存与正式集合同时保护';
COMMENT ON COLUMN nw_runtime_knowledge_sources.source_tenant_id IS '原tenant_id的受约束数值映射，仅供同机构外键核对';
COMMENT ON COLUMN nw_runtime_knowledge_sources.source_base_id IS '原source_id的数值映射，仅供精确知识库版本外键';
COMMENT ON COLUMN nw_runtime_knowledge_chunks.source_tenant_id IS '冻结tenant_id的数值映射，仅供所属机构外键';
COMMENT ON COLUMN nw_runtime_knowledge_chunks.source_chunk_id IS '冻结chunk_id的数值映射，外键保护原分块关系';
COMMENT ON COLUMN nw_runtime_knowledge_chunks.source_document_id IS '冻结document_id的数值映射，外键校验原分块所属文档';
COMMENT ON TABLE nw_knowledge_index_projection_holds IS '索引网络进行中或结果未确认的来源持有者；未确认不按TTL释放，防止回收后迟到投影复活';
COMMENT ON COLUMN nw_knowledge_index_projection_holds.id IS '本次精确索引操作持有者身份';
COMMENT ON COLUMN nw_knowledge_index_projection_holds.tenant_id IS '所属机构，不能由外部请求正文提供';
COMMENT ON COLUMN nw_knowledge_index_projection_holds.kb_id IS '精确来源所属知识库';
COMMENT ON COLUMN nw_knowledge_index_projection_holds.source_version IS '本次索引的不可变来源版本；外键保护版本及内容';
COMMENT ON COLUMN nw_knowledge_index_projection_holds.status IS 'PENDING为可能进行中，UNKNOWN为结果未确认；两者均保护原来源';
COMMENT ON COLUMN nw_knowledge_index_projection_holds.created_at IS '持有者登记时间，只作排查，不作为自动过期条件';
COMMENT ON TABLE nw_knowledge_index_cleanup_intents IS '来源回收同事务登记的精确ES投影删除意图；确认后删除，失败有界重试';
COMMENT ON COLUMN nw_knowledge_index_cleanup_intents.tenant_id IS '已授权回收来源的原机构身份';
COMMENT ON COLUMN nw_knowledge_index_cleanup_intents.kb_id IS '已退役来源的原知识库身份，不随业务对象删除而丢意图';
COMMENT ON COLUMN nw_knowledge_index_cleanup_intents.source_version IS '已退役精确来源版本，不删除共享chunk的其他版本投影';
COMMENT ON COLUMN nw_knowledge_index_cleanup_intents.attempt IS '领取代次，迟到删除回执须命中同代次才收尾';
COMMENT ON COLUMN nw_knowledge_index_cleanup_intents.lease_until IS '技术领取租约，不是正文或UNKNOWN保留期限';
COMMENT ON COLUMN nw_knowledge_index_cleanup_intents.next_attempt_at IS '有界删除失败重试的下一可领取时刻';
