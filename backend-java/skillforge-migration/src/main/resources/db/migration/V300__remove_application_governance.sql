-- GI-14 新目标格式完整接续后删除 Application；只在新建或明确获准重建环境执行。
-- 不迁移、伪造或重新归属历史业务事实，不提供默认应用和历史身份代理。
DROP TABLE public.nw_integration_provision_requests;
DROP TABLE public.nw_legacy_integration_business_scopes;
DROP TABLE public.nw_integration_application_credentials;
DROP TABLE public.nw_integration_application_audit;
DROP TABLE public.nw_integration_application_tenants;
DROP TABLE public.nw_integration_applications;

DELETE FROM public.nw_foundation_role_permissions
 WHERE permission_code LIKE 'foundation.applications.%'
    OR permission_code LIKE 'foundation.agent_templates.%';
DELETE FROM public.nw_foundation_navigation_nodes
 WHERE code IN ('applications','application_usage');

INSERT INTO public.nw_foundation_navigation_nodes(owner_key,code,title,route_key,sort_order,enabled,created_by,updated_by)
VALUES
 ('platform','management_principals','平台管理身份','management_principals',25,true,'seed','seed'),
 ('platform','institutions','机构接入','institutions',26,true,'seed','seed'),
 ('platform','institution_credentials','机构执行凭证','institution_credentials',27,true,'seed','seed'),
 ('platform','agent_grants','定义直接授权','agent_grants',81,true,'seed','seed'),
 ('platform','agent_deployments','机构部署','agent_deployments',82,true,'seed','seed'),
 ('platform','usage','平台用量','usage',121,true,'seed','seed'),
 ('tenant_template','usage','机构用量','usage',190,true,'seed','seed');
UPDATE public.nw_foundation_navigation_nodes SET title='公共定义'
 WHERE owner_key='platform' AND code='agent_templates';
