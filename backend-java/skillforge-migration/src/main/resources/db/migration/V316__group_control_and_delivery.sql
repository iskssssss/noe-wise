-- 群控制只拥有门禁、传播与发送来源资格；执行仍属于唯一Runtime，实际发送账本属于发送进程。
CREATE TABLE public.nw_group_control_groups (
 tenant_id bigint NOT NULL,integration_id varchar(128) NOT NULL,group_binding_id bigint NOT NULL,
 revision bigint NOT NULL DEFAULT 0 CHECK(revision>=0),latest_trigger_sequence bigint NOT NULL DEFAULT 0 CHECK(latest_trigger_sequence>=0),
 CONSTRAINT skillforge_group_control_groups_pk PRIMARY KEY(tenant_id,integration_id,group_binding_id),
 CONSTRAINT skillforge_group_control_groups_binding_fk FOREIGN KEY(tenant_id,integration_id,group_binding_id) REFERENCES nw_group_bindings(tenant_id,integration_id,id));
CREATE TABLE public.nw_group_control_states (
 tenant_id bigint NOT NULL,integration_id varchar(128) NOT NULL,group_binding_id bigint NOT NULL,target_ref varchar(128) NOT NULL,
 target_kind varchar(16) GENERATED ALWAYS AS(CASE WHEN target_ref='*' THEN 'group' ELSE 'run' END) STORED,
 scope varchar(32) NOT NULL CHECK(scope IN('execution','business_write','send','memory_extraction')),paused boolean NOT NULL,revision bigint NOT NULL CHECK(revision>0),
 CONSTRAINT skillforge_group_control_states_pk PRIMARY KEY(tenant_id,integration_id,group_binding_id,target_ref,scope),
 CONSTRAINT skillforge_group_control_states_group_fk FOREIGN KEY(tenant_id,integration_id,group_binding_id) REFERENCES nw_group_control_groups);
CREATE TABLE public.nw_group_control_commands (
 tenant_id bigint NOT NULL,integration_id varchar(128) NOT NULL,group_binding_id bigint NOT NULL,command_id varchar(128) NOT NULL CHECK(btrim(command_id)<>''),target_ref varchar(128) NOT NULL,
 fingerprint varchar(64) NOT NULL CHECK(fingerprint ~ '^[0-9a-f]{64}$'),expected_revision bigint NOT NULL CHECK(expected_revision>=0),accepted_revision bigint,
 desired_state varchar(16) CHECK(desired_state IN('paused','running')),actor_principal varchar(128) NOT NULL,
 result_kind varchar(32) NOT NULL CHECK(result_kind IN('accepted','rejected','terminal_not_accepted')),result_ref varchar(256) NOT NULL,response_json jsonb NOT NULL CHECK(jsonb_typeof(response_json)='object' AND octet_length(response_json::text)<=4194304),
 local_intent_id varchar(128),created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 CONSTRAINT skillforge_group_control_commands_pk PRIMARY KEY(tenant_id,integration_id,group_binding_id,command_id),
 CONSTRAINT skillforge_group_control_commands_revision_uk UNIQUE(tenant_id,integration_id,group_binding_id,accepted_revision),
 CONSTRAINT skillforge_group_control_commands_group_fk FOREIGN KEY(tenant_id,integration_id,group_binding_id) REFERENCES nw_group_control_groups,
 CONSTRAINT skillforge_group_control_commands_result_ck CHECK((result_kind='accepted' AND accepted_revision>0 AND desired_state IS NOT NULL) OR(result_kind<>'accepted' AND accepted_revision IS NULL)));
CREATE TABLE public.nw_group_control_command_scopes (
 tenant_id bigint NOT NULL,integration_id varchar(128) NOT NULL,group_binding_id bigint NOT NULL,command_id varchar(128) NOT NULL,
 scope varchar(32) NOT NULL CONSTRAINT skillforge_group_control_command_scopes_scope_ck CHECK(scope IN('execution','business_write','send','memory_extraction')),
 CONSTRAINT skillforge_group_control_command_scopes_pk PRIMARY KEY(tenant_id,integration_id,group_binding_id,command_id,scope),
 CONSTRAINT skillforge_group_control_command_scopes_command_fk FOREIGN KEY(tenant_id,integration_id,group_binding_id,command_id) REFERENCES nw_group_control_commands);
CREATE TABLE public.nw_group_control_boundaries (
 tenant_id bigint NOT NULL,integration_id varchar(128) NOT NULL,group_binding_id bigint NOT NULL,boundary_ref varchar(256) NOT NULL,target_ref varchar(128) NOT NULL,
 through_sequence bigint CHECK(through_sequence>=0),control_revision bigint NOT NULL CHECK(control_revision>=0),created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 CONSTRAINT skillforge_group_control_boundaries_pk PRIMARY KEY(tenant_id,integration_id,group_binding_id,boundary_ref),
 CONSTRAINT skillforge_group_control_boundaries_group_fk FOREIGN KEY(tenant_id,integration_id,group_binding_id) REFERENCES nw_group_control_groups);
ALTER TABLE public.nw_service_principals ADD CONSTRAINT skillforge_service_principals_domain_principal_uk UNIQUE(tenant_id,integration_id,principal_id);
COMMENT ON CONSTRAINT skillforge_service_principals_domain_principal_uk ON public.nw_service_principals IS '端点注册以完整机构/执行域/固定主体组合引用认证身份';
CREATE TABLE public.nw_group_sender_endpoints (
 tenant_id bigint NOT NULL,integration_id varchar(128) NOT NULL,group_binding_id bigint NOT NULL,endpoint_id varchar(128) NOT NULL CHECK(btrim(endpoint_id)<>''),generation bigint NOT NULL CHECK(generation>0),
 ledger_ref varchar(256) NOT NULL CHECK(btrim(ledger_ref)<>''),principal_id varchar(128) NOT NULL,status varchar(16) NOT NULL CHECK(status IN('REGISTERING','ACTIVE','RETIRING','RETIRED')),
 applied_revision bigint NOT NULL DEFAULT -1 CHECK(applied_revision>=-1),fence_revision bigint NOT NULL DEFAULT 0 CHECK(fence_revision>=0),applied_boundary_ref varchar(256),retirement_receipt_ref varchar(256),
 registered_at timestamptz NOT NULL DEFAULT clock_timestamp(),last_ack_at timestamptz,
 CONSTRAINT skillforge_group_sender_endpoints_pk PRIMARY KEY(tenant_id,integration_id,group_binding_id,endpoint_id,generation),
 CONSTRAINT skillforge_group_sender_endpoints_group_fk FOREIGN KEY(tenant_id,integration_id,group_binding_id) REFERENCES nw_group_control_groups,
 CONSTRAINT skillforge_group_sender_endpoints_principal_fk FOREIGN KEY(tenant_id,integration_id,principal_id) REFERENCES nw_service_principals(tenant_id,integration_id,principal_id),
 CONSTRAINT skillforge_group_sender_endpoints_retired_ck CHECK((status='RETIRED')=(retirement_receipt_ref IS NOT NULL)));
CREATE TABLE public.nw_group_sender_endpoint_flights (
 tenant_id bigint NOT NULL,integration_id varchar(128) NOT NULL,group_binding_id bigint NOT NULL,endpoint_id varchar(128) NOT NULL,generation bigint NOT NULL,
 delivery_id varchar(128) NOT NULL,status varchar(16) NOT NULL CONSTRAINT skillforge_group_sender_endpoint_flights_status_ck CHECK(status IN('DISPATCHING','UNKNOWN')),result_ref varchar(256),
 CONSTRAINT skillforge_group_sender_endpoint_flights_pk PRIMARY KEY(tenant_id,integration_id,group_binding_id,endpoint_id,generation,delivery_id),
 CONSTRAINT skillforge_group_sender_endpoint_flights_endpoint_fk FOREIGN KEY(tenant_id,integration_id,group_binding_id,endpoint_id,generation) REFERENCES nw_group_sender_endpoints);
CREATE TABLE public.nw_group_control_participants (
 tenant_id bigint NOT NULL,integration_id varchar(128) NOT NULL,group_binding_id bigint NOT NULL,command_id varchar(128) NOT NULL,revision bigint NOT NULL CHECK(revision>=0),
 endpoint_id varchar(128) NOT NULL,generation bigint NOT NULL CHECK(generation>=0),status varchar(16) NOT NULL CHECK(status IN('required','missing')),
 CONSTRAINT skillforge_group_control_participants_pk PRIMARY KEY(tenant_id,integration_id,group_binding_id,command_id,endpoint_id,generation),
 CONSTRAINT skillforge_group_control_participants_group_fk FOREIGN KEY(tenant_id,integration_id,group_binding_id) REFERENCES nw_group_control_groups);
CREATE TABLE public.nw_group_control_acks (
 tenant_id bigint NOT NULL,integration_id varchar(128) NOT NULL,group_binding_id bigint NOT NULL,command_id varchar(128) NOT NULL,revision bigint NOT NULL CHECK(revision>=0),
 endpoint_id varchar(128) NOT NULL,generation bigint NOT NULL CHECK(generation>=0),ledger_ref varchar(256) NOT NULL,applied_boundary_ref varchar(256) NOT NULL CHECK(btrim(applied_boundary_ref)<>''),
 fence_revision bigint NOT NULL CHECK(fence_revision>=0),actor_principal varchar(128) NOT NULL,created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 CONSTRAINT skillforge_group_control_acks_pk PRIMARY KEY(tenant_id,integration_id,group_binding_id,command_id,endpoint_id,generation),
 CONSTRAINT skillforge_group_control_acks_participant_fk FOREIGN KEY(tenant_id,integration_id,group_binding_id,command_id,endpoint_id,generation) REFERENCES nw_group_control_participants);
CREATE TABLE public.nw_group_send_eligibilities (
 tenant_id bigint NOT NULL,integration_id varchar(128) NOT NULL,group_binding_id bigint NOT NULL,trigger_id varchar(64) NOT NULL,trigger_sequence bigint NOT NULL CHECK(trigger_sequence>0),registered_revision bigint NOT NULL CHECK(registered_revision>=0),
 CONSTRAINT skillforge_group_send_eligibilities_pk PRIMARY KEY(tenant_id,integration_id,group_binding_id,trigger_id),
 CONSTRAINT skillforge_group_send_eligibilities_sequence_uk UNIQUE(tenant_id,integration_id,group_binding_id,trigger_sequence),
 CONSTRAINT skillforge_group_send_eligibilities_trigger_fk FOREIGN KEY(tenant_id,integration_id,group_binding_id,trigger_id) REFERENCES nw_group_triggers);
CREATE TABLE public.nw_group_deliveries (
 tenant_id bigint NOT NULL,integration_id varchar(128) NOT NULL,group_binding_id bigint NOT NULL,delivery_id varchar(128) NOT NULL,run_id varchar(128) NOT NULL,message_id varchar(256) NOT NULL,message_version bigint NOT NULL CHECK(message_version>0),trigger_id varchar(64) NOT NULL,
 content_digest varchar(64) NOT NULL CHECK(content_digest ~ '^[0-9a-f]{64}$'),created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 CONSTRAINT skillforge_group_deliveries_pk PRIMARY KEY(tenant_id,integration_id,group_binding_id,delivery_id),
 CONSTRAINT skillforge_group_deliveries_message_uk UNIQUE(tenant_id,integration_id,group_binding_id,run_id,message_id,message_version),
 CONSTRAINT skillforge_group_deliveries_eligibility_fk FOREIGN KEY(tenant_id,integration_id,group_binding_id,trigger_id) REFERENCES nw_group_send_eligibilities);
CREATE TABLE public.nw_group_delivery_releases (
 tenant_id bigint NOT NULL,integration_id varchar(128) NOT NULL,group_binding_id bigint NOT NULL,release_id varchar(256) NOT NULL,delivery_id varchar(128) NOT NULL,
 control_revision bigint NOT NULL CHECK(control_revision>=0),actor_principal varchar(128) NOT NULL,created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 CONSTRAINT skillforge_group_delivery_releases_pk PRIMARY KEY(tenant_id,integration_id,group_binding_id,release_id,delivery_id),
 CONSTRAINT skillforge_group_delivery_releases_delivery_fk FOREIGN KEY(tenant_id,integration_id,group_binding_id,delivery_id) REFERENCES nw_group_deliveries);
CREATE TABLE public.nw_group_delivery_release_boundaries (
 tenant_id bigint NOT NULL,integration_id varchar(128) NOT NULL,group_binding_id bigint NOT NULL,release_id varchar(256) NOT NULL,delivery_id varchar(128) NOT NULL,boundary_ref varchar(256) NOT NULL,
 CONSTRAINT skillforge_group_delivery_release_boundaries_pk PRIMARY KEY(tenant_id,integration_id,group_binding_id,release_id,delivery_id,boundary_ref),
 CONSTRAINT skillforge_group_delivery_release_boundaries_release_fk FOREIGN KEY(tenant_id,integration_id,group_binding_id,release_id,delivery_id) REFERENCES nw_group_delivery_releases,
 CONSTRAINT skillforge_group_delivery_release_boundaries_boundary_fk FOREIGN KEY(tenant_id,integration_id,group_binding_id,boundary_ref) REFERENCES nw_group_control_boundaries);
CREATE TABLE public.nw_group_control_requests (
 tenant_id bigint NOT NULL,integration_id varchar(128) NOT NULL,group_binding_id bigint NOT NULL,actor_principal varchar(128) NOT NULL,operation varchar(32) NOT NULL CHECK(operation IN('TERMINALIZE','RELEASE','REGISTER','RETIRE','ACK')),
 request_key varchar(256) NOT NULL,fingerprint varchar(64) NOT NULL CHECK(fingerprint ~ '^[0-9a-f]{64}$'),response_json jsonb NOT NULL CHECK(jsonb_typeof(response_json)='object' AND octet_length(response_json::text)<=4194304),created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 CONSTRAINT skillforge_group_control_requests_pk PRIMARY KEY(tenant_id,integration_id,group_binding_id,actor_principal,operation,request_key),
 CONSTRAINT skillforge_group_control_requests_group_fk FOREIGN KEY(tenant_id,integration_id,group_binding_id) REFERENCES nw_group_control_groups);

-- 不可变事实禁止覆写；状态表仍由所属CAS/世代更新约束推进。
CREATE FUNCTION public.protect_group_control_fact() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
 RAISE EXCEPTION 'group_control_fact_immutable' USING ERRCODE='23514'; END $$;
CREATE FUNCTION public.protect_group_sender_transition() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
 IF TG_OP='INSERT' THEN
  IF EXISTS(SELECT 1 FROM public.nw_group_sender_endpoints e WHERE (e.tenant_id,e.integration_id,e.group_binding_id)=(NEW.tenant_id,NEW.integration_id,NEW.group_binding_id) AND (e.endpoint_id<>NEW.endpoint_id OR e.ledger_ref<>NEW.ledger_ref))
  OR NEW.generation<>(SELECT COALESCE(max(e.generation),0)+1 FROM public.nw_group_sender_endpoints e WHERE (e.tenant_id,e.integration_id,e.group_binding_id,e.endpoint_id)=(NEW.tenant_id,NEW.integration_id,NEW.group_binding_id,NEW.endpoint_id))
  OR NEW.status<>'REGISTERING' OR NEW.applied_revision<>-1 OR NEW.fence_revision<>0
  THEN RAISE EXCEPTION 'group_sender_registration_invalid' USING ERRCODE='23514'; END IF; RETURN NEW;
 END IF;
 IF TG_OP='DELETE' OR (NEW.tenant_id,NEW.integration_id,NEW.group_binding_id,NEW.endpoint_id,NEW.generation,NEW.ledger_ref,NEW.principal_id)
 IS DISTINCT FROM (OLD.tenant_id,OLD.integration_id,OLD.group_binding_id,OLD.endpoint_id,OLD.generation,OLD.ledger_ref,OLD.principal_id)
 OR NEW.applied_revision<OLD.applied_revision OR NEW.fence_revision<OLD.fence_revision
 OR (OLD.status='RETIRED' AND NEW IS DISTINCT FROM OLD)
 OR (OLD.status='RETIRING' AND NEW.status NOT IN('RETIRING','RETIRED'))
 THEN RAISE EXCEPTION 'group_sender_transition_invalid' USING ERRCODE='23514'; END IF; RETURN NEW; END $$;
CREATE TRIGGER group_sender_transition BEFORE INSERT OR UPDATE OR DELETE ON public.nw_group_sender_endpoints FOR EACH ROW EXECUTE FUNCTION public.protect_group_sender_transition();
CREATE FUNCTION public.protect_group_control_boundary() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
 IF TG_OP='DELETE' OR (NEW.tenant_id,NEW.integration_id,NEW.group_binding_id,NEW.boundary_ref,NEW.target_ref,NEW.control_revision,NEW.created_at)
 IS DISTINCT FROM (OLD.tenant_id,OLD.integration_id,OLD.group_binding_id,OLD.boundary_ref,OLD.target_ref,OLD.control_revision,OLD.created_at)
 OR OLD.through_sequence IS NOT NULL OR NEW.through_sequence IS NULL
 THEN RAISE EXCEPTION 'group_control_boundary_immutable' USING ERRCODE='23514'; END IF; RETURN NEW; END $$;
CREATE TRIGGER group_control_boundary_transition BEFORE UPDATE OR DELETE ON public.nw_group_control_boundaries FOR EACH ROW EXECUTE FUNCTION public.protect_group_control_boundary();
CREATE FUNCTION public.protect_group_sender_flight_report() RETURNS trigger LANGUAGE plpgsql AS $$ DECLARE e record; BEGIN
 IF TG_OP='UPDATE' THEN RAISE EXCEPTION 'group_sender_flight_replace_required' USING ERRCODE='23514'; END IF;
 IF TG_OP='INSERT' THEN e:=NEW; ELSE e:=OLD; END IF;
 IF EXISTS(SELECT 1 FROM public.nw_group_sender_endpoints s WHERE (s.tenant_id,s.integration_id,s.group_binding_id,s.endpoint_id,s.generation)=(e.tenant_id,e.integration_id,e.group_binding_id,e.endpoint_id,e.generation) AND s.status='RETIRED')
 THEN RAISE EXCEPTION 'group_retired_flight_report_immutable' USING ERRCODE='23514'; END IF;
 IF TG_OP='DELETE' THEN RETURN OLD; END IF; RETURN NEW; END $$;
CREATE TRIGGER group_sender_flight_report_transition BEFORE INSERT OR UPDATE OR DELETE ON public.nw_group_sender_endpoint_flights FOR EACH ROW EXECUTE FUNCTION public.protect_group_sender_flight_report();
DO $$ DECLARE t text;c record; BEGIN
 FOREACH t IN ARRAY ARRAY['nw_group_control_groups','nw_group_control_states','nw_group_control_commands','nw_group_control_command_scopes','nw_group_control_boundaries','nw_group_sender_endpoints','nw_group_sender_endpoint_flights','nw_group_control_participants','nw_group_control_acks','nw_group_send_eligibilities','nw_group_deliveries','nw_group_delivery_releases','nw_group_delivery_release_boundaries','nw_group_control_requests'] LOOP
  EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY',t); EXECUTE format('ALTER TABLE public.%I FORCE ROW LEVEL SECURITY',t);
  EXECUTE format('CREATE POLICY %I ON public.%I USING(tenant_id::text=current_setting(''app.tenant_id'',true)) WITH CHECK(tenant_id::text=current_setting(''app.tenant_id'',true))',t||'_tenant',t);
  EXECUTE format('COMMENT ON TABLE public.%I IS %L',t,'GI04群控制权威门禁、来源资格或不可变传播/回执；完整Tenant/integration/Group范围');
  FOR c IN SELECT column_name FROM information_schema.columns WHERE table_schema='public' AND table_name=t LOOP EXECUTE format('COMMENT ON COLUMN public.%I.%I IS %L',t,c.column_name,'群控制领域事实：'||c.column_name); END LOOP;
  FOR c IN SELECT conname,pg_get_constraintdef(oid) AS definition FROM pg_constraint WHERE conrelid=('public.'||t)::regclass LOOP EXECUTE format('COMMENT ON CONSTRAINT %I ON public.%I IS %L',c.conname,t,'群控制身份、版本或生命周期不变量；'||c.definition); END LOOP;
 END LOOP;
 FOREACH t IN ARRAY ARRAY['nw_group_control_commands','nw_group_control_command_scopes','nw_group_control_participants','nw_group_control_acks','nw_group_send_eligibilities','nw_group_deliveries','nw_group_delivery_releases','nw_group_delivery_release_boundaries','nw_group_control_requests'] LOOP
  EXECUTE format('CREATE TRIGGER %I BEFORE UPDATE OR DELETE ON public.%I FOR EACH ROW EXECUTE FUNCTION public.protect_group_control_fact()',t||'_immutable',t);
 END LOOP;
END $$;
COMMENT ON FUNCTION public.protect_group_control_fact() IS '原命令、ACK、根资格、delivery及release事实不可覆写';
COMMENT ON FUNCTION public.protect_group_sender_transition() IS '世代身份、ledger与principal不可替换，退役不可复活，应用与隔离版本不得后退';
COMMENT ON FUNCTION public.protect_group_control_boundary() IS '暂停边界只可在恢复时固化上界，禁止改写原来源范围或删除';
COMMENT ON FUNCTION public.protect_group_sender_flight_report() IS '发送端报告逐项类型化保存；退役回执确认后原在途和UNKNOWN集合不可重写';
COMMENT ON COLUMN public.nw_group_control_commands.response_json IS '有界4MiB首次协议data快照；保留参与方/ACK等当时响应，不承担当前门禁状态权威';
COMMENT ON COLUMN public.nw_group_control_requests.response_json IS '有界4MiB首次管理动作协议data快照；换代/控制推进后仍原样重读，不作为当前授权来源';
