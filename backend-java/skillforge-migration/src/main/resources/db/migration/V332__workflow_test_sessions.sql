-- 测试资源归属由后台拥有；普通业务列表和管理入口必须按该关系排除，scope 标签不构成隔离。
ALTER TABLE public.nw_workflow_containers ADD CONSTRAINT workflow_test_container_owner_uq UNIQUE(tenant_id,id);
ALTER TABLE public.nw_capability_definitions ADD CONSTRAINT workflow_test_capability_owner_uq UNIQUE(tenant_id,id);
ALTER TABLE public.nw_capability_versions ADD CONSTRAINT workflow_test_version_owner_uq UNIQUE(tenant_id,id);
ALTER TABLE public.nw_capability_versions ADD CONSTRAINT workflow_test_version_definition_uq UNIQUE(definition_id,id);
ALTER TABLE public.nw_workflow_definition_versions ADD CONSTRAINT workflow_test_definition_container_uq UNIQUE(tenant_id,container_id,id);
ALTER TABLE public.nw_chat_sessions ADD CONSTRAINT workflow_test_session_identity_uq UNIQUE(tenant_id,session_id,agent_id,end_user_id);
ALTER TABLE public.nw_knowledge_base_revisions ADD CONSTRAINT workflow_test_knowledge_version_revision_uq UNIQUE(tenant_id,capability_version_id,revision);
CREATE TABLE public.nw_workflow_test_sessions (
 id bigint NOT NULL,
 tenant_id bigint NOT NULL REFERENCES nw_tenants(id) ON DELETE RESTRICT,
 initiator_member_id bigint NOT NULL REFERENCES nw_tenant_members(id) ON DELETE RESTRICT,
 request_id varchar(128) NOT NULL,
 request_fingerprint varchar(64) NOT NULL,
 source_kind varchar(32) NOT NULL,
 source_container_id bigint NOT NULL REFERENCES nw_workflow_containers(id) ON DELETE RESTRICT,
 source_workflow_id varchar(128) NOT NULL,
 source_draft_revision bigint,
 source_definition_version_id bigint,
 source_hash varchar(64) NOT NULL,
 container_id bigint NOT NULL REFERENCES nw_workflow_containers(id) ON DELETE RESTRICT,
 definition_version_id bigint NOT NULL,
 capability_definition_id bigint NOT NULL REFERENCES nw_capability_definitions(id) ON DELETE RESTRICT,
 capability_version_id bigint NOT NULL REFERENCES nw_capability_versions(id) ON DELETE RESTRICT,
 instance_id bigint NOT NULL,
 installation_revision bigint NOT NULL,
 agent_id bigint NOT NULL REFERENCES nw_agents(id) ON DELETE RESTRICT,
 end_user_id bigint NOT NULL,
 session_id varchar(64) NOT NULL,
 initial_input_id varchar(128) NOT NULL,
 initial_message text NOT NULL,
 parameters jsonb NOT NULL,
 max_business_model_calls integer NOT NULL,
 max_tool_calls integer NOT NULL,
 max_knowledge_calls integer NOT NULL,
 max_total_tokens bigint NOT NULL,
 max_wall_time_seconds bigint NOT NULL,
 status varchar(32) NOT NULL DEFAULT 'ready',
 expires_at timestamptz NOT NULL,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 closed_at timestamptz,
 CONSTRAINT skillforge_workflow_test_sessions_pk PRIMARY KEY(id),
 CONSTRAINT skillforge_workflow_test_sessions_tenant_uq UNIQUE(tenant_id,id),
 CONSTRAINT skillforge_workflow_test_sessions_request_uq UNIQUE(tenant_id,initiator_member_id,request_id),
 CONSTRAINT skillforge_workflow_test_sessions_session_uq UNIQUE(tenant_id,session_id),
 CONSTRAINT skillforge_workflow_test_sessions_agent_uq UNIQUE(tenant_id,agent_id),
 CONSTRAINT skillforge_workflow_test_sessions_user_uq UNIQUE(tenant_id,end_user_id),
 CONSTRAINT skillforge_workflow_test_sessions_container_uq UNIQUE(tenant_id,container_id),
 CONSTRAINT skillforge_workflow_test_sessions_instance_uq UNIQUE(tenant_id,instance_id),
 CONSTRAINT skillforge_workflow_test_sessions_member_fk FOREIGN KEY(tenant_id,initiator_member_id) REFERENCES nw_tenant_members(tenant_id,id),
 CONSTRAINT skillforge_workflow_test_sessions_source_container_fk FOREIGN KEY(tenant_id,source_container_id) REFERENCES nw_workflow_containers(tenant_id,id),
 CONSTRAINT skillforge_workflow_test_sessions_container_fk FOREIGN KEY(tenant_id,container_id) REFERENCES nw_workflow_containers(tenant_id,id),
 CONSTRAINT skillforge_workflow_test_sessions_agent_fk FOREIGN KEY(tenant_id,agent_id) REFERENCES nw_agents(tenant_id,id),
 CONSTRAINT skillforge_workflow_test_sessions_capability_fk FOREIGN KEY(tenant_id,capability_definition_id) REFERENCES nw_capability_definitions(tenant_id,id),
 CONSTRAINT skillforge_workflow_test_sessions_version_fk FOREIGN KEY(tenant_id,capability_version_id) REFERENCES nw_capability_versions(tenant_id,id),
 CONSTRAINT skillforge_workflow_test_sessions_capability_version_fk FOREIGN KEY(capability_definition_id,capability_version_id) REFERENCES nw_capability_versions(definition_id,id),
 CONSTRAINT skillforge_workflow_test_sessions_session_fk FOREIGN KEY(tenant_id,session_id,agent_id,end_user_id) REFERENCES nw_chat_sessions(tenant_id,session_id,agent_id,end_user_id),
 CONSTRAINT skillforge_workflow_test_sessions_source_owner_fk FOREIGN KEY(tenant_id,source_container_id,source_definition_version_id) REFERENCES nw_workflow_definition_versions(tenant_id,container_id,id),
 CONSTRAINT skillforge_workflow_test_sessions_definition_owner_fk FOREIGN KEY(tenant_id,container_id,definition_version_id) REFERENCES nw_workflow_definition_versions(tenant_id,container_id,id),
 CONSTRAINT skillforge_workflow_test_sessions_source_fk FOREIGN KEY(tenant_id,source_definition_version_id) REFERENCES nw_workflow_definition_versions(tenant_id,id),
 CONSTRAINT skillforge_workflow_test_sessions_definition_fk FOREIGN KEY(tenant_id,definition_version_id) REFERENCES nw_workflow_definition_versions(tenant_id,id),
 CONSTRAINT skillforge_workflow_test_sessions_install_fk FOREIGN KEY(tenant_id,instance_id,installation_revision) REFERENCES nw_workflow_installation_revisions(tenant_id,instance_id,installation_revision),
 CONSTRAINT skillforge_workflow_test_sessions_user_fk FOREIGN KEY(tenant_id,end_user_id) REFERENCES nw_end_users(tenant_id,id),
 CONSTRAINT skillforge_workflow_test_sessions_source_ck CHECK((source_kind='draft' AND source_draft_revision IS NOT NULL AND source_draft_revision>0 AND source_definition_version_id IS NULL)
   OR (source_kind='publication' AND source_draft_revision IS NULL AND source_definition_version_id IS NOT NULL)),
 CONSTRAINT skillforge_workflow_test_sessions_identity_ck CHECK(id>0 AND btrim(request_id)<>'' AND btrim(initial_input_id)<>'' AND btrim(initial_message)<>''
   AND request_fingerprint~'^[0-9a-f]{64}$' AND source_hash~'^[0-9a-f]{64}$'),
 CONSTRAINT skillforge_workflow_test_sessions_input_ck CHECK(jsonb_typeof(parameters)='object' AND octet_length(parameters::text)<=262144 AND octet_length(initial_message)<=65536),
 CONSTRAINT skillforge_workflow_test_sessions_budget_ck CHECK(max_business_model_calls BETWEEN 1 AND 16 AND max_tool_calls BETWEEN 1 AND 32
   AND max_knowledge_calls BETWEEN 1 AND 32 AND max_total_tokens BETWEEN 1 AND 64000 AND max_wall_time_seconds BETWEEN 1 AND 300),
 CONSTRAINT skillforge_workflow_test_sessions_status_ck CHECK(status IN ('ready','closing','closed') AND (status='closed')=(closed_at IS NOT NULL)),
 CONSTRAINT skillforge_workflow_test_sessions_expiry_ck CHECK(expires_at>created_at)
);
CREATE TABLE public.nw_workflow_test_capability_bindings (
 tenant_id bigint NOT NULL,
 test_session_id bigint NOT NULL,
 slot varchar(64) NOT NULL,
 capability_type varchar(32) NOT NULL,
 capability_definition_id bigint NOT NULL REFERENCES nw_capability_definitions(id) ON DELETE RESTRICT,
 capability_version_id bigint NOT NULL REFERENCES nw_capability_versions(id) ON DELETE RESTRICT,
 tool_deployment_id bigint REFERENCES nw_tool_deployments(id) ON DELETE RESTRICT,
 tool_deployment_key varchar(128),
 tool_deployment_revision bigint,
 knowledge_revision bigint,
 CONSTRAINT skillforge_workflow_test_capability_bindings_pk PRIMARY KEY(tenant_id,test_session_id,slot),
 CONSTRAINT skillforge_workflow_test_capability_bindings_test_fk FOREIGN KEY(tenant_id,test_session_id) REFERENCES nw_workflow_test_sessions(tenant_id,id) ON DELETE RESTRICT,
 CONSTRAINT skillforge_workflow_test_capability_bindings_version_fk FOREIGN KEY(capability_definition_id,capability_version_id) REFERENCES nw_capability_versions(definition_id,id),
 CONSTRAINT skillforge_workflow_test_capability_bindings_deployment_fk FOREIGN KEY(tenant_id,tool_deployment_id) REFERENCES nw_tool_deployments(tenant_id,id),
 CONSTRAINT skillforge_workflow_test_capability_bindings_knowledge_fk FOREIGN KEY(tenant_id,capability_version_id,knowledge_revision) REFERENCES nw_knowledge_base_revisions(tenant_id,capability_version_id,revision) ON DELETE RESTRICT,
 CONSTRAINT skillforge_workflow_test_capability_bindings_type_ck CHECK(
  (capability_type='TOOL' AND tool_deployment_id IS NOT NULL AND tool_deployment_key IS NOT NULL AND tool_deployment_revision IS NOT NULL AND tool_deployment_revision>0 AND knowledge_revision IS NULL)
  OR (capability_type='KNOWLEDGE_SOURCE' AND knowledge_revision IS NOT NULL AND tool_deployment_id IS NULL AND tool_deployment_key IS NULL AND tool_deployment_revision IS NULL))
);
CREATE INDEX skillforge_workflow_test_sessions_expiry_ix ON public.nw_workflow_test_sessions(tenant_id,status,expires_at) WHERE status!='closed';
COMMENT ON TABLE public.nw_workflow_test_sessions IS '后台创建的隔离Workflow测试资源包及创建幂等回执；不提供执行旁路，不保存Runtime进度';
COMMENT ON TABLE public.nw_workflow_test_capability_bindings IS '测试创建时显式选择的精确能力和部署或知识修订；准入与派发重新求交当前授权';
COMMENT ON COLUMN public.nw_workflow_test_sessions.parameters IS '用户定义的开放Workflow输入文档；创建时校验原input_schema，最多256KiB；与原来源hash一起永久冻结，不包含用途和身份';
COMMENT ON COLUMN public.nw_workflow_test_sessions.initial_input_id IS '唯一允许初始root输入，与正式chat.client_message_id相同；后续仅接受原Pending回复';
COMMENT ON COLUMN public.nw_workflow_test_sessions.source_draft_revision IS '源当前草稿的锁内CAS修订；publication来源为空';
COMMENT ON COLUMN public.nw_workflow_test_sessions.source_definition_version_id IS '用户指定的源不可变发布版本；draft来源为空';
COMMENT ON COLUMN public.nw_workflow_test_sessions.expires_at IS '过期先阻断新派发并请求正式取消，UNKNOWN和活跃回执仍保留';
COMMENT ON COLUMN public.nw_workflow_test_sessions.status IS '资源生命周期ready/closing/closed；不冒充Runtime Run状态，安静终态才closed';
COMMENT ON COLUMN public.nw_workflow_test_sessions.closed_at IS '确认原执行及取消清理安静后归档自有资源的时间；之前为空';
CREATE FUNCTION public.protect_workflow_test_identity() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'workflow test receipt cannot be deleted'; END IF;
 IF (to_jsonb(NEW)-ARRAY['status','updated_at','closed_at']) IS DISTINCT FROM (to_jsonb(OLD)-ARRAY['status','updated_at','closed_at'])
 THEN RAISE EXCEPTION 'workflow test identity and frozen source are immutable'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER skillforge_workflow_test_sessions_immutable BEFORE UPDATE OR DELETE ON public.nw_workflow_test_sessions FOR EACH ROW EXECUTE FUNCTION public.protect_workflow_test_identity();
CREATE FUNCTION public.protect_workflow_test_binding() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
 RAISE EXCEPTION 'workflow test exact bindings are immutable';
END $$;
CREATE TRIGGER workflow_test_bindings_immutable BEFORE UPDATE OR DELETE ON public.nw_workflow_test_capability_bindings FOR EACH ROW EXECUTE FUNCTION public.protect_workflow_test_binding();
ALTER TABLE public.nw_workflow_test_sessions ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.nw_workflow_test_sessions FORCE ROW LEVEL SECURITY;
CREATE POLICY skillforge_workflow_test_sessions_tenant ON public.nw_workflow_test_sessions
 USING(tenant_id::text=current_setting('app.tenant_id',true)) WITH CHECK(tenant_id::text=current_setting('app.tenant_id',true));
ALTER TABLE public.nw_workflow_test_capability_bindings ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.nw_workflow_test_capability_bindings FORCE ROW LEVEL SECURITY;
CREATE POLICY workflow_test_bindings_tenant ON public.nw_workflow_test_capability_bindings
 USING(tenant_id::text=current_setting('app.tenant_id',true)) WITH CHECK(tenant_id::text=current_setting('app.tenant_id',true));
GRANT SELECT,INSERT,UPDATE ON public.nw_workflow_test_sessions TO skillforge;
GRANT SELECT,INSERT ON public.nw_workflow_test_capability_bindings TO skillforge;
