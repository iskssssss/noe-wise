-- 群公开来源、前台单槽及可恢复的显式触发；执行仍唯一Runtime worker。
CREATE TABLE public.nw_group_front_slots (
 tenant_id bigint NOT NULL,integration_id varchar(128) NOT NULL,group_binding_id bigint NOT NULL,
 source_sequence bigint NOT NULL DEFAULT 0 CHECK(source_sequence>=0),trigger_sequence bigint NOT NULL DEFAULT 0 CHECK(trigger_sequence>=0),
 active_trigger_id varchar(64),active_run_id varchar(128),
 PRIMARY KEY(tenant_id,integration_id,group_binding_id),
 FOREIGN KEY(tenant_id,integration_id,group_binding_id) REFERENCES nw_group_bindings(tenant_id,integration_id,id),
 CHECK((active_trigger_id IS NULL)=(active_run_id IS NULL)));
CREATE TABLE public.nw_group_source_messages (
 tenant_id bigint NOT NULL,integration_id varchar(128) NOT NULL,group_binding_id bigint NOT NULL,session_id varchar(64) NOT NULL,
 source_message_id varchar(64) NOT NULL CHECK(btrim(source_message_id)<>''),source_sequence bigint NOT NULL CHECK(source_sequence>0),
 source_event_id varchar(256) NOT NULL CHECK(btrim(source_event_id)<>''),source_revision bigint NOT NULL CHECK(source_revision>0),
 author_kind varchar(16) NOT NULL CHECK(author_kind IN('end_user','agent','tool','system')),
 author_end_user_id bigint,author_agent_id bigint,author_run_id varchar(128),source_ref varchar(256),
 author_json jsonb NOT NULL CHECK(jsonb_typeof(author_json)='object'),content_json jsonb NOT NULL CHECK(jsonb_typeof(content_json)='array' AND jsonb_array_length(content_json) BETWEEN 1 AND 100),
 fingerprint varchar(64) NOT NULL CHECK(length(fingerprint)=64),created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 PRIMARY KEY(tenant_id,integration_id,group_binding_id,source_message_id),
 UNIQUE(tenant_id,integration_id,group_binding_id,source_event_id),UNIQUE(tenant_id,integration_id,group_binding_id,source_sequence),
 UNIQUE(tenant_id,integration_id,group_binding_id,source_message_id,source_sequence),
 FOREIGN KEY(tenant_id,integration_id,group_binding_id) REFERENCES nw_group_bindings(tenant_id,integration_id,id),
 FOREIGN KEY(tenant_id,integration_id,author_end_user_id) REFERENCES nw_end_users(tenant_id,external_integration_id,id),
 FOREIGN KEY(tenant_id,author_agent_id) REFERENCES nw_agents(tenant_id,id),
 CHECK(author_json->>'kind' IS NOT DISTINCT FROM author_kind),
 CHECK((author_kind='end_user' AND (author_json->>'end_user_id') IS NOT DISTINCT FROM author_end_user_id::text) OR author_kind<>'end_user'),
 CHECK((author_kind IN('agent','tool') AND (author_json->>'agent_id') IS NOT DISTINCT FROM author_agent_id::text AND (author_json->>'run_id') IS NOT DISTINCT FROM author_run_id) OR author_kind NOT IN('agent','tool')),
 CHECK((author_kind<>'end_user' AND (author_json->>'source_ref') IS NOT DISTINCT FROM source_ref) OR author_kind='end_user'),
 CHECK((author_kind='end_user' AND author_end_user_id IS NOT NULL AND author_agent_id IS NULL AND author_run_id IS NULL AND source_ref IS NULL)
 OR(author_kind IN('agent','tool') AND author_end_user_id IS NULL AND author_agent_id IS NOT NULL AND author_run_id IS NOT NULL AND btrim(author_run_id)<>'' AND source_ref IS NOT NULL AND btrim(source_ref)<>'')
 OR(author_kind='system' AND author_end_user_id IS NULL AND author_agent_id IS NULL AND author_run_id IS NULL AND source_ref IS NOT NULL AND btrim(source_ref)<>'')));
CREATE TABLE public.nw_group_triggers (
 tenant_id bigint NOT NULL,integration_id varchar(128) NOT NULL,group_binding_id bigint NOT NULL,session_id varchar(64) NOT NULL,
 trigger_id varchar(64) NOT NULL CHECK(btrim(trigger_id)<>''),trigger_sequence bigint NOT NULL CHECK(trigger_sequence>0),
 source_message_id varchar(64) NOT NULL,source_sequence bigint NOT NULL CHECK(source_sequence>0),
 requester_end_user_id bigint NOT NULL,agent_id bigint NOT NULL,principal_id varchar(128) NOT NULL,credential_version bigint NOT NULL CHECK(credential_version>0),
 status varchar(16) NOT NULL CHECK(status IN('queued','accepted','rejected')),input_id varchar(128),run_id varchar(128),rejection_code varchar(128),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 PRIMARY KEY(tenant_id,integration_id,group_binding_id,trigger_id),
 UNIQUE(tenant_id,integration_id,group_binding_id,source_message_id),UNIQUE(tenant_id,integration_id,group_binding_id,trigger_sequence),
 UNIQUE(tenant_id,integration_id,group_binding_id,trigger_id,run_id),
 FOREIGN KEY(tenant_id,integration_id,group_binding_id,source_message_id,source_sequence) REFERENCES nw_group_source_messages(tenant_id,integration_id,group_binding_id,source_message_id,source_sequence),
 FOREIGN KEY(tenant_id,integration_id,requester_end_user_id) REFERENCES nw_end_users(tenant_id,external_integration_id,id),
 FOREIGN KEY(tenant_id,agent_id) REFERENCES nw_agents(tenant_id,id),
 CHECK((status='queued' AND input_id IS NULL AND run_id IS NULL AND rejection_code IS NULL)
 OR(status='accepted' AND input_id IS NOT NULL AND btrim(input_id)<>'' AND run_id IS NOT NULL AND btrim(run_id)<>'' AND rejection_code IS NULL)
 OR(status='rejected' AND input_id IS NULL AND run_id IS NULL AND rejection_code IS NOT NULL AND btrim(rejection_code)<>'')));
ALTER TABLE public.nw_group_front_slots ADD CONSTRAINT group_front_slot_active_trigger_fk FOREIGN KEY(tenant_id,integration_id,group_binding_id,active_trigger_id,active_run_id) REFERENCES nw_group_triggers(tenant_id,integration_id,group_binding_id,trigger_id,run_id) DEFERRABLE INITIALLY DEFERRED;
CREATE INDEX skillforge_group_triggers_queue_idx ON public.nw_group_triggers(tenant_id,integration_id,group_binding_id,trigger_sequence) WHERE status='queued';
CREATE TABLE public.nw_group_message_requests (
 tenant_id bigint NOT NULL,integration_id varchar(128) NOT NULL,actor_ref varchar(160) NOT NULL,operation varchar(32) NOT NULL CHECK(operation IN('message.record','trigger.create')),
 group_binding_id bigint NOT NULL,request_key varchar(256) NOT NULL CHECK(btrim(request_key)<>''),fingerprint varchar(64) NOT NULL CHECK(length(fingerprint)=64),response jsonb NOT NULL CHECK(jsonb_typeof(response)='object'),
 recorded_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 PRIMARY KEY(tenant_id,integration_id,actor_ref,operation,group_binding_id,request_key),
 FOREIGN KEY(tenant_id,integration_id,group_binding_id) REFERENCES nw_group_bindings(tenant_id,integration_id,id));
CREATE TABLE public.nw_group_memory_extraction_pending (
 tenant_id bigint NOT NULL,integration_id varchar(128) NOT NULL,group_binding_id bigint NOT NULL,source_message_id varchar(64) NOT NULL,source_sequence bigint NOT NULL,
 status varchar(16) NOT NULL DEFAULT 'pending' CHECK(status='pending'),created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 PRIMARY KEY(tenant_id,integration_id,group_binding_id,source_message_id),
 FOREIGN KEY(tenant_id,integration_id,group_binding_id,source_message_id,source_sequence) REFERENCES nw_group_source_messages(tenant_id,integration_id,group_binding_id,source_message_id,source_sequence));
CREATE FUNCTION public.protect_group_message_fact() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE g record; s record;
BEGIN
 IF TG_OP<>'INSERT' THEN RAISE EXCEPTION 'group_message_fact_immutable' USING ERRCODE='23514'; END IF;
 SELECT * INTO g FROM public.nw_group_bindings WHERE tenant_id=NEW.tenant_id AND integration_id=NEW.integration_id AND id=NEW.group_binding_id;
 IF NOT FOUND THEN RAISE EXCEPTION 'group_message_domain_mismatch' USING ERRCODE='23514'; END IF;
 IF TG_TABLE_NAME IN('nw_group_source_messages','nw_group_triggers') THEN
  IF NEW.session_id<>g.session_id THEN RAISE EXCEPTION 'group_message_domain_mismatch' USING ERRCODE='23514'; END IF;
 END IF;
 IF TG_TABLE_NAME='nw_group_triggers' THEN
  SELECT * INTO s FROM public.nw_group_source_messages WHERE tenant_id=NEW.tenant_id AND integration_id=NEW.integration_id AND group_binding_id=NEW.group_binding_id AND source_message_id=NEW.source_message_id;
  IF NOT FOUND OR s.author_kind<>'end_user' OR s.author_end_user_id<>NEW.requester_end_user_id OR NEW.status<>'queued' THEN RAISE EXCEPTION 'group_trigger_source_invalid' USING ERRCODE='23514'; END IF;
 END IF;
 RETURN NEW;
END $$;
CREATE FUNCTION public.protect_group_trigger_transition() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
 IF TG_OP='DELETE' OR OLD.status<>'queued' OR NEW.status NOT IN('accepted','rejected')
 OR (to_jsonb(NEW)-ARRAY['status','input_id','run_id','rejection_code']) IS DISTINCT FROM (to_jsonb(OLD)-ARRAY['status','input_id','run_id','rejection_code'])
 THEN RAISE EXCEPTION 'group_trigger_transition_invalid' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER group_source_message_immutable BEFORE INSERT OR UPDATE OR DELETE ON public.nw_group_source_messages FOR EACH ROW EXECUTE FUNCTION public.protect_group_message_fact();
CREATE TRIGGER group_message_request_immutable BEFORE INSERT OR UPDATE OR DELETE ON public.nw_group_message_requests FOR EACH ROW EXECUTE FUNCTION public.protect_group_message_fact();
CREATE TRIGGER group_trigger_insert BEFORE INSERT ON public.nw_group_triggers FOR EACH ROW EXECUTE FUNCTION public.protect_group_message_fact();
CREATE TRIGGER group_trigger_transition BEFORE UPDATE OR DELETE ON public.nw_group_triggers FOR EACH ROW EXECUTE FUNCTION public.protect_group_trigger_transition();
DO $$ DECLARE t text; c record; BEGIN
 FOREACH t IN ARRAY ARRAY['nw_group_front_slots','nw_group_source_messages','nw_group_triggers','nw_group_message_requests','nw_group_memory_extraction_pending'] LOOP
  EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY',t);
  EXECUTE format('ALTER TABLE public.%I FORCE ROW LEVEL SECURITY',t);
  EXECUTE format($policy$CREATE POLICY tenant_isolation ON public.%I USING(tenant_id::text=current_setting('app.tenant_id',true)) WITH CHECK(tenant_id::text=current_setting('app.tenant_id',true))$policy$,t);
  EXECUTE format('COMMENT ON TABLE public.%I IS %L',t,'GI03固定机构执行域的群来源、排队准入、前台占用或原始回执；不执行模型');
  FOR c IN SELECT column_name FROM information_schema.columns WHERE table_schema='public' AND table_name=t LOOP
   EXECUTE format('COMMENT ON COLUMN public.%I.%I IS %L',t,c.column_name,'群领域持久事实：'||c.column_name);
  END LOOP;
  FOR c IN SELECT conname,pg_get_constraintdef(oid) AS definition FROM pg_constraint WHERE conrelid=('public.'||t)::regclass LOOP
   EXECUTE format('COMMENT ON CONSTRAINT %I ON public.%I IS %L',c.conname,t,'群身份、来源不可变性、排队状态或组合归属约束；'||c.definition);
  END LOOP;
 END LOOP;
END $$;
COMMENT ON INDEX public.skillforge_group_triggers_queue_idx IS '按稳定触发序读取队首，只有真实Run终态释放前台槽';
COMMENT ON FUNCTION public.protect_group_message_fact() IS '禁止来源与原回执变更，触发来源限同群真实EndUser且作者等于requester';
COMMENT ON FUNCTION public.protect_group_trigger_transition() IS 'queued只可一次转accepted/rejected，所有来源与请求身份不可变';

CREATE FUNCTION public.check_group_trigger_run() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
 IF NEW.status='accepted' AND NOT EXISTS(SELECT 1 FROM public.agent_runtime_execution_runs r
  WHERE r.tenant_id=NEW.tenant_id::text AND r.run_id=NEW.run_id AND r.input_id=NEW.input_id
   AND r.session_id=NEW.session_id AND r.target_kind='GROUP' AND r.target_id=NEW.group_binding_id::text
   AND r.subject_kind='GROUP' AND r.subject_id=NEW.group_binding_id::text AND r.agent_id=NEW.agent_id::text
   AND r.requester_kind='END_USER' AND r.requester_end_user_id=NEW.requester_end_user_id
   AND r.source_session_id=NEW.session_id AND r.source_message_id=NEW.source_message_id
   AND r.origin_ref='group-trigger:'||NEW.trigger_id)
 THEN RAISE EXCEPTION 'group_trigger_requires_original_typed_run' USING ERRCODE='23514'; END IF;
 RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER group_trigger_run_committed AFTER INSERT OR UPDATE ON public.nw_group_triggers DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION public.check_group_trigger_run();
CREATE FUNCTION public.protect_group_front_slot() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'group_front_slot_delete_forbidden' USING ERRCODE='23514'; END IF;
 IF TG_OP='INSERT' THEN
  IF NEW.source_sequence<>0 OR NEW.trigger_sequence<>0 OR NEW.active_trigger_id IS NOT NULL THEN RAISE EXCEPTION 'group_front_slot_initial_invalid' USING ERRCODE='23514'; END IF;
  RETURN NEW;
 END IF;
 IF (NEW.tenant_id,NEW.integration_id,NEW.group_binding_id) IS DISTINCT FROM (OLD.tenant_id,OLD.integration_id,OLD.group_binding_id)
 OR NEW.source_sequence NOT BETWEEN OLD.source_sequence AND OLD.source_sequence+1
 OR NEW.trigger_sequence NOT BETWEEN OLD.trigger_sequence AND OLD.trigger_sequence+1
 OR (NEW.source_sequence<>OLD.source_sequence AND NEW.trigger_sequence<>OLD.trigger_sequence)
 OR (OLD.active_run_id IS NOT NULL AND NEW.active_run_id IS DISTINCT FROM OLD.active_run_id AND NEW.active_run_id IS NOT NULL)
 THEN RAISE EXCEPTION 'group_front_slot_transition_invalid' USING ERRCODE='23514'; END IF;
 IF OLD.active_run_id IS NOT NULL AND NEW.active_run_id IS NULL AND NOT EXISTS(SELECT 1 FROM public.agent_runtime_execution_runs r
  WHERE r.tenant_id=OLD.tenant_id::text AND r.run_id=OLD.active_run_id AND r.phase='FINISHED')
 THEN RAISE EXCEPTION 'group_front_slot_requires_terminal' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER group_front_slot_transition BEFORE INSERT OR UPDATE OR DELETE ON public.nw_group_front_slots FOR EACH ROW EXECUTE FUNCTION public.protect_group_front_slot();
COMMENT ON FUNCTION public.check_group_trigger_run() IS '提交时要求accepted触发精确引用同事务真实typed Group Run/requester/source/origin';
COMMENT ON FUNCTION public.protect_group_front_slot() IS '来源与触发序号分别单调，活动Run不得换绑，只有真实不可逆FINISHED可释放';
