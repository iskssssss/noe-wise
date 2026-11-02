-- 群治理只持有归属、当前成员/服务目录及原命令回执；执行沿原Runtime。
ALTER TABLE public.nw_end_users ADD CONSTRAINT skillforge_end_users_external_identity_unique UNIQUE(tenant_id,external_integration_id,id);
CREATE TABLE public.nw_group_bindings (
 id bigint PRIMARY KEY,tenant_id bigint NOT NULL,integration_id varchar(128) NOT NULL,
 namespace varchar(128) NOT NULL CHECK(length(btrim(namespace))>0),external_group_ref varchar(256) NOT NULL CHECK(length(btrim(external_group_ref))>0),
 lifecycle_ref varchar(128) NOT NULL CHECK(length(btrim(lifecycle_ref))>0),business_scope_id varchar(64) NOT NULL,
 session_id varchar(64) NOT NULL,status varchar(16) NOT NULL CHECK(status IN('active','closed')),revision bigint NOT NULL CHECK(revision>0),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 UNIQUE(tenant_id,integration_id,id),UNIQUE(tenant_id,session_id),UNIQUE(tenant_id,integration_id,session_id,id,business_scope_id),
 UNIQUE(tenant_id,integration_id,namespace,external_group_ref,lifecycle_ref),
 FOREIGN KEY(tenant_id,integration_id) REFERENCES nw_institution_integrations(tenant_id,integration_id),
 FOREIGN KEY(tenant_id,integration_id,business_scope_id) REFERENCES nw_integration_business_scopes(tenant_id,integration_id,scope_id));
CREATE UNIQUE INDEX skillforge_group_bindings_active_external_unique ON public.nw_group_bindings(tenant_id,integration_id,namespace,external_group_ref) WHERE status='active';
ALTER TABLE public.nw_chat_sessions ADD CONSTRAINT skillforge_chat_sessions_group_binding_tuple_unique UNIQUE(tenant_id,integration_id,session_id,group_binding_id,business_scope_id);
ALTER TABLE public.nw_group_bindings ADD CONSTRAINT group_binding_session_fk FOREIGN KEY(tenant_id,integration_id,session_id,id,business_scope_id)
 REFERENCES public.nw_chat_sessions(tenant_id,integration_id,session_id,group_binding_id,business_scope_id) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE public.nw_chat_sessions ADD CONSTRAINT skillforge_chat_sessions_group_binding_fk FOREIGN KEY(tenant_id,integration_id,session_id,group_binding_id,business_scope_id)
 REFERENCES public.nw_group_bindings(tenant_id,integration_id,session_id,id,business_scope_id) DEFERRABLE INITIALLY DEFERRED;
CREATE TABLE public.nw_group_memberships (
 tenant_id bigint NOT NULL,integration_id varchar(128) NOT NULL,group_binding_id bigint NOT NULL,end_user_id bigint NOT NULL,
 role varchar(16) NOT NULL CHECK(role IN('member','manager')),status varchar(16) NOT NULL CHECK(status IN('active','left')),
 revision bigint NOT NULL CHECK(revision>0),source_event_id varchar(256) NOT NULL CHECK(length(btrim(source_event_id))>0),source_revision bigint NOT NULL CHECK(source_revision>0),
 actor_ref varchar(160) NOT NULL,created_at timestamptz NOT NULL DEFAULT clock_timestamp(),updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 PRIMARY KEY(tenant_id,integration_id,group_binding_id,end_user_id),
 FOREIGN KEY(tenant_id,integration_id,group_binding_id) REFERENCES public.nw_group_bindings(tenant_id,integration_id,id),
 FOREIGN KEY(tenant_id,integration_id,end_user_id) REFERENCES public.nw_end_users(tenant_id,external_integration_id,id));
CREATE TABLE public.nw_group_service_agents (
 tenant_id bigint NOT NULL,integration_id varchar(128) NOT NULL,group_binding_id bigint NOT NULL,agent_id bigint NOT NULL,
 status varchar(16) NOT NULL CHECK(status IN('active','removed')),revision bigint NOT NULL CHECK(revision>0),
 source_event_id varchar(256) NOT NULL CHECK(length(btrim(source_event_id))>0),source_revision bigint NOT NULL CHECK(source_revision>0),
 actor_ref varchar(160) NOT NULL,created_at timestamptz NOT NULL DEFAULT clock_timestamp(),updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 PRIMARY KEY(tenant_id,integration_id,group_binding_id,agent_id),
 FOREIGN KEY(tenant_id,integration_id,group_binding_id) REFERENCES public.nw_group_bindings(tenant_id,integration_id,id),
 FOREIGN KEY(tenant_id,agent_id) REFERENCES public.nw_agents(tenant_id,id));
CREATE TABLE public.nw_group_binding_requests (
 tenant_id bigint NOT NULL,integration_id varchar(128) NOT NULL,actor_ref varchar(160) NOT NULL,operation varchar(32) NOT NULL CHECK(operation IN('ensure','close','member.update','agent.update')),
 object_scope varchar(160) NOT NULL,request_key varchar(256) NOT NULL,fingerprint varchar(64) NOT NULL CHECK(length(fingerprint)=64),
 group_binding_id bigint NOT NULL,source_event_id varchar(256),source_revision bigint,source_fingerprint varchar(64),
 response jsonb NOT NULL CHECK(jsonb_typeof(response)='object'),recorded_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 PRIMARY KEY(tenant_id,integration_id,actor_ref,operation,object_scope,request_key),
 FOREIGN KEY(tenant_id,integration_id,group_binding_id) REFERENCES public.nw_group_bindings(tenant_id,integration_id,id),
 CHECK((source_event_id IS NULL AND source_revision IS NULL AND source_fingerprint IS NULL)
  OR(operation IN('member.update','agent.update') AND source_event_id IS NOT NULL AND length(btrim(source_event_id))>0 AND source_revision IS NOT NULL AND source_revision>0 AND source_fingerprint IS NOT NULL AND length(source_fingerprint)=64)));
CREATE UNIQUE INDEX group_projection_source_event_unique ON public.nw_group_binding_requests(tenant_id,integration_id,group_binding_id,operation,object_scope,source_event_id,source_revision) WHERE source_event_id IS NOT NULL;
CREATE UNIQUE INDEX group_projection_source_revision_unique ON public.nw_group_binding_requests(tenant_id,integration_id,group_binding_id,operation,object_scope,source_revision) WHERE source_event_id IS NOT NULL;

CREATE FUNCTION public.protect_group_binding() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'group binding cannot be deleted'; END IF;
 IF TG_OP='INSERT' THEN
  IF NEW.status<>'active' OR NEW.revision<>1 THEN RAISE EXCEPTION 'group initial lifecycle must be active revision one'; END IF;
  PERFORM 1 FROM public.nw_integration_business_scopes WHERE tenant_id=NEW.tenant_id AND integration_id=NEW.integration_id AND scope_id=NEW.business_scope_id AND status='active' FOR SHARE;
  IF NOT FOUND THEN RAISE EXCEPTION 'new group business scope unavailable'; END IF;
  RETURN NEW;
 END IF;
 IF (to_jsonb(NEW)-ARRAY['status','revision','updated_at']) IS DISTINCT FROM (to_jsonb(OLD)-ARRAY['status','revision','updated_at'])
  OR OLD.status<>'active' OR NEW.status<>'closed' OR NEW.revision<>OLD.revision+1 THEN RAISE EXCEPTION 'group binding is immutable except terminal close'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER group_binding_immutable BEFORE INSERT OR UPDATE OR DELETE ON public.nw_group_bindings FOR EACH ROW EXECUTE FUNCTION public.protect_group_binding();
CREATE FUNCTION public.protect_group_projection() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE g public.nw_group_bindings; BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'group projection removal must retain a versioned tombstone'; END IF;
 SELECT * INTO g FROM public.nw_group_bindings WHERE id=NEW.group_binding_id AND tenant_id=NEW.tenant_id AND integration_id=NEW.integration_id FOR SHARE;
 IF g.id IS NULL OR g.status<>'active' THEN RAISE EXCEPTION 'group binding is closed or outside current domain'; END IF;
 IF NEW.status='active' THEN
  PERFORM 1 FROM public.nw_integration_business_scopes WHERE tenant_id=NEW.tenant_id AND integration_id=NEW.integration_id AND scope_id=g.business_scope_id AND status='active' FOR SHARE;
  IF NOT FOUND THEN RAISE EXCEPTION 'group business scope unavailable'; END IF;
 END IF;
 IF TG_TABLE_NAME='nw_group_service_agents' THEN
  IF EXISTS(SELECT 1 FROM public.nw_agent_deployments d WHERE d.tenant_id=NEW.tenant_id AND d.agent_id=NEW.agent_id AND d.integration_id IS NOT NULL AND d.integration_id<>NEW.integration_id) THEN RAISE EXCEPTION 'group agent is fixed to another integration'; END IF;
 END IF;
 IF TG_OP='INSERT' THEN
  IF NEW.revision<>1 THEN RAISE EXCEPTION 'group object initial revision must be one'; END IF;
 ELSE
  IF NEW.tenant_id<>OLD.tenant_id OR NEW.integration_id<>OLD.integration_id OR NEW.group_binding_id<>OLD.group_binding_id
   OR (to_jsonb(NEW)->>CASE WHEN TG_TABLE_NAME='nw_group_memberships' THEN 'end_user_id' ELSE 'agent_id' END)
      IS DISTINCT FROM (to_jsonb(OLD)->>CASE WHEN TG_TABLE_NAME='nw_group_memberships' THEN 'end_user_id' ELSE 'agent_id' END)
   OR NEW.revision<>OLD.revision+1 OR NEW.source_revision<=OLD.source_revision OR NEW.created_at<>OLD.created_at THEN RAISE EXCEPTION 'group object identity or source revision conflict'; END IF;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER group_member_integrity BEFORE INSERT OR UPDATE OR DELETE ON public.nw_group_memberships FOR EACH ROW EXECUTE FUNCTION public.protect_group_projection();
CREATE TRIGGER group_agent_integrity BEFORE INSERT OR UPDATE OR DELETE ON public.nw_group_service_agents FOR EACH ROW EXECUTE FUNCTION public.protect_group_projection();
CREATE TRIGGER group_request_immutable BEFORE UPDATE OR DELETE ON public.nw_group_binding_requests FOR EACH ROW EXECUTE FUNCTION public.prevent_direct_deployment_revision_mutation();
DO $$ DECLARE table_name text; BEGIN
 FOREACH table_name IN ARRAY ARRAY['nw_group_bindings','nw_group_memberships','nw_group_service_agents','nw_group_binding_requests'] LOOP
  EXECUTE format('ALTER TABLE public.%I ENABLE ROW LEVEL SECURITY',table_name);
  EXECUTE format('ALTER TABLE public.%I FORCE ROW LEVEL SECURITY',table_name);
  EXECUTE format($policy$CREATE POLICY %I ON public.%I USING(tenant_id=NULLIF(current_setting('app.tenant_id',true),'')::bigint) WITH CHECK(tenant_id=NULLIF(current_setting('app.tenant_id',true),'')::bigint)$policy$,table_name||'_tenant',table_name);
 END LOOP;
END $$;
GRANT SELECT,INSERT,UPDATE ON public.nw_group_bindings,public.nw_group_memberships,public.nw_group_service_agents TO skillforge;
GRANT SELECT,INSERT ON public.nw_group_binding_requests TO skillforge;
COMMENT ON TABLE public.nw_group_bindings IS '单外部群生命周期的稳定共享Session，不以EndUser或默认Agent冒充群归属';
COMMENT ON TABLE public.nw_group_memberships IS '固定Tenant/integration群的真实外部成员当前投影，退出保留source和CAS版本';
COMMENT ON TABLE public.nw_group_service_agents IS '显式服务本群的本地Agent目录，实际执行仍复验原授权链和当前资格';
COMMENT ON TABLE public.nw_group_binding_requests IS '群治理原安全命令回执与来源去重，不承载执行或业务事件引擎';
COMMENT ON COLUMN public.nw_group_bindings.id IS '稳定群生命周期身份；对外为不透明字符串';
COMMENT ON COLUMN public.nw_group_bindings.tenant_id IS '可信固定机构';
COMMENT ON COLUMN public.nw_group_bindings.integration_id IS '可信固定外部执行域';
COMMENT ON COLUMN public.nw_group_bindings.namespace IS '执行域内外部群命名空间';
COMMENT ON COLUMN public.nw_group_bindings.external_group_ref IS '原始外部群键；生命周期复用须显式新建';
COMMENT ON COLUMN public.nw_group_bindings.lifecycle_ref IS '调用方明确提供的外部群生命周期身份';
COMMENT ON COLUMN public.nw_group_bindings.business_scope_id IS '已登记且与群Session完全一致的业务责任范围';
COMMENT ON COLUMN public.nw_group_bindings.session_id IS '本群唯一共享Session；成员或服务Agent变化不替换';
COMMENT ON COLUMN public.nw_group_bindings.status IS 'active可治理；closed终态不可重新激活';
COMMENT ON COLUMN public.nw_group_bindings.revision IS '仅群生命周期CAS修订；不随成员或服务目录更新';
COMMENT ON COLUMN public.nw_group_bindings.created_at IS '数据库首次创建时间';
COMMENT ON COLUMN public.nw_group_bindings.updated_at IS '数据库最后生命周期变更时间';
COMMENT ON COLUMN public.nw_group_memberships.tenant_id IS '可信固定机构';
COMMENT ON COLUMN public.nw_group_memberships.integration_id IS '群及真实EndUser映射共同所属执行域';
COMMENT ON COLUMN public.nw_group_memberships.group_binding_id IS '固定所属群生命周期';
COMMENT ON COLUMN public.nw_group_memberships.end_user_id IS '同Tenant和integration的真实外部EndUser';
COMMENT ON COLUMN public.nw_group_memberships.role IS 'member普通成员或manager群治理角色；不形成Session所有者';
COMMENT ON COLUMN public.nw_group_memberships.status IS 'active在群或left退出；重新加入须更高来源修订';
COMMENT ON COLUMN public.nw_group_memberships.revision IS '当前成员对象CAS；初次命令预期0并保存1';
COMMENT ON COLUMN public.nw_group_memberships.source_event_id IS '最近实际采用的稳定外部来源事件';
COMMENT ON COLUMN public.nw_group_memberships.source_revision IS '本群此成员单调来源版本；不按事件ID重新计数';
COMMENT ON COLUMN public.nw_group_memberships.actor_ref IS '最近变更的可信固定服务主体；不含凭据版本';
COMMENT ON COLUMN public.nw_group_memberships.created_at IS '成员对象或退出墓碑首次记录时间';
COMMENT ON COLUMN public.nw_group_memberships.updated_at IS '最近采用来源变更时间';
COMMENT ON COLUMN public.nw_group_service_agents.tenant_id IS '服务Agent真实机构';
COMMENT ON COLUMN public.nw_group_service_agents.integration_id IS '明确授予群服务资格的固定执行域';
COMMENT ON COLUMN public.nw_group_service_agents.group_binding_id IS '固定所属群生命周期';
COMMENT ON COLUMN public.nw_group_service_agents.agent_id IS '真实本地Agent身份；普通独立及合法受控部署共用';
COMMENT ON COLUMN public.nw_group_service_agents.status IS 'active服务或removed移出；新加入仍复验发布及源链';
COMMENT ON COLUMN public.nw_group_service_agents.revision IS '此服务Agent对象的独立CAS修订';
COMMENT ON COLUMN public.nw_group_service_agents.source_event_id IS '最近实际采用的外部服务目录事件';
COMMENT ON COLUMN public.nw_group_service_agents.source_revision IS '本群此Agent单调来源版本';
COMMENT ON COLUMN public.nw_group_service_agents.actor_ref IS '最近变更的可信固定服务主体；不含凭据版本';
COMMENT ON COLUMN public.nw_group_service_agents.created_at IS '服务对象或移除墓碑首次记录时间';
COMMENT ON COLUMN public.nw_group_service_agents.updated_at IS '最近采用来源变更时间';
COMMENT ON COLUMN public.nw_group_binding_requests.tenant_id IS '原命令可信机构';
COMMENT ON COLUMN public.nw_group_binding_requests.integration_id IS '原命令可信固定执行域';
COMMENT ON COLUMN public.nw_group_binding_requests.actor_ref IS '稳定服务主体；凭据轮换保持同一幂等域';
COMMENT ON COLUMN public.nw_group_binding_requests.operation IS 'ensure、close、member.update或agent.update精确领域命令';
COMMENT ON COLUMN public.nw_group_binding_requests.object_scope IS 'new或精确群/成员/Agent对象范围';
COMMENT ON COLUMN public.nw_group_binding_requests.request_key IS '原Idempotency-Key；完整命令指纹冲突不执行';
COMMENT ON COLUMN public.nw_group_binding_requests.fingerprint IS '包含本地预期CAS的规范命令SHA256';
COMMENT ON COLUMN public.nw_group_binding_requests.group_binding_id IS '原成功结果的真实群生命周期';
COMMENT ON COLUMN public.nw_group_binding_requests.source_event_id IS '首次采用的来源事件；纯命令重读别名为空';
COMMENT ON COLUMN public.nw_group_binding_requests.source_revision IS '首次采用的单对象来源版本；须与另两来源列同时存在';
COMMENT ON COLUMN public.nw_group_binding_requests.source_fingerprint IS '来源内容SHA256；排除本地expected_revision及actor/key';
COMMENT ON COLUMN public.nw_group_binding_requests.response IS '首次成功安全响应；后续关闭/退出不能改写';
COMMENT ON COLUMN public.nw_group_binding_requests.recorded_at IS '数据库记录原安全回执的时间';
COMMENT ON CONSTRAINT skillforge_end_users_external_identity_unique ON public.nw_end_users IS '支持群成员真实Tenant/integration/EndUser组合FK，不创建替身用户';
COMMENT ON CONSTRAINT skillforge_chat_sessions_group_binding_tuple_unique ON public.nw_chat_sessions IS '共享Session的Tenant/integration/group/scope必须由同一群精确引用';
COMMENT ON CONSTRAINT skillforge_chat_sessions_group_binding_fk ON public.nw_chat_sessions IS 'GROUP Session只指向同Tenant/integration/session/scope的真实群，提交时完成原子双向引用';
COMMENT ON CONSTRAINT group_binding_session_fk ON public.nw_group_bindings IS '群只引用同域同范围且GROUP归属一致的Session，允许同事务先创建Session';
COMMENT ON INDEX public.skillforge_group_bindings_active_external_unique IS '同外部群键仅一个active生命周期，closed历史允许显式新生命周期';
COMMENT ON INDEX public.group_projection_source_event_unique IS '外部来源事件按群和对象去重，不因actor或凭据轮换重复采用';
COMMENT ON INDEX public.group_projection_source_revision_unique IS '同对象同来源版本不得换事件ID覆盖不同内容';
COMMENT ON FUNCTION public.protect_group_binding() IS '固定群身份/范围/Session，close为不可复活终态';
COMMENT ON FUNCTION public.protect_group_projection() IS '对象初次revision1，之后本地和来源版本单调；群关闭和scope停用约束active写入';
DO $$ DECLARE c record; BEGIN
 FOR c IN SELECT conname,conrelid::regclass AS relation,contype,pg_get_constraintdef(oid) AS definition FROM pg_constraint
  WHERE conrelid IN('public.nw_group_bindings'::regclass,'public.nw_group_memberships'::regclass,'public.nw_group_service_agents'::regclass,'public.nw_group_binding_requests'::regclass)
    AND conname<>'group_binding_session_fk' LOOP
  EXECUTE format('COMMENT ON CONSTRAINT %I ON %s IS %L',c.conname,c.relation,
    CASE c.contype WHEN 'p' THEN '稳定群或对象身份唯一；命令回执按可信域及主体范围唯一'
      WHEN 'u' THEN '群生命周期与组合引用唯一，防止外部键或共享Session重绑'
      WHEN 'f' THEN '强制群、业务范围、真实成员或Agent同Tenant/integration组合归属'
      ELSE '限制领域枚举、非空身份或正修订；来源回执三元要么完整存在要么全部为空' END||'；'||c.definition);
 END LOOP;
END $$;
