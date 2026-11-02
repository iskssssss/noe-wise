-- 目标部署管理与来源授权独立；保留 GI-15 的安全目录动作。
ALTER TABLE public.nw_institution_management_grants DROP CONSTRAINT nw_institution_management_grants_action_check;
ALTER TABLE public.nw_institution_management_grants ADD CONSTRAINT skillforge_institution_management_grants_action_check
  CHECK(action IN('tenant.sub_institutions.read','tenant.agent_deployments.read','tenant.agent_deployments.create',
    'tenant.agent_deployments.bind','tenant.agent_deployments.switch_authorization'));

-- 撤销为终态；同关系/动作的新请求可创建新 ID，不能更新原许可复活。
DO $$
DECLARE original_unique text;
BEGIN
  SELECT conname INTO STRICT original_unique FROM pg_constraint
    WHERE conrelid='public.nw_institution_management_grants'::regclass AND contype='u'
      AND pg_get_constraintdef(oid)='UNIQUE (actor_domain, actor_ref, target_tenant_id, action, relation_ref)';
  EXECUTE format('ALTER TABLE public.nw_institution_management_grants DROP CONSTRAINT %I',original_unique);
END $$;
CREATE UNIQUE INDEX skillforge_institution_management_grants_active_unique
  ON public.nw_institution_management_grants(actor_domain,actor_ref,target_tenant_id,action,relation_ref) WHERE status='active';

COMMENT ON TABLE public.nw_institution_management_grants IS '总部对精确直属关系的显式目录或目标部署管理许可；不授成员、业务、费用或Agent来源权限';
COMMENT ON COLUMN public.nw_institution_management_grants.id IS '稳定管理许可ID；撤销后的显式重授使用新ID';
COMMENT ON COLUMN public.nw_institution_management_grants.target_tenant_id IS '明确目录或部署管理目标机构';
COMMENT ON COLUMN public.nw_institution_management_grants.action IS '安全直属目录read或精确部署read/create/bind/switch_authorization动作';
COMMENT ON COLUMN public.nw_institution_management_grants.status IS 'active或revoked终态；active仍须校验当前直属关系和双方运营状态';
