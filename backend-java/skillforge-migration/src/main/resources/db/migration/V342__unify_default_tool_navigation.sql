-- 工具接入统一入口；只升级未编辑的默认节点，不改变角色权限或旧直达路由。
-- 与 NavigationMapper.initialize/save 使用相同owner锁，锁后再核对种子/克隆事实。
DO $$
DECLARE menu_owner text;
BEGIN
    FOR menu_owner IN
        SELECT owner_key FROM (
            SELECT 'tenant_template'::text AS owner_key
            UNION
            SELECT owner_key FROM public.nw_foundation_navigation_owners
             WHERE owner_key LIKE 'tenant:%'
        ) owners ORDER BY owner_key
    LOOP
        PERFORM pg_advisory_xact_lock(hashtextextended(menu_owner,276));
    END LOOP;
END $$;

CREATE TEMP TABLE tool_navigation_default_owners(owner_key text PRIMARY KEY) ON COMMIT DROP;
INSERT INTO tool_navigation_default_owners(owner_key)
SELECT actual.owner_key
  FROM public.nw_foundation_navigation_nodes actual
  JOIN (VALUES ('tool_deployments','工具部署','tool_deployments',70),
               ('connectors','MCP连接器','connectors',80))
       expected(code,title,route_key,sort_order) ON expected.code=actual.code
 WHERE actual.title=expected.title AND actual.route_key=expected.route_key
   AND actual.sort_order=expected.sort_order AND actual.enabled=true
   AND (actual.parent_code IS NULL OR actual.parent_code='nav_capabilities')
   AND (
       (actual.owner_key='tenant_template' AND actual.tenant_id IS NULL
        AND actual.created_by='seed' AND actual.updated_by='seed' AND actual.revision IN (1,2))
       OR
       (actual.owner_key='tenant:'||actual.tenant_id::text AND actual.revision=1
        AND actual.created_by=actual.updated_by
        AND EXISTS (SELECT 1 FROM public.nw_foundation_navigation_owners owner
                     WHERE owner.owner_key=actual.owner_key AND owner.tenant_id=actual.tenant_id))
   )
   -- 带自定义子树的旧入口不能停用或换路由，保持管理员配置完整。
   AND NOT EXISTS (SELECT 1 FROM public.nw_foundation_navigation_nodes child
                    WHERE child.owner_key=actual.owner_key
                      AND child.parent_code IN ('tool_deployments','connectors'))
 GROUP BY actual.owner_key HAVING count(*)=2;

UPDATE public.nw_foundation_navigation_nodes actual
   SET title='工具',route_key='tools',revision=actual.revision+1,
       updated_at=clock_timestamp(),updated_by='migration:V342'
  FROM tool_navigation_default_owners eligible
 WHERE actual.owner_key=eligible.owner_key AND actual.code='tool_deployments';

UPDATE public.nw_foundation_navigation_nodes actual
   SET enabled=false,revision=actual.revision+1,
       updated_at=clock_timestamp(),updated_by='migration:V342'
  FROM tool_navigation_default_owners eligible
 WHERE actual.owner_key=eligible.owner_key AND actual.code='connectors';
