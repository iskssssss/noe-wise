-- 平台与机构共享同一稳定源和生命周期；机构源仅允许明确授权部署。
COMMENT ON COLUMN public.nw_agent_templates.owner_kind IS '可信源所有者域platform/tenant；普通Agent发布不自动创建共享源';

CREATE FUNCTION public.protect_agent_definition_owner() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.owner_kind IS DISTINCT FROM OLD.owner_kind OR NEW.owner_tenant_id IS DISTINCT FROM OLD.owner_tenant_id THEN
        RAISE EXCEPTION 'agent_definition_owner_immutable' USING ERRCODE='23514';
    END IF;
    IF OLD.published_at IS NOT NULL AND NEW.distribution_policy IS DISTINCT FROM OLD.distribution_policy THEN
        RAISE EXCEPTION 'agent_definition_distribution_immutable' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER agent_definition_owner_immutable BEFORE UPDATE ON public.nw_agent_templates
FOR EACH ROW EXECUTE FUNCTION public.protect_agent_definition_owner();
COMMENT ON FUNCTION public.protect_agent_definition_owner() IS '稳定源所有者始终不可更改，首次发布后分发策略不可切换';

ALTER TABLE public.nw_agent_definition_operations DROP CONSTRAINT nw_agent_definition_operations_actor_ref_check;
ALTER TABLE public.nw_agent_definition_operations ADD CONSTRAINT agent_definition_operation_actor_check CHECK(
    actor_ref ~ '^(platform_member|management_principal|service_principal):[1-9][0-9]*$'
    OR actor_ref ~ '^tenant:[1-9][0-9]*:member:[1-9][0-9]*$');
DROP POLICY skillforge_agent_definition_operations_platform ON public.nw_agent_definition_operations;
CREATE POLICY agent_definition_operation_owner ON public.nw_agent_definition_operations
USING(EXISTS(SELECT 1 FROM public.nw_agent_templates d WHERE d.id=definition_id AND (
    (d.owner_kind='platform' AND COALESCE(current_setting('app.tenant_id',true),'')='')
    OR(d.owner_kind='tenant' AND d.owner_tenant_id::text=current_setting('app.tenant_id',true)))))
WITH CHECK(EXISTS(SELECT 1 FROM public.nw_agent_templates d WHERE d.id=definition_id AND (
    (d.owner_kind='platform' AND COALESCE(current_setting('app.tenant_id',true),'')='')
    OR(d.owner_kind='tenant' AND d.owner_tenant_id::text=current_setting('app.tenant_id',true)))));
COMMENT ON POLICY agent_definition_operation_owner ON public.nw_agent_definition_operations IS '原回执仅源所有者域可读写；受让发布消费权不泄漏源草稿与维护回执';
