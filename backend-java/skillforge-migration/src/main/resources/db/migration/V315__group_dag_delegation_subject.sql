-- 现有DAG与委派沿GROUP主体使用原领域账本；执行状态仍只归typed ExecutionStore。
-- 个人会话存储、记忆、旧执行账本保持原约束，不因群领域日志而全面放宽。
ALTER TABLE public.agent_runtime_session_streams
    ALTER COLUMN end_user_id DROP NOT NULL,
    DROP CONSTRAINT agent_runtime_session_streams_target_check,
    DROP CONSTRAINT agent_runtime_session_stream_owner_root_fkey,
    ADD COLUMN subject_kind text GENERATED ALWAYS AS (CASE WHEN target_kind='GROUP' THEN 'GROUP' ELSE 'USER' END) STORED NOT NULL,
    ADD COLUMN subject_id text GENERATED ALWAYS AS (CASE WHEN target_kind='GROUP' THEN target_id ELSE end_user_id::text END) STORED NOT NULL,
    ADD CONSTRAINT domain_stream_subject_ck CHECK (
        (target_kind IN ('AGENT','TEAM') AND end_user_id IS NOT NULL AND end_user_id>0)
        OR (target_kind='GROUP' AND end_user_id IS NULL)),
    ADD CONSTRAINT domain_stream_subject_uq UNIQUE(tenant_id,target_kind,target_id,session_id,subject_kind,subject_id),
    ADD CONSTRAINT domain_stream_subject_root_fk FOREIGN KEY(tenant_id,target_kind,target_id,session_id,subject_kind,subject_id)
        REFERENCES public.agent_runtime_session_owners(tenant_id,target_kind,target_id,session_id,subject_kind,subject_id);

ALTER TABLE public.agent_runtime_session_events
    ALTER COLUMN end_user_id DROP NOT NULL,
    DROP CONSTRAINT agent_runtime_session_events_target_check,
    DROP CONSTRAINT agent_runtime_session_event_owner_fkey,
    ADD COLUMN subject_kind text GENERATED ALWAYS AS (CASE WHEN target_kind='GROUP' THEN 'GROUP' ELSE 'USER' END) STORED NOT NULL,
    ADD COLUMN subject_id text GENERATED ALWAYS AS (CASE WHEN target_kind='GROUP' THEN target_id ELSE end_user_id::text END) STORED NOT NULL,
    ADD CONSTRAINT domain_events_subject_ck CHECK (
        (target_kind IN ('AGENT','TEAM') AND end_user_id IS NOT NULL AND end_user_id>0)
        OR (target_kind='GROUP' AND end_user_id IS NULL)),
    ADD CONSTRAINT group_domain_events_only_ck CHECK (target_kind<>'GROUP' OR payload_schema_id='dag.work.v1'
        OR (event_type LIKE 'plan.%' AND payload_schema_id='skillforge.'||event_type||'.v1')),
    ADD CONSTRAINT domain_event_subject_owner_fk FOREIGN KEY(tenant_id,target_kind,target_id,session_id,subject_kind,subject_id)
        REFERENCES public.agent_runtime_session_streams(tenant_id,target_kind,target_id,session_id,subject_kind,subject_id);

ALTER TABLE public.agent_runtime_dag_works
    ALTER COLUMN end_user_id DROP NOT NULL,
    ADD COLUMN subject_kind text GENERATED ALWAYS AS (CASE WHEN target_kind='GROUP' THEN 'GROUP' ELSE 'USER' END) STORED NOT NULL,
    ADD COLUMN subject_id text GENERATED ALWAYS AS (CASE WHEN target_kind='GROUP' THEN target_id ELSE end_user_id::text END) STORED NOT NULL,
    ADD CONSTRAINT dag_work_subject_ck CHECK (
        (target_kind IN ('AGENT','TEAM') AND end_user_id IS NOT NULL AND end_user_id>0)
        OR (target_kind='GROUP' AND end_user_id IS NULL)),
    ADD CONSTRAINT dag_work_subject_root_fk FOREIGN KEY(tenant_id,target_kind,target_id,session_id,subject_kind,subject_id)
        REFERENCES public.agent_runtime_session_owners(tenant_id,target_kind,target_id,session_id,subject_kind,subject_id);

-- 仅回填可由同一原typed Run证明的来源元数据；保留触发器原启用模式，迁移失败整体回滚。
CREATE TEMP TABLE group_delegation_source_triggers ON COMMIT DROP AS
 SELECT c.relname AS table_name,t.tgname AS trigger_name,t.tgenabled AS enabled_mode
 FROM pg_trigger t JOIN pg_class c ON c.oid=t.tgrelid JOIN pg_namespace n ON n.oid=c.relnamespace
 WHERE n.nspname='public' AND c.relname IN ('agent_runtime_delegations','agent_runtime_delegation_waits')
  AND NOT t.tgisinternal AND (t.tgtype::integer & 16)<>0;
DO $$ DECLARE saved record; BEGIN
 FOR saved IN SELECT * FROM group_delegation_source_triggers LOOP
  EXECUTE format('ALTER TABLE public.%I DISABLE TRIGGER %I',saved.table_name,saved.trigger_name);
 END LOOP;
END $$;
DO $$ DECLARE table_name text; BEGIN
 FOREACH table_name IN ARRAY ARRAY['agent_runtime_delegations','agent_runtime_delegation_waits'] LOOP
  EXECUTE format('ALTER TABLE public.%I ALTER COLUMN parent_end_user_id DROP NOT NULL, DROP CONSTRAINT %I',table_name,table_name||'_parent_target_kind_check');
  EXECUTE format('ALTER TABLE public.%I ADD COLUMN parent_requester_kind text, ADD COLUMN parent_requester_end_user_id bigint, ADD COLUMN parent_requester_system_principal_id text, ADD COLUMN parent_source_session_id text, ADD COLUMN parent_source_message_id text, ADD COLUMN parent_origin_ref text',table_name);
  -- 历史个人记录只填已证明的个人请求责任与原输入引用；不猜群作者或新授权。
  EXECUTE format('UPDATE public.%I d SET parent_requester_kind=r.requester_kind,parent_requester_end_user_id=r.requester_end_user_id,parent_requester_system_principal_id=r.requester_system_principal_id,parent_source_session_id=r.source_session_id,parent_source_message_id=r.source_message_id,parent_origin_ref=r.origin_ref FROM public.agent_runtime_execution_runs r WHERE r.tenant_id=d.tenant_id AND r.run_id=d.parent_run_id',table_name);
  EXECUTE format('ALTER TABLE public.%I ALTER COLUMN parent_requester_kind SET NOT NULL,ALTER COLUMN parent_origin_ref SET NOT NULL,
   ADD CONSTRAINT %I CHECK ((parent_target_kind IN (''AGENT'',''TEAM'') AND parent_end_user_id IS NOT NULL AND parent_end_user_id>0) OR (parent_target_kind=''GROUP'' AND parent_end_user_id IS NULL)),
   ADD CONSTRAINT %I CHECK ((parent_requester_kind=''END_USER'' AND parent_requester_end_user_id IS NOT NULL AND parent_requester_end_user_id>0 AND parent_requester_system_principal_id IS NULL) OR (parent_requester_kind=''SYSTEM'' AND parent_requester_end_user_id IS NULL AND parent_requester_system_principal_id IS NOT NULL AND btrim(parent_requester_system_principal_id)<>'''')),
   ADD CONSTRAINT %I CHECK ((parent_source_session_id IS NULL AND parent_source_message_id IS NULL) OR (parent_source_session_id IS NOT NULL AND btrim(parent_source_session_id)<>'''' AND parent_source_message_id IS NOT NULL AND btrim(parent_source_message_id)<>'''')),
   ADD CONSTRAINT %I CHECK (btrim(parent_origin_ref)<>'''')',table_name,table_name||'_subject_ck',table_name||'_requester_ck',table_name||'_source_ck',table_name||'_origin_ck');
 END LOOP;
END $$;

DO $$ DECLARE saved record; BEGIN
 FOR saved IN SELECT * FROM group_delegation_source_triggers LOOP
  EXECUTE format('ALTER TABLE public.%I %s TRIGGER %I',saved.table_name,
   CASE saved.enabled_mode WHEN 'D' THEN 'DISABLE' WHEN 'R' THEN 'ENABLE REPLICA' WHEN 'A' THEN 'ENABLE ALWAYS' ELSE 'ENABLE' END,saved.trigger_name);
 END LOOP;
END $$;

CREATE FUNCTION public.protect_delegation_parent_source() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
 IF (NEW.parent_requester_kind,NEW.parent_requester_end_user_id,NEW.parent_requester_system_principal_id,
     NEW.parent_source_session_id,NEW.parent_source_message_id,NEW.parent_origin_ref)
    IS DISTINCT FROM
    (OLD.parent_requester_kind,OLD.parent_requester_end_user_id,OLD.parent_requester_system_principal_id,
     OLD.parent_source_session_id,OLD.parent_source_message_id,OLD.parent_origin_ref)
 THEN RAISE EXCEPTION 'delegation original requester source is immutable'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER delegation_parent_source_immutable BEFORE UPDATE ON public.agent_runtime_delegations
 FOR EACH ROW EXECUTE FUNCTION public.protect_delegation_parent_source();
CREATE TRIGGER delegation_wait_parent_source_immutable BEFORE UPDATE ON public.agent_runtime_delegation_waits
 FOR EACH ROW EXECUTE FUNCTION public.protect_delegation_parent_source();
