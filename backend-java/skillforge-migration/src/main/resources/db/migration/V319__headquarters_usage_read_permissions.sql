-- 总部用量需显式目标许可；不自动给已有或未来下级授予费用读取权。
ALTER TABLE public.nw_institution_management_grants DROP CONSTRAINT skillforge_institution_management_grants_action_check;
ALTER TABLE public.nw_institution_management_grants ADD CONSTRAINT skillforge_institution_management_grants_action_check
  CHECK(action IN('tenant.sub_institutions.read','tenant.agent_deployments.read','tenant.agent_deployments.create',
    'tenant.agent_deployments.bind','tenant.agent_deployments.switch_authorization',
    'tenant.sub_institutions.usage_summary.read','tenant.sub_institutions.usage_details.read','tenant.sub_institutions.usage_events.read'));
COMMENT ON TABLE public.nw_institution_management_grants IS '当前精确直属关系的独立目录、部署或用量动作许可；汇总、明细、事件及执行权不互相授予';
COMMENT ON COLUMN public.nw_institution_management_grants.action IS '独立目录/部署或usage_summary/usage_details/usage_events读取动作';

-- 布尔函数只验证当前总部、独立动作、声明目标和当前关系许可，不返回任何业务正文。
DO $$ BEGIN
  IF NOT EXISTS(SELECT 1 FROM pg_roles WHERE rolname=current_user AND (rolsuper OR rolbypassrls)) THEN
    RAISE EXCEPTION 'headquarters usage policy requires controlled superuser or BYPASSRLS migration owner';
  END IF;
END $$;
CREATE FUNCTION public.headquarters_usage_target_allowed(target_tenant bigint) RETURNS boolean
  LANGUAGE sql STABLE SECURITY DEFINER SET search_path=pg_catalog,public AS $$
  SELECT COALESCE(
    current_setting('app.headquarters_usage_action',true) IN
      ('tenant.sub_institutions.usage_summary.read','tenant.sub_institutions.usage_details.read','tenant.sub_institutions.usage_events.read')
    AND target_tenant::text=ANY(string_to_array(current_setting('app.headquarters_usage_targets',true),','))
    AND EXISTS(
      SELECT 1 FROM public.nw_institution_management_grants g
      JOIN public.nw_institution_relation_revisions r ON r.id=g.relation_ref AND r.child_tenant_id=g.target_tenant_id
      JOIN public.nw_tenants child ON child.id=g.target_tenant_id
        AND child.parent_tenant_id=r.parent_tenant_id AND child.relation_revision=r.revision
      JOIN public.nw_tenants headquarters ON headquarters.id=r.parent_tenant_id
      WHERE g.target_tenant_id=target_tenant AND g.actor_domain='tenant'
        AND g.actor_ref='tenant:'||current_setting('app.tenant_id',true)
        AND headquarters.id::text=current_setting('app.tenant_id',true)
        AND g.action=current_setting('app.headquarters_usage_action',true) AND g.status='active'
        AND child.status IN('active','warning') AND headquarters.status IN('active','warning')
    ),false)
$$;
REVOKE ALL ON FUNCTION public.headquarters_usage_target_allowed(bigint) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.headquarters_usage_target_allowed(bigint) TO skillforge;
DO $$ BEGIN
  IF EXISTS(SELECT 1 FROM pg_roles WHERE rolname='skillforge_platform') THEN
    GRANT EXECUTE ON FUNCTION public.headquarters_usage_target_allowed(bigint) TO skillforge_platform;
  END IF;
END $$;
COMMENT ON FUNCTION public.headquarters_usage_target_allowed(bigint) IS '仅供总部固定用量只读窗口的RLS布尔许可；当前精确grant和relation共同判定，不返回正文，不修改状态';

CREATE POLICY skillforge_usage_events_headquarters_read ON public.nw_usage_events FOR SELECT
  USING(public.headquarters_usage_target_allowed(tenant_id));
CREATE POLICY usage_publication_headquarters_read ON public.nw_usage_publication_events FOR SELECT
  USING(public.headquarters_usage_target_allowed(tenant_id));
CREATE POLICY usage_pending_headquarters_read ON public.nw_usage_publication_pending FOR SELECT
  USING(public.headquarters_usage_target_allowed(tenant_id));
CREATE POLICY usage_grant_hops_headquarters_read ON public.nw_usage_event_grant_hops FOR SELECT
  USING(public.headquarters_usage_target_allowed(tenant_id));
