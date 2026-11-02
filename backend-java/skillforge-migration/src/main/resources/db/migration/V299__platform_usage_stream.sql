-- GI-14目标格式只用于新建/明确获准重建环境；不提供旧应用数据兼容或在线切换。
-- 先解除旧发布投影和选择策略，再重绑可信来源；计量正文继续由usage_events唯一保存。
DROP POLICY skillforge_usage_events_application_export_select ON public.nw_usage_events;
DROP TABLE public.nw_application_usage_exports;
DROP TABLE public.nw_application_usage_streams;
DROP FUNCTION public.guard_application_usage_export_reference();
DROP VIEW public.observation_effective_model_usage;

ALTER TABLE public.nw_chat_sessions
 DROP CONSTRAINT ck_skillforge_chat_sessions_application_source,
 DROP CONSTRAINT ck_skillforge_chat_sessions_business_scope_source,
 DROP CONSTRAINT fk_skillforge_chat_sessions_application_tenant,
 DROP CONSTRAINT fk_skillforge_chat_sessions_business_scope,
 DROP COLUMN application_id,
 ADD COLUMN origin_kind varchar(32) NOT NULL,
 ADD COLUMN actor_ref varchar(256) NOT NULL,
 ADD COLUMN responsibility_ref varchar(256) NOT NULL,
 ADD CONSTRAINT skillforge_chat_sessions_origin_kind CHECK(origin_kind IN ('external','native','system')),
 ADD CONSTRAINT skillforge_chat_sessions_external_integration CHECK(origin_kind<>'external' OR integration_id IS NOT NULL),
 ADD CONSTRAINT skillforge_chat_sessions_business_scope_integration CHECK(business_scope_id IS NULL OR integration_id IS NOT NULL),
 ADD CONSTRAINT skillforge_chat_sessions_integration_fk FOREIGN KEY(tenant_id,integration_id) REFERENCES public.nw_institution_integrations(tenant_id,integration_id),
 ADD CONSTRAINT skillforge_chat_sessions_business_scope_fk FOREIGN KEY(tenant_id,integration_id,business_scope_id)
  REFERENCES public.nw_integration_business_scopes(tenant_id,integration_id,scope_id);

-- PostgreSQL自动命名可能截断FK名字，按真实被引用表定位并只解除两条旧来源FK。
DO $$ DECLARE item record; BEGIN
 FOR item IN SELECT conname FROM pg_constraint WHERE conrelid='public.nw_integration_run_sources'::regclass
  AND contype='f' AND confrelid IN('public.nw_integration_application_tenants'::regclass,'public.nw_legacy_integration_business_scopes'::regclass)
 LOOP EXECUTE format('ALTER TABLE public.nw_integration_run_sources DROP CONSTRAINT %I',item.conname); END LOOP;
END $$;
-- 原生/系统运行不强行制造integration，Run根和实际Agent在原受理事务冻结。
ALTER TABLE public.nw_integration_run_sources
 DROP CONSTRAINT nw_integration_run_sources_check,
 DROP COLUMN application_id,
 ALTER COLUMN integration_id DROP NOT NULL,
 ADD COLUMN origin_kind varchar(32) NOT NULL,
 ADD COLUMN actor_ref varchar(256) NOT NULL,
 ADD COLUMN responsibility_ref varchar(256) NOT NULL,
 ADD COLUMN root_input_id varchar(512) NOT NULL,
 ADD COLUMN deployment_id bigint,
 ADD COLUMN deployment_revision bigint,
 ADD COLUMN grant_id bigint,
 ADD COLUMN grant_revision bigint,
 ADD CONSTRAINT run_source_origin_kind CHECK(origin_kind IN ('external','native','system')),
 ADD CONSTRAINT run_source_external_integration CHECK(origin_kind<>'external' OR integration_id IS NOT NULL),
 ADD CONSTRAINT run_source_business_scope CHECK(business_scope_id IS NULL OR integration_id IS NOT NULL),
 ADD CONSTRAINT run_source_deployment_complete CHECK(
  (deployment_id IS NULL AND deployment_revision IS NULL AND grant_id IS NULL AND grant_revision IS NULL)
  OR(deployment_id IS NOT NULL AND deployment_revision>0 AND grant_id IS NOT NULL AND grant_revision>0)),
 ADD CONSTRAINT run_source_agent_tenant_fk FOREIGN KEY(tenant_id,agent_id) REFERENCES public.nw_agents(tenant_id,id),
 ADD CONSTRAINT run_source_integration_fk FOREIGN KEY(tenant_id,integration_id) REFERENCES public.nw_institution_integrations(tenant_id,integration_id),
 ADD CONSTRAINT run_source_business_scope_fk FOREIGN KEY(tenant_id,integration_id,business_scope_id)
  REFERENCES public.nw_integration_business_scopes(tenant_id,integration_id,scope_id),
 ADD CONSTRAINT run_source_deployment_fk FOREIGN KEY(tenant_id,deployment_id) REFERENCES public.nw_agent_deployments(tenant_id,deployment_id),
 ADD CONSTRAINT run_source_deployment_revision_fk FOREIGN KEY(deployment_id,deployment_revision) REFERENCES public.nw_agent_deployment_revisions(deployment_id,revision),
 ADD CONSTRAINT run_source_grant_fk FOREIGN KEY(grant_id) REFERENCES public.nw_agent_definition_grants(grant_id);
ALTER TABLE public.nw_control_operations DROP CONSTRAINT fk_control_operation_application_tenant,
 DROP COLUMN application_id,
 ADD COLUMN origin_kind varchar(32) NOT NULL,
 ADD COLUMN actor_ref varchar(256) NOT NULL,
 ADD COLUMN responsibility_ref varchar(256) NOT NULL,
 ADD CONSTRAINT control_origin_kind CHECK(origin_kind IN ('external','native','system')),
 ADD CONSTRAINT control_integration_fk FOREIGN KEY(tenant_id,integration_id) REFERENCES public.nw_institution_integrations(tenant_id,integration_id);
ALTER TABLE public.nw_usage_events DROP CONSTRAINT usage_event_application_reference,
 DROP COLUMN application_id,
 ADD COLUMN origin_kind varchar(32) NOT NULL,
 ADD COLUMN actor_ref varchar(256) NOT NULL,
 ADD COLUMN responsibility_ref varchar(256) NOT NULL,
 ADD COLUMN deployment_id bigint,
 ADD CONSTRAINT usage_deployment_tenant_fk FOREIGN KEY(tenant_id,deployment_id) REFERENCES public.nw_agent_deployments(tenant_id,deployment_id),
 ADD CONSTRAINT usage_origin_kind CHECK(origin_kind IN ('external','native','system')),
 ADD CONSTRAINT usage_external_integration CHECK(origin_kind<>'external' OR integration_id IS NOT NULL),
 ADD CONSTRAINT usage_business_scope_integration CHECK(business_scope_id IS NULL OR integration_id IS NOT NULL),
 ADD CONSTRAINT usage_integration_fk FOREIGN KEY(tenant_id,integration_id) REFERENCES public.nw_institution_integrations(tenant_id,integration_id),
 ADD CONSTRAINT usage_business_scope_fk FOREIGN KEY(tenant_id,integration_id,business_scope_id)
  REFERENCES public.nw_integration_business_scopes(tenant_id,integration_id,scope_id);

CREATE TABLE public.nw_usage_publication_state (
 stream_id smallint PRIMARY KEY CHECK(stream_id=1),
 stream_revision varchar(64) NOT NULL,
 published_cursor bigint NOT NULL DEFAULT 0 CHECK(published_cursor>=0)
);
INSERT INTO public.nw_usage_publication_state(stream_id,stream_revision) VALUES(1,gen_random_uuid()::text);
CREATE TABLE public.nw_usage_publication_pending (
 tenant_id bigint NOT NULL,usage_id bigint NOT NULL,event_id varchar(640) NOT NULL,
 PRIMARY KEY(tenant_id,usage_id),UNIQUE(event_id),
 FOREIGN KEY(tenant_id,usage_id) REFERENCES public.nw_usage_events(tenant_id,id)
);
CREATE TABLE public.nw_usage_publication_events (
 event_id varchar(640) PRIMARY KEY,tenant_id bigint NOT NULL,usage_id bigint NOT NULL,
 stream_revision varchar(64) NOT NULL,publication_cursor bigint NOT NULL CHECK(publication_cursor>0),
 published_at timestamptz NOT NULL DEFAULT clock_timestamp(),export_schema_version integer NOT NULL DEFAULT 1 CHECK(export_schema_version=1),
 UNIQUE(tenant_id,usage_id),UNIQUE(stream_revision,publication_cursor),
 FOREIGN KEY(tenant_id,usage_id) REFERENCES public.nw_usage_events(tenant_id,id)
);
ALTER TABLE public.nw_usage_publication_pending ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.nw_usage_publication_pending FORCE ROW LEVEL SECURITY;
ALTER TABLE public.nw_usage_publication_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.nw_usage_publication_events FORCE ROW LEVEL SECURITY;
CREATE POLICY usage_pending_tenant ON public.nw_usage_publication_pending
 USING(tenant_id::text=current_setting('app.tenant_id',true))
 WITH CHECK(tenant_id::text=current_setting('app.tenant_id',true));
CREATE POLICY usage_publication_tenant ON public.nw_usage_publication_events FOR SELECT
 USING(tenant_id::text=current_setting('app.tenant_id',true));
CREATE FUNCTION public.check_usage_pending_source() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM public.nw_usage_events u WHERE u.tenant_id=NEW.tenant_id AND u.id=NEW.usage_id
  AND NEW.event_id=u.tenant_id::text||':'||u.event_id) THEN
  RAISE EXCEPTION 'usage_pending_source_identity_mismatch';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER usage_pending_source BEFORE INSERT ON public.nw_usage_publication_pending
 FOR EACH ROW EXECUTE FUNCTION public.check_usage_pending_source();
GRANT SELECT,INSERT ON public.nw_usage_publication_pending TO skillforge;
GRANT SELECT,INSERT ON public.nw_integration_run_sources TO skillforge;
GRANT SELECT ON public.nw_usage_publication_events,public.nw_usage_publication_state TO skillforge;

-- 沿V40/V281模式：仅受控superuser/BYPASSRLS迁移owner拥有函数；应用角色无跨租户正文读取权。
DO $$ BEGIN
 IF NOT EXISTS(SELECT 1 FROM pg_roles WHERE rolname=current_user AND (rolsuper OR rolbypassrls)) THEN
  RAISE EXCEPTION 'platform usage publisher requires controlled superuser or BYPASSRLS migration owner';
 END IF;
END $$;
CREATE FUNCTION public.publish_committed_usage(batch_limit integer) RETURNS bigint
 LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog,public AS $$
DECLARE tail bigint; generation varchar(64); item record; added integer;
BEGIN
 IF batch_limit IS NULL OR batch_limit<1 OR batch_limit>1000 THEN RAISE EXCEPTION 'usage_publication_batch_invalid'; END IF;
 -- 流尾行锁持有到整个发布事务提交；源提交无需获取此锁，不可能以源ID跳过迟提交。
 SELECT published_cursor,stream_revision INTO STRICT tail,generation
 FROM public.nw_usage_publication_state WHERE stream_id=1 FOR UPDATE;
 FOR item IN SELECT tenant_id,usage_id,event_id FROM public.nw_usage_publication_pending
  ORDER BY usage_id,tenant_id LIMIT batch_limit LOOP
  INSERT INTO public.nw_usage_publication_events(event_id,tenant_id,usage_id,stream_revision,publication_cursor)
   VALUES(item.event_id,item.tenant_id,item.usage_id,generation,tail+1) ON CONFLICT(event_id) DO NOTHING;
  GET DIAGNOSTICS added=ROW_COUNT;
  IF added=1 THEN tail=tail+1; END IF;
  DELETE FROM public.nw_usage_publication_pending WHERE tenant_id=item.tenant_id AND usage_id=item.usage_id;
 END LOOP;
 UPDATE public.nw_usage_publication_state SET published_cursor=tail WHERE stream_id=1;
 RETURN tail;
END $$;
REVOKE ALL ON FUNCTION public.publish_committed_usage(integer) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.publish_committed_usage(integer) TO skillforge;
DO $$ BEGIN
 IF EXISTS(SELECT 1 FROM pg_roles WHERE rolname='skillforge_platform') THEN
  GRANT SELECT ON public.nw_usage_publication_events,public.nw_usage_publication_state TO skillforge_platform;
  GRANT EXECUTE ON FUNCTION public.publish_committed_usage(integer) TO skillforge_platform;
 END IF;
END $$;
COMMENT ON FUNCTION public.publish_committed_usage(integer) IS '只从已提交pending分配平台发布序列，尾锁直到提交，仅返回尾位置不披露跨租户正文';
COMMENT ON TABLE public.nw_usage_publication_pending IS '源Usage同事务登记的可靠待发布引用；回滚原子、重启可接续，不复制计量正文';
COMMENT ON TABLE public.nw_usage_publication_events IS '原始稳定计量事实的不可变发布引用；平台与机构视图共享event_id';
COMMENT ON TABLE public.nw_usage_publication_state IS '当前部署唯一平台流尾与世代；发布事务持锁推进，不采用数据库sequence';
COMMENT ON COLUMN public.nw_usage_publication_events.publication_cursor IS '提交后分配的连续扫描位置；公开cursor为绑定授权的认证不透明值';

-- V273后台重建计量来源仍引用受理时的真实Session/Run/ControlOperation；不改写旧根、不创建假Session。
-- 原metering_*和guard_knowledge_rebuild_metering_origin触发器全部保留；引用现在解析上述去应用来源。
COMMENT ON FUNCTION public.guard_knowledge_rebuild_metering_origin() IS '重建计量因果根受理后不可变；恢复沿原Run或ControlOperation解析真实机构/接入/责任来源，不读取线程或应用默认值';

CREATE OR REPLACE VIEW public.observation_effective_model_usage WITH (security_invoker=true) AS
WITH reports AS (
 SELECT e.*,COALESCE(e.attempt_id, 'event:'||e.id::text) AS observation_key,
        CASE WHEN e.usage_event_kind='usage_report' THEN e.report_revision ELSE COALESCE(e.usage_snapshot_revision,-1) END AS revision
 FROM public.nw_usage_events e
 WHERE e.event_type='llm.chat' AND e.usage_event_kind IN ('measured','usage_report')
), grouped AS (
 SELECT tenant_id,session_id,root_input_id,run_id,observation_key,
        (array_agg(id ORDER BY revision DESC,id DESC))[1] AS metadata_id,
        MAX(id) AS boundary_id,MAX(ingested_at) AS read_at,
        (array_agg(prompt_tokens ORDER BY revision DESC,id DESC) FILTER(WHERE prompt_tokens IS NOT NULL AND usage_source='PROVIDER_REPORTED'))[1] AS prompt_tokens,
        (array_agg(completion_tokens ORDER BY revision DESC,id DESC) FILTER(WHERE completion_tokens IS NOT NULL AND usage_source='PROVIDER_REPORTED'))[1] AS completion_tokens,
        (array_agg(cached_input_tokens ORDER BY revision DESC,id DESC) FILTER(WHERE cached_input_tokens IS NOT NULL AND usage_source='PROVIDER_REPORTED'))[1] AS cached_input_tokens,
        (array_agg(cache_write_input_tokens ORDER BY revision DESC,id DESC) FILTER(WHERE cache_write_input_tokens IS NOT NULL AND usage_source='PROVIDER_REPORTED'))[1] AS cache_write_input_tokens,
        (array_agg(reasoning_output_tokens ORDER BY revision DESC,id DESC) FILTER(WHERE reasoning_output_tokens IS NOT NULL AND usage_source='PROVIDER_REPORTED'))[1] AS reasoning_output_tokens
 FROM reports GROUP BY tenant_id,session_id,root_input_id,run_id,observation_key
)
SELECT g.boundary_id AS id,g.tenant_id,g.session_id,g.root_input_id,g.run_id,e.invocation_id,e.attempt_id,
       e.provider,e.model,e.event_type,e.call_point,e.request_id,e.end_user_id,e.agent_id,e.execution_id,e.meta,
       e.event_id,e.created_at,e.quantity,
       m.total_amount_cents,m.currency,m.input_amount_cents,m.output_amount_cents,m.amount_status,
       m.pricing_snapshot_version,
       CASE WHEN g.prompt_tokens IS NOT NULL AND g.completion_tokens IS NOT NULL
                 AND g.prompt_tokens::numeric+g.completion_tokens::numeric<=9223372036854775807
            THEN (g.prompt_tokens::numeric+g.completion_tokens::numeric)::bigint END AS tokens,
       e.occurred_at,g.read_at AS ingested_at,
       g.prompt_tokens,g.completion_tokens,
       CASE WHEN g.prompt_tokens IS NULL OR g.cached_input_tokens<=g.prompt_tokens
            THEN g.cached_input_tokens END AS cached_input_tokens,
       CASE WHEN g.prompt_tokens IS NULL OR g.cache_write_input_tokens<=g.prompt_tokens
            THEN g.cache_write_input_tokens END AS cache_write_input_tokens,
       CASE WHEN g.completion_tokens IS NULL OR g.reasoning_output_tokens<=g.completion_tokens
            THEN g.reasoning_output_tokens END AS reasoning_output_tokens,
       CASE WHEN g.prompt_tokens IS NOT NULL OR g.completion_tokens IS NOT NULL OR g.cached_input_tokens IS NOT NULL
           OR g.cache_write_input_tokens IS NOT NULL OR g.reasoning_output_tokens IS NOT NULL
            THEN 'PROVIDER_REPORTED' ELSE 'UNKNOWN' END AS usage_source
FROM grouped g JOIN public.nw_usage_events e ON e.tenant_id=g.tenant_id AND e.id=g.metadata_id
LEFT JOIN LATERAL (SELECT u.* FROM public.nw_usage_events u WHERE u.tenant_id=e.tenant_id
    AND u.usage_event_kind='measured' AND (u.attempt_id=e.attempt_id OR (e.attempt_id IS NULL AND u.id=e.id))
    ORDER BY u.id DESC LIMIT 1) m ON true;
COMMENT ON VIEW public.observation_effective_model_usage IS '按供应商尝试归并有效 Token；不累计流式快照，不改变金额事实';
