-- 工具页仍以 MCP 连接器作为外部工具配置入口；恢复 V342 停用的默认节点。
-- 仅处理 V342 亲自停用的节点，管理员自定义的标题、层级或删除保持原样。
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

UPDATE public.nw_foundation_navigation_nodes actual
   SET enabled=true,parent_code='nav_integrations',title='MCP 连接器',sort_order=155,
       revision=actual.revision+1,updated_at=clock_timestamp(),updated_by='migration:V384'
 WHERE actual.code='connectors'
   AND actual.enabled=false
   AND actual.updated_by='migration:V342'
   AND (actual.owner_key='tenant_template' OR actual.owner_key LIKE 'tenant:%');
