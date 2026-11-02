-- 只投影 Run 已冻结的执行来源，不从当前定义/部署/授权补历史。
ALTER TABLE public.nw_usage_events
 ALTER COLUMN quantity DROP NOT NULL,
 ALTER COLUMN quantity DROP DEFAULT,
 ADD COLUMN measurement_unit varchar(32) GENERATED ALWAYS AS
  (CASE WHEN event_type='request' THEN 'request' WHEN event_type='llm.rerank' THEN 'document' ELSE 'call' END) STORED,
 ADD COLUMN source_owner_kind varchar(32),
 ADD COLUMN source_owner_tenant_id bigint,
 ADD COLUMN agent_definition_id bigint,
 ADD COLUMN agent_revision_id bigint,
 ADD COLUMN agent_revision integer,
 ADD COLUMN deployment_revision bigint,
 ADD COLUMN grant_id bigint,
 ADD COLUMN grant_revision bigint,
 ADD CONSTRAINT usage_event_type CHECK(event_type IN
  ('request','llm.chat','llm.embedding','llm.rerank','knowledge.source','tool.call','workflow.external')),
 ADD CONSTRAINT usage_quantity_nonnegative CHECK(quantity IS NULL OR quantity>=0),
 ADD CONSTRAINT usage_source_owner CHECK(
  (source_owner_kind IS NULL AND source_owner_tenant_id IS NULL)
  OR (source_owner_kind IS NOT NULL AND source_owner_kind='platform' AND source_owner_tenant_id IS NULL)
  OR (source_owner_kind IS NOT NULL AND source_owner_kind='tenant' AND source_owner_tenant_id IS NOT NULL AND source_owner_tenant_id>0)),
 ADD CONSTRAINT usage_agent_revision_pair CHECK(
  (agent_revision_id IS NULL AND agent_revision IS NULL)
  OR (agent_revision_id IS NOT NULL AND agent_revision_id>0 AND agent_revision IS NOT NULL AND agent_revision>0)),
 ADD CONSTRAINT usage_definition_owner CHECK(agent_definition_id IS NULL OR
  (agent_definition_id>0 AND source_owner_kind IS NOT NULL)),
 ADD CONSTRAINT usage_authorization_revision CHECK(
  (deployment_revision IS NULL AND grant_id IS NULL AND grant_revision IS NULL)
  OR (deployment_id IS NOT NULL AND deployment_revision IS NOT NULL AND deployment_revision>0
   AND grant_id IS NOT NULL AND grant_id>0 AND grant_revision IS NOT NULL AND grant_revision>0));

CREATE TABLE public.nw_usage_event_grant_hops (
 tenant_id bigint NOT NULL,
 usage_id bigint NOT NULL,
 ordinal integer NOT NULL CHECK(ordinal>0),
 grant_id bigint NOT NULL CHECK(grant_id>0),
 revision bigint NOT NULL CHECK(revision>0),
 grantor_kind varchar(32) NOT NULL CHECK(grantor_kind IN ('platform','tenant')),
 grantor_tenant_id bigint,
 target_tenant_id bigint NOT NULL CHECK(target_tenant_id>0),
 parent_grant_id bigint,
 parent_grant_revision bigint,
 relation_ref bigint,
 relation_revision bigint,
 PRIMARY KEY(tenant_id,usage_id,ordinal),
 UNIQUE(tenant_id,usage_id,grant_id),
 FOREIGN KEY(tenant_id,usage_id) REFERENCES public.nw_usage_events(tenant_id,id),
 CHECK((grantor_kind='platform' AND grantor_tenant_id IS NULL)
  OR (grantor_kind='tenant' AND grantor_tenant_id IS NOT NULL AND grantor_tenant_id>0)),
 CHECK((parent_grant_id IS NULL AND parent_grant_revision IS NULL)
  OR (parent_grant_id IS NOT NULL AND parent_grant_id>0 AND parent_grant_revision IS NOT NULL AND parent_grant_revision>0)),
 CHECK((relation_ref IS NULL AND relation_revision IS NULL)
  OR (relation_ref IS NOT NULL AND relation_ref>0 AND relation_revision IS NOT NULL AND relation_revision>0))
);
ALTER TABLE public.nw_usage_event_grant_hops ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.nw_usage_event_grant_hops FORCE ROW LEVEL SECURITY;
CREATE POLICY usage_grant_hops_tenant_select ON public.nw_usage_event_grant_hops FOR SELECT
 USING(tenant_id::text=current_setting('app.tenant_id',true));
CREATE POLICY usage_grant_hops_tenant_insert ON public.nw_usage_event_grant_hops FOR INSERT
 WITH CHECK(tenant_id::text=current_setting('app.tenant_id',true));
GRANT SELECT,INSERT ON public.nw_usage_event_grant_hops TO skillforge;
DO $$ BEGIN
 IF EXISTS(SELECT 1 FROM pg_roles WHERE rolname='skillforge_platform') THEN
  GRANT SELECT ON public.nw_usage_event_grant_hops TO skillforge_platform;
 END IF;
END $$;
COMMENT ON COLUMN public.nw_usage_events.measurement_unit IS 'quantity真实单位：request请求、call实际调用、document rerank文档；token另列，未知quantity为NULL';
COMMENT ON COLUMN public.nw_usage_events.quantity IS '真实计量数量；NULL为未知，不以默认1或0补造';
COMMENT ON COLUMN public.nw_usage_events.source_owner_kind IS 'Run冻结原定义所有者platform或tenant；非Agent来源NULL';
COMMENT ON COLUMN public.nw_usage_events.source_owner_tenant_id IS 'Run冻结原定义所属机构；平台定义及非Agent来源NULL';
COMMENT ON COLUMN public.nw_usage_events.agent_definition_id IS 'Run冻结共享定义稳定ID；自有Agent及非Agent来源NULL';
COMMENT ON COLUMN public.nw_usage_events.agent_revision_id IS 'Run冻结实际Agent发布记录ID，非Agent来源NULL';
COMMENT ON COLUMN public.nw_usage_events.agent_revision IS 'Run冻结实际Agent发布修订，非Agent来源NULL';
COMMENT ON COLUMN public.nw_usage_events.deployment_revision IS 'Run冻结部署修订，非部署来源NULL';
COMMENT ON COLUMN public.nw_usage_events.grant_id IS 'Run冻结末端授权ID，非部署来源NULL';
COMMENT ON COLUMN public.nw_usage_events.grant_revision IS 'Run冻结末端授权修订，非部署来源NULL';
COMMENT ON TABLE public.nw_usage_event_grant_hops IS '用量原Run受理时冻结的有序授权链投影；只INSERT+SELECT，与原用量同事务及保留周期，确认/调整复制原链';
