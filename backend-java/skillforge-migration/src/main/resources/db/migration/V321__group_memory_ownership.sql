-- 目标 MemoryOwner：Session 与群事实不再伪装为用户/服务 Agent。
-- 不转换旧事实或旧二进制；存量切换需另行授权。
DO $$ BEGIN
 IF EXISTS (SELECT 1 FROM public.agent_runtime_memory_fact_revisions
             WHERE revision=1 AND (fact_data IS NULL OR octet_length(fact_data)<8
              OR substring(fact_data FROM 5 FOR 4)<>decode('00000003','hex'))) THEN
  RAISE EXCEPTION 'memory_owner_migration_requires_current_v3_facts_or_empty_store';
 END IF;
END $$;

-- V219已按Session target重建时移除旧source_owner/scope_owner外键；此处只替换仍存在的head身份外键。
ALTER TABLE public.agent_runtime_memory_fact_heads DROP CONSTRAINT fk_agent_runtime_memory_head_revision;
ALTER TABLE public.agent_runtime_memory_fact_revisions DROP CONSTRAINT uq_agent_runtime_memory_revision_identity;

ALTER TABLE public.agent_runtime_memory_fact_revisions
 ALTER COLUMN end_user_id DROP NOT NULL,
 ADD COLUMN group_binding_id text,
 ADD COLUMN subject_end_user_id bigint,
 DROP CONSTRAINT ck_agent_runtime_memory_revision_owner,
 DROP CONSTRAINT ck_agent_runtime_memory_revision_scope,
 DROP CONSTRAINT ck_agent_runtime_memory_revision_session_scope;
ALTER TABLE public.agent_runtime_memory_fact_heads
 ALTER COLUMN end_user_id DROP NOT NULL,
 ADD COLUMN group_binding_id text,
 ADD COLUMN subject_end_user_id bigint,
 DROP CONSTRAINT ck_agent_runtime_memory_head_identity,
 DROP CONSTRAINT ck_agent_runtime_memory_head_scope;

ALTER TABLE public.agent_runtime_memory_fact_revisions
 ADD CONSTRAINT ck_memory_revision_typed_owner CHECK (
 (scope='SESSION' AND session_id IS NOT NULL AND btrim(session_id)<>'' AND end_user_id IS NULL AND agent_id IS NULL AND group_binding_id IS NULL AND subject_end_user_id IS NULL)
 OR (scope='USER_AGENT' AND end_user_id IS NOT NULL AND end_user_id>0 AND agent_id IS NOT NULL AND btrim(agent_id)<>'' AND session_id IS NULL AND group_binding_id IS NULL AND subject_end_user_id IS NULL)
 OR (scope='USER' AND end_user_id IS NOT NULL AND end_user_id>0 AND agent_id IS NULL AND session_id IS NULL AND group_binding_id IS NULL AND subject_end_user_id IS NULL)
 OR (scope='GROUP' AND group_binding_id IS NOT NULL AND btrim(group_binding_id)<>'' AND end_user_id IS NULL AND agent_id IS NULL AND session_id IS NULL AND subject_end_user_id IS NULL)
 OR (scope='GROUP_MEMBER' AND group_binding_id IS NOT NULL AND btrim(group_binding_id)<>'' AND subject_end_user_id IS NOT NULL AND subject_end_user_id>0 AND end_user_id IS NULL AND agent_id IS NULL AND session_id IS NULL)),
 ADD CONSTRAINT ck_memory_revision_identity CHECK (revision>0 AND btrim(type_id)<>'');
COMMENT ON CONSTRAINT ck_memory_revision_typed_owner ON public.agent_runtime_memory_fact_revisions IS 'MemoryOwner封闭分支：Session仅会话；用户/用户Agent为个人；群无用户Agent；成员对象单列';
COMMENT ON COLUMN public.agent_runtime_memory_fact_revisions.end_user_id IS '仅USER/USER_AGENT的真实归属用户；SESSION与群为NULL';
COMMENT ON COLUMN public.agent_runtime_memory_fact_revisions.agent_id IS '仅USER_AGENT归属Agent；其他scope为NULL';
COMMENT ON COLUMN public.agent_runtime_memory_fact_revisions.session_id IS '仅SESSION的精确会话归属';
COMMENT ON COLUMN public.agent_runtime_memory_fact_revisions.group_binding_id IS '仅GROUP/GROUP_MEMBER的稳定群生命周期引用，不绑定执行Agent';
COMMENT ON COLUMN public.agent_runtime_memory_fact_revisions.subject_end_user_id IS '仅GROUP_MEMBER的被描述对象，不作为请求者或群归属用户';

ALTER TABLE public.agent_runtime_memory_fact_heads
 ADD CONSTRAINT ck_memory_head_typed_owner CHECK (
 (scope='SESSION' AND session_id IS NOT NULL AND btrim(session_id)<>'' AND end_user_id IS NULL AND agent_id IS NULL AND group_binding_id IS NULL AND subject_end_user_id IS NULL)
 OR (scope='USER_AGENT' AND end_user_id IS NOT NULL AND end_user_id>0 AND agent_id IS NOT NULL AND btrim(agent_id)<>'' AND session_id IS NULL AND group_binding_id IS NULL AND subject_end_user_id IS NULL)
 OR (scope='USER' AND end_user_id IS NOT NULL AND end_user_id>0 AND agent_id IS NULL AND session_id IS NULL AND group_binding_id IS NULL AND subject_end_user_id IS NULL)
 OR (scope='GROUP' AND group_binding_id IS NOT NULL AND btrim(group_binding_id)<>'' AND end_user_id IS NULL AND agent_id IS NULL AND session_id IS NULL AND subject_end_user_id IS NULL)
 OR (scope='GROUP_MEMBER' AND group_binding_id IS NOT NULL AND btrim(group_binding_id)<>'' AND subject_end_user_id IS NOT NULL AND subject_end_user_id>0 AND end_user_id IS NULL AND agent_id IS NULL AND session_id IS NULL)),
 ADD CONSTRAINT ck_memory_head_identity CHECK (current_revision>0 AND btrim(type_id)<>'');
COMMENT ON CONSTRAINT ck_memory_head_typed_owner ON public.agent_runtime_memory_fact_heads IS 'MemoryOwner封闭分支：Session仅会话；用户/用户Agent为个人；群无用户Agent；成员对象单列';
COMMENT ON COLUMN public.agent_runtime_memory_fact_heads.end_user_id IS '仅USER/USER_AGENT的真实归属用户；SESSION与群为NULL';
COMMENT ON COLUMN public.agent_runtime_memory_fact_heads.agent_id IS '仅USER_AGENT归属Agent；其他scope为NULL';
COMMENT ON COLUMN public.agent_runtime_memory_fact_heads.session_id IS '仅SESSION的精确会话归属';
COMMENT ON COLUMN public.agent_runtime_memory_fact_heads.group_binding_id IS '仅GROUP/GROUP_MEMBER的稳定群生命周期引用，不绑定执行Agent';
COMMENT ON COLUMN public.agent_runtime_memory_fact_heads.subject_end_user_id IS '仅GROUP_MEMBER的被描述对象，不作为请求者或群归属用户';

ALTER TABLE public.agent_runtime_memory_fact_revisions
 ADD CONSTRAINT ck_memory_revision_session_source CHECK (scope<>'SESSION' OR session_id=source_session_id);
ALTER TABLE public.agent_runtime_memory_fact_heads
 ADD CONSTRAINT fk_memory_head_current_revision FOREIGN KEY(tenant_id,fact_id,current_revision)
 REFERENCES public.agent_runtime_memory_fact_revisions(tenant_id,fact_id,revision) DEFERRABLE INITIALLY DEFERRED;
COMMENT ON CONSTRAINT fk_memory_head_current_revision ON public.agent_runtime_memory_fact_heads IS '当前修订必须存在；完整可空owner由延迟owner守卫使用IS NOT DISTINCT FROM核验';

CREATE OR REPLACE FUNCTION public.agent_runtime_memory_head_guard()
RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'agent runtime memory head cannot be deleted'; END IF;
 IF (NEW.tenant_id,NEW.fact_id,NEW.end_user_id,NEW.agent_id,NEW.session_id,NEW.group_binding_id,NEW.subject_end_user_id,NEW.scope,NEW.type_id)
 IS DISTINCT FROM (OLD.tenant_id,OLD.fact_id,OLD.end_user_id,OLD.agent_id,OLD.session_id,OLD.group_binding_id,OLD.subject_end_user_id,OLD.scope,OLD.type_id)
 OR NEW.current_revision<>OLD.current_revision+1 THEN
  RAISE EXCEPTION 'agent runtime memory head identity or revision jump';
 END IF;
 RETURN NEW;
END $$;

CREATE OR REPLACE FUNCTION public.assert_memory_typed_owner()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE r public.agent_runtime_memory_fact_revisions; h public.agent_runtime_memory_fact_heads;
BEGIN
 SELECT * INTO h FROM public.agent_runtime_memory_fact_heads WHERE tenant_id=NEW.tenant_id AND fact_id=NEW.fact_id;
 IF NOT FOUND THEN RAISE EXCEPTION 'memory fact requires current head'; END IF;
 IF TG_TABLE_NAME='agent_runtime_memory_fact_heads' THEN
  SELECT * INTO r FROM public.agent_runtime_memory_fact_revisions WHERE tenant_id=h.tenant_id AND fact_id=h.fact_id AND revision=h.current_revision;
 ELSE r:=NEW;
 END IF;
 IF (r.end_user_id,r.agent_id,r.session_id,r.group_binding_id,r.subject_end_user_id,r.scope,r.type_id)
 IS DISTINCT FROM (h.end_user_id,h.agent_id,h.session_id,h.group_binding_id,h.subject_end_user_id,h.scope,h.type_id) THEN
  RAISE EXCEPTION 'memory current head typed owner mismatch';
 END IF;
 RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER memory_revision_typed_owner_guard AFTER INSERT ON public.agent_runtime_memory_fact_revisions
 DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION public.assert_memory_typed_owner();
CREATE CONSTRAINT TRIGGER memory_head_typed_owner_guard AFTER INSERT OR UPDATE ON public.agent_runtime_memory_fact_heads
 DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION public.assert_memory_typed_owner();
COMMENT ON FUNCTION public.assert_memory_typed_owner() IS '首版与状态修订、current head的全部nullable owner字段精确一致，避免复合外键NULL跳过检查';

ALTER TABLE public.agent_runtime_memory_fact_sources
 ALTER COLUMN agent_id DROP NOT NULL,
 DROP CONSTRAINT ck_agent_runtime_memory_source_identity,
 ADD CONSTRAINT ck_memory_source_target_owner CHECK (source_ordinal>=0 AND btrim(source_session_id)<>'' AND btrim(source_event_id)<>''
 AND ((source_target_kind='GROUP' AND agent_id IS NULL) OR (source_target_kind IN ('AGENT','TEAM') AND agent_id IS NOT NULL AND btrim(agent_id)<>'')));
COMMENT ON COLUMN public.agent_runtime_memory_fact_sources.agent_id IS '个人来源保留原执行Agent；共享GROUP来源为NULL，不能绑定当前服务Agent';

CREATE OR REPLACE FUNCTION public.assert_agent_runtime_memory_sources()
RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
 IF NOT EXISTS (SELECT 1 FROM public.agent_runtime_memory_fact_sources s
  WHERE s.tenant_id=NEW.tenant_id AND s.fact_id=NEW.fact_id AND s.revision=NEW.content_revision) THEN
  RAISE EXCEPTION 'memory fact revision requires committed source events';
 END IF;
 IF EXISTS (SELECT 1 FROM public.agent_runtime_memory_fact_sources s
  JOIN public.agent_runtime_session_owners o ON o.tenant_id=s.tenant_id AND o.target_kind=s.source_target_kind
   AND o.target_id=s.source_target_id AND o.session_id=s.source_session_id
  WHERE s.tenant_id=NEW.tenant_id AND s.fact_id=NEW.fact_id AND s.revision=NEW.content_revision
   AND (s.source_session_id<>NEW.source_session_id
    OR (NEW.scope='SESSION' AND NEW.session_id<>s.source_session_id)
    OR (NEW.scope IN ('USER','USER_AGENT') AND (o.subject_kind<>'USER' OR o.end_user_id IS DISTINCT FROM NEW.end_user_id))
    OR (NEW.scope='USER_AGENT' AND s.agent_id IS DISTINCT FROM NEW.agent_id)
    OR (NEW.scope IN ('GROUP','GROUP_MEMBER') AND (s.source_target_kind<>'GROUP' OR s.source_target_id<>NEW.group_binding_id)))) THEN
  RAISE EXCEPTION 'memory fact typed owner source mismatch';
 END IF;
 RETURN NULL;
END $$;
COMMENT ON FUNCTION public.assert_agent_runtime_memory_sources() IS '复用正文来源闭包并核对个人/群归属；群来源不以服务Agent限定，描述对象不冒充作者';
CREATE INDEX ix_memory_group_current ON public.agent_runtime_memory_fact_heads(tenant_id,group_binding_id,scope,subject_end_user_id,type_id) WHERE group_binding_id IS NOT NULL;
CREATE INDEX ix_memory_session_current ON public.agent_runtime_memory_fact_heads(tenant_id,session_id,type_id) WHERE scope='SESSION';
COMMENT ON INDEX public.ix_memory_group_current IS '按当前群和明确描述对象前置过滤，再执行类型/相关性检索';
COMMENT ON INDEX public.ix_memory_session_current IS 'SESSION按会话归属过滤，不依赖用户或Agent';

-- 群 Memory 的真实来源与现有Runtime journal使用正式codec白名单。
ALTER TABLE public.agent_runtime_session_events DROP CONSTRAINT group_domain_events_only_ck;
ALTER TABLE public.agent_runtime_session_events ADD CONSTRAINT group_domain_events_only_ck CHECK (
 target_kind<>'GROUP' OR payload_schema_id='dag.work.v1'
 OR (event_type LIKE 'plan.%' AND payload_schema_id='skillforge.'||event_type||'.v1')
 OR (event_type='group.memory.source' AND payload_schema_id='skillforge.group.memory-source.v1')
 OR (event_type='memory.written' AND payload_schema_id='skillforge.runtime.memory.written.v2')
 OR (event_type='retrieval.performed' AND payload_schema_id='skillforge.runtime.memory.retrieval-performed.v2'));
COMMENT ON CONSTRAINT group_domain_events_only_ck ON public.agent_runtime_session_events IS '群目录仅允许已交付DAG/Plan与群Memory来源、写入、检索正式codec事件';

-- 大正文仍复用当前Session物理存储，群owner通过生成主体键绑定同一会话。
ALTER TABLE public.agent_runtime_session_event_bodies
 DROP CONSTRAINT session_event_bodies_owner_fk,
 DROP CONSTRAINT session_event_bodies_identity,
 ALTER COLUMN end_user_id DROP NOT NULL,
 ADD COLUMN subject_kind text GENERATED ALWAYS AS (CASE WHEN target_kind='GROUP' THEN 'GROUP' ELSE 'USER' END) STORED NOT NULL,
 ADD COLUMN subject_id text GENERATED ALWAYS AS (CASE WHEN target_kind='GROUP' THEN target_id ELSE end_user_id::text END) STORED NOT NULL,
 ADD CONSTRAINT session_event_bodies_typed_identity CHECK (btrim(tenant_id)<>'' AND btrim(session_id)<>'' AND btrim(target_id)<>''
  AND ((target_kind IN ('AGENT','TEAM') AND end_user_id IS NOT NULL AND end_user_id>0) OR (target_kind='GROUP' AND end_user_id IS NULL))),
 ADD CONSTRAINT session_event_bodies_typed_owner_fk FOREIGN KEY(tenant_id,target_kind,target_id,session_id,subject_kind,subject_id)
 REFERENCES public.agent_runtime_session_owners(tenant_id,target_kind,target_id,session_id,subject_kind,subject_id);
COMMENT ON COLUMN public.agent_runtime_session_event_bodies.end_user_id IS '个人Session真实用户；GROUP为NULL，不以执行者代替群owner';
COMMENT ON COLUMN public.agent_runtime_session_event_bodies.subject_kind IS '由target_kind确定的USER或GROUP主体';
COMMENT ON COLUMN public.agent_runtime_session_event_bodies.subject_id IS '个人用户或群生命周期的生成主体键';

CREATE OR REPLACE FUNCTION public.agent_runtime_session_event_payload(requested_tenant text, requested_session text,
    requested_target_kind text, requested_target text, requested_end_user bigint, requested_run text,
    requested_event text, requested_type text, requested_schema text, stored text)
RETURNS text LANGUAGE plpgsql STABLE SECURITY INVOKER AS $$
DECLARE reference record; restored text := stored; expected_digest text; resolved integer := 0; token text;
BEGIN
    IF public.agent_runtime_session_archive_is_marker(stored) THEN
        RETURN public.agent_runtime_session_archived_event_payload(requested_tenant,requested_session,
            requested_target_kind,requested_target,requested_end_user,requested_event,stored);
    END IF;
    IF requested_type<>'memory.written' OR requested_schema<>'skillforge.runtime.memory.written.v2'
       OR NOT stored ~ '"canonicalJson":"@runtime-session-content:[a-f0-9]{64}"' THEN
        RETURN public.agent_runtime_session_output_payload(requested_tenant,requested_session,requested_target_kind,
            requested_target,requested_end_user,requested_run,requested_schema,stored);
    END IF;
    FOR reference IN SELECT body.content_hash,body.canonical_content,ref.original_payload_digest
        FROM public.agent_runtime_session_event_body_refs ref JOIN public.agent_runtime_session_event_bodies body
            ON (body.tenant_id,body.target_kind,body.target_id,body.session_id,body.content_hash)
                =(ref.tenant_id,ref.target_kind,ref.target_id,ref.session_id,ref.content_hash)
        WHERE ref.tenant_id=requested_tenant AND ref.target_kind=requested_target_kind AND ref.target_id=requested_target
            AND ref.session_id=requested_session AND ref.event_id=requested_event AND body.end_user_id IS NOT DISTINCT FROM requested_end_user LOOP
        IF expected_digest IS NOT NULL AND expected_digest<>reference.original_payload_digest THEN
            RAISE EXCEPTION 'session_event_body_reference_rejected';
        END IF;
        expected_digest := reference.original_payload_digest;
        IF encode(sha256(convert_to('skillforge.runtime.session.memory-event-text.v1','UTF8')||decode('00','hex')||convert_to(reference.canonical_content,'UTF8')),'hex')
           <>reference.content_hash THEN RAISE EXCEPTION 'session_event_body_reference_rejected'; END IF;
        token := '"canonicalJson":"@runtime-session-content:'||reference.content_hash||'"';
        IF strpos(restored,token)=0 THEN RAISE EXCEPTION 'session_event_body_reference_rejected'; END IF;
        restored := replace(restored,token,'"canonicalJson":'||reference.canonical_content);
        resolved := resolved+1;
    END LOOP;
    IF resolved=0 OR restored ~ '"canonicalJson":"@runtime-session-content:[a-f0-9]{64}"'
       OR encode(sha256(convert_to(requested_schema,'UTF8')||decode('00','hex')||convert_to(restored,'UTF8')),'hex')
          IS DISTINCT FROM expected_digest THEN RAISE EXCEPTION 'session_event_body_reference_rejected'; END IF;
    RETURN restored;
END;
$$;
COMMENT ON FUNCTION public.agent_runtime_session_event_payload IS '当前v2 Memory大正文从同Owner/event精确还原并验证摘要；群owner支持NULL用户，不从当前head替换正文';
