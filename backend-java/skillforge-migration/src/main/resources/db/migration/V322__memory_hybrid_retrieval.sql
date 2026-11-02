-- Memory检索仅保存可重建派生投影；权威owner、状态、时态、revision与来源继续归Memory。
CREATE TABLE public.agent_runtime_memory_search_documents (
 tenant_id text NOT NULL,fact_id text NOT NULL,content_revision bigint NOT NULL,
 projection_version text NOT NULL,analyzer_version text NOT NULL,
 search_text text NOT NULL,text_digest varchar(64) NOT NULL,lexical_terms tsvector NOT NULL,
 run_data text NOT NULL,identity_data text NOT NULL,
 CONSTRAINT memory_search_documents_pk PRIMARY KEY(tenant_id,fact_id,content_revision,projection_version,analyzer_version),
 CONSTRAINT memory_search_documents_fact_fk FOREIGN KEY(tenant_id,fact_id,content_revision)
  REFERENCES public.agent_runtime_memory_fact_revisions(tenant_id,fact_id,revision) ON DELETE RESTRICT,
 CONSTRAINT memory_search_documents_digest_uq UNIQUE(tenant_id,fact_id,content_revision,projection_version,analyzer_version,text_digest),
 CONSTRAINT memory_search_documents_identity_ck CHECK(content_revision=1 AND btrim(projection_version)<>'' AND btrim(analyzer_version)<>''),
 CONSTRAINT memory_search_documents_digest_ck CHECK(text_digest=encode(sha256(convert_to(search_text,'UTF8')),'hex')));
CREATE INDEX memory_search_documents_terms ON public.agent_runtime_memory_search_documents USING gin(lexical_terms);
CREATE TRIGGER memory_search_documents_immutable BEFORE UPDATE ON public.agent_runtime_memory_search_documents
 FOR EACH ROW EXECUTE FUNCTION public.agent_runtime_memory_immutable();
COMMENT ON TABLE public.agent_runtime_memory_search_documents IS '按注册类型有效正文生成的派生词法投影；版本钉住，不从JSON字段名或schema构造检索词';
COMMENT ON COLUMN public.agent_runtime_memory_search_documents.lexical_terms IS '成熟Lucene分析所得lexemes，PG仅承载tsvector/排名，不进行另一套分词';
COMMENT ON COLUMN public.agent_runtime_memory_search_documents.run_data IS '原提交RunIdentity精确持久表示，用于后台模型成本与预算责任';
COMMENT ON COLUMN public.agent_runtime_memory_search_documents.identity_data IS '原已验证身份快照，不代替Worker派发前的当前授权';

CREATE TABLE public.agent_runtime_memory_embeddings (
 tenant_id text NOT NULL,fact_id text NOT NULL,content_revision bigint NOT NULL,projection_version text NOT NULL,analyzer_version text NOT NULL,
 model_id text NOT NULL,model_revision bigint NOT NULL,dimensions integer NOT NULL,text_digest varchar(64) NOT NULL,
 invocation_id text NOT NULL,state varchar(16) NOT NULL DEFAULT 'QUEUED',embedding vector,
 lease_token text,lease_until timestamptz,reason_code text,updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 CONSTRAINT memory_embeddings_pk PRIMARY KEY(tenant_id,fact_id,content_revision,projection_version,analyzer_version,model_id,model_revision,dimensions),
 CONSTRAINT memory_embeddings_document_fk FOREIGN KEY(tenant_id,fact_id,content_revision,projection_version,analyzer_version,text_digest)
 REFERENCES public.agent_runtime_memory_search_documents(tenant_id,fact_id,content_revision,projection_version,analyzer_version,text_digest) ON DELETE RESTRICT,
 CONSTRAINT memory_embeddings_invocation_uq UNIQUE(tenant_id,invocation_id),
 CONSTRAINT memory_embeddings_model_ck CHECK(btrim(model_id)<>'' AND model_revision>0 AND dimensions>0),
 CONSTRAINT memory_embeddings_state_ck CHECK(state IN ('QUEUED','RUNNING','READY','REJECTED','UNKNOWN')),
 CONSTRAINT memory_embeddings_vector_ck CHECK((state='READY' AND embedding IS NOT NULL AND vector_dims(embedding)=dimensions AND vector_norm(embedding)>0)
  OR (state<>'READY' AND embedding IS NULL)),
 CONSTRAINT memory_embeddings_lease_ck CHECK((state='RUNNING' AND lease_token IS NOT NULL AND lease_until IS NOT NULL)
  OR (state<>'RUNNING' AND lease_token IS NULL AND lease_until IS NULL)),
 CONSTRAINT memory_embeddings_digest_ck CHECK(text_digest ~ '^[0-9a-f]{64}$'));
CREATE INDEX memory_embeddings_pending ON public.agent_runtime_memory_embeddings(tenant_id,updated_at,fact_id) WHERE state='QUEUED';
COMMENT ON TABLE public.agent_runtime_memory_embeddings IS '冻结模型空间的派生向量及其唯一持久索引工作；未知调用保留UNKNOWN，不另建队列或盲目重派';
COMMENT ON COLUMN public.agent_runtime_memory_embeddings.invocation_id IS '外调前固定的唯一原模型操作引用，索引租约超时不生成新调用';
COMMENT ON COLUMN public.agent_runtime_memory_embeddings.text_digest IS '原有效正文SHA-256，写回须与document和原claim一致';
COMMENT ON COLUMN public.agent_runtime_memory_embeddings.lease_token IS '只授予原领取者终结权的随机fence；到期RUNNING仅转UNKNOWN';

CREATE TABLE public.agent_runtime_memory_query_embeddings (
 tenant_id text NOT NULL,operation_id varchar(64) NOT NULL,fingerprint varchar(64) NOT NULL,
 invocation_id text NOT NULL,model_id text NOT NULL,model_revision bigint NOT NULL,dimensions integer NOT NULL,
 run_data text NOT NULL,identity_data text NOT NULL,state varchar(16) NOT NULL,
 embedding vector,reason_code text,deadline_at timestamptz NOT NULL,recorded_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 CONSTRAINT memory_query_embeddings_pk PRIMARY KEY(tenant_id,operation_id),
 CONSTRAINT memory_query_embeddings_invocation_uq UNIQUE(tenant_id,invocation_id),
 CONSTRAINT memory_query_embeddings_model_ck CHECK(btrim(model_id)<>'' AND model_revision>0 AND dimensions>0),
 CONSTRAINT memory_query_embeddings_identity_ck CHECK(operation_id ~ '^[0-9a-f]{64}$' AND fingerprint ~ '^[0-9a-f]{64}$'),
 CONSTRAINT memory_query_embeddings_state_ck CHECK(state IN ('RUNNING','READY','REJECTED','UNKNOWN')),
 CONSTRAINT memory_query_embeddings_vector_ck CHECK((state='READY' AND embedding IS NOT NULL AND vector_dims(embedding)=dimensions AND vector_norm(embedding)>0)
  OR (state<>'READY' AND embedding IS NULL)));
COMMENT ON TABLE public.agent_runtime_memory_query_embeddings IS '按原Run/query与请求摘要固定查询向量操作；结果丢失后重用原向量，UNKNOWN拒绝自动重新调用';
COMMENT ON COLUMN public.agent_runtime_memory_query_embeddings.fingerprint IS '完整query/owner/policy/原上下文规范摘要，同操作异请求拒绝';
DO $$ DECLARE table_name text; BEGIN
 FOREACH table_name IN ARRAY ARRAY['agent_runtime_memory_search_documents','agent_runtime_memory_embeddings','agent_runtime_memory_query_embeddings'] LOOP
  EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY',table_name);
  EXECUTE format('ALTER TABLE public.%I FORCE ROW LEVEL SECURITY',table_name);
  EXECUTE format('CREATE POLICY tenant_isolation ON public.%I USING(tenant_id=current_setting(''app.tenant_id'',true)) WITH CHECK(tenant_id=current_setting(''app.tenant_id'',true))',table_name);
 END LOOP;
END $$;
GRANT SELECT,INSERT,UPDATE ON public.agent_runtime_memory_search_documents,public.agent_runtime_memory_embeddings,public.agent_runtime_memory_query_embeddings TO skillforge;
GRANT DELETE ON public.agent_runtime_memory_search_documents,public.agent_runtime_memory_embeddings TO skillforge;
CREATE FUNCTION public.guard_memory_embedding_projection() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
 IF TG_OP='DELETE' THEN
  IF OLD.state IN ('RUNNING','UNKNOWN') THEN RAISE EXCEPTION 'memory_embedding_unresolved_operation_delete_forbidden'; END IF;
  RETURN OLD;
 END IF;
 IF (NEW.tenant_id,NEW.fact_id,NEW.content_revision,NEW.projection_version,NEW.analyzer_version,NEW.model_id,NEW.model_revision,NEW.dimensions,NEW.text_digest,NEW.invocation_id)
 IS DISTINCT FROM (OLD.tenant_id,OLD.fact_id,OLD.content_revision,OLD.projection_version,OLD.analyzer_version,OLD.model_id,OLD.model_revision,OLD.dimensions,OLD.text_digest,OLD.invocation_id)
 OR NOT ((OLD.state='QUEUED' AND NEW.state='RUNNING') OR (OLD.state='RUNNING' AND NEW.state IN ('READY','REJECTED','UNKNOWN'))) THEN
  RAISE EXCEPTION 'memory_embedding_operation_identity_or_state_mismatch';
 END IF;RETURN NEW;
END $$;
CREATE TRIGGER memory_embedding_projection_guard BEFORE UPDATE OR DELETE ON public.agent_runtime_memory_embeddings FOR EACH ROW EXECUTE FUNCTION public.guard_memory_embedding_projection();
CREATE FUNCTION public.guard_memory_query_embedding_operation() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'memory_query_embedding_operation_delete_forbidden'; END IF;
 IF (NEW.tenant_id,NEW.operation_id,NEW.fingerprint,NEW.invocation_id,NEW.model_id,NEW.model_revision,NEW.dimensions,NEW.run_data,NEW.identity_data,NEW.deadline_at,NEW.recorded_at)
 IS DISTINCT FROM (OLD.tenant_id,OLD.operation_id,OLD.fingerprint,OLD.invocation_id,OLD.model_id,OLD.model_revision,OLD.dimensions,OLD.run_data,OLD.identity_data,OLD.deadline_at,OLD.recorded_at)
 OR OLD.state<>'RUNNING' OR NEW.state NOT IN ('READY','REJECTED','UNKNOWN') THEN
  RAISE EXCEPTION 'memory_query_embedding_operation_identity_or_state_mismatch';
 END IF;RETURN NEW;
END $$;
CREATE TRIGGER memory_query_embedding_operation_guard BEFORE UPDATE OR DELETE ON public.agent_runtime_memory_query_embeddings FOR EACH ROW EXECUTE FUNCTION public.guard_memory_query_embedding_operation();
-- 只替换已批准的检索事件codec版本，不保留旧检索契约兼容入口。
ALTER TABLE public.agent_runtime_session_events DROP CONSTRAINT group_domain_events_only_ck;
ALTER TABLE public.agent_runtime_session_events ADD CONSTRAINT group_domain_events_only_ck CHECK (
 target_kind<>'GROUP' OR payload_schema_id='dag.work.v1'
 OR (event_type LIKE 'plan.%' AND payload_schema_id='skillforge.'||event_type||'.v1')
 OR (event_type='group.memory.source' AND payload_schema_id='skillforge.group.memory-source.v1')
 OR (event_type='memory.written' AND payload_schema_id='skillforge.runtime.memory.written.v2')
 OR (event_type='retrieval.performed' AND payload_schema_id='skillforge.runtime.memory.retrieval-performed.v3'));
