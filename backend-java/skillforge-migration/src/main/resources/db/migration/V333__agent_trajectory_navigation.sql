-- Agent轨迹只增加到仍由代码种子维护的默认导航；机构自定义树保持管理员的配置。
-- 与NavigationMapper使用同一owner锁，避免和菜单编辑交错。
SELECT pg_advisory_xact_lock(hashtextextended('platform',276));
SELECT pg_advisory_xact_lock(hashtextextended('tenant_template',276));

INSERT INTO public.nw_foundation_navigation_nodes
    (owner_key,code,parent_code,title,route_key,sort_order,enabled,created_by,updated_by)
SELECT defaults.owner_key,'trajectories',defaults.parent_code,'Agent 轨迹','trajectories',
       defaults.sort_order,true,'seed','seed'
FROM (VALUES ('platform','nav_runtime',111),('tenant_template','nav_agents',121))
     AS defaults(owner_key,parent_code,sort_order)
JOIN public.nw_foundation_navigation_nodes parent
  ON parent.owner_key=defaults.owner_key AND parent.code=defaults.parent_code
 AND parent.route_key IS NULL AND parent.enabled=true
JOIN public.nw_foundation_navigation_nodes sessions
  ON sessions.owner_key=defaults.owner_key AND sessions.code='sessions'
 AND sessions.parent_code=defaults.parent_code AND sessions.route_key='sessions'
 AND sessions.enabled=true
WHERE NOT EXISTS (
    SELECT 1 FROM public.nw_foundation_navigation_nodes custom
    WHERE custom.owner_key=defaults.owner_key
      AND (custom.created_by<>'seed' OR custom.updated_by<>'seed')
)
ON CONFLICT (owner_key,code) DO NOTHING;
