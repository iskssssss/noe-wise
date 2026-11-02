-- GI-11 机构管理入口使用受控路由；扩展默认导航模板，不改用户自定义机构树。
INSERT INTO public.nw_foundation_navigation_nodes(owner_key,code,title,route_key,sort_order,enabled,created_by,updated_by)
VALUES
 ('platform','institution_relations','机构层级','institution_relations',28,true,'seed','seed'),
 ('tenant_template','sub_institutions','下级机构','sub_institutions',31,true,'seed','seed'),
 ('tenant_template','agent_definitions','共享定义','agent_definitions',32,true,'seed','seed'),
 ('tenant_template','agent_grants','定义授权','agent_grants',33,true,'seed','seed'),
 ('tenant_template','headquarters_usage','下级机构用量','headquarters_usage',191,true,'seed','seed');
