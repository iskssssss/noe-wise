-- 默认菜单按业务组织；只升级完整且从未由管理员修改的种子树。
-- 自有 tenant:ID 树完全保留；标题、路由、排序、开关、增删或CAS修订有差量的默认树也整棵保留。
-- 与 NavigationMapper.lockOwner 使用同一锁键，避免迁移和管理员编辑交错。
SELECT pg_advisory_xact_lock(hashtextextended('platform',276));
SELECT pg_advisory_xact_lock(hashtextextended('tenant_template',276));

CREATE TEMP TABLE navigation_seed_before(owner_key text,code text,title text,route_key text,sort_order integer,PRIMARY KEY(owner_key,code)) ON COMMIT DROP;
INSERT INTO navigation_seed_before(owner_key,code,title,route_key,sort_order) VALUES
 ('platform','home','工作台','home',0),
 ('platform','dashboard','系统状态','dashboard',10),
 ('platform','tenants','机构管理','tenants',20),
 ('platform','models','模型管理','models',40),
 ('platform','runtime_settings','运行配置','runtime_settings',50),
 ('platform','system_agents','系统智能体','system_agents',60),
 ('platform','system_agent_tools','系统工具','system_agent_tools',70),
 ('platform','agent_templates','公共定义','agent_templates',80),
 ('platform','capabilities','能力目录','capabilities',90),
 ('platform','skills','技能工作台','skills',100),
 ('platform','sessions','会话观察','sessions',110),
 ('platform','billing','账务管理','billing',120),
 ('platform','audit','运维审计','audit',130),
 ('platform','marketplace','市场审核','marketplace',140),
 ('platform','foundation_access','组织与权限','foundation.access',150),
 ('platform','foundation_security','账号安全','foundation.security',160),
 ('platform','foundation_catalog','基础目录与设置','foundation.catalog',170),
 ('platform','foundation_messaging','公告与通知','foundation.messaging',180),
 ('platform','foundation_assets','文件与管理任务','foundation.assets',190),
 ('platform','foundation_operations','日志与运维','foundation.operations_foundation',200),
 ('tenant_template','home','工作台','home',0),
 ('tenant_template','agents','智能体','agents',10),
 ('tenant_template','agent_deployments','公共智能体实例','agent_deployments',20),
 ('tenant_template','teams','团队','teams',30),
 ('tenant_template','skills','技能工作台','skills',40),
 ('tenant_template','workflows','工作流','workflows',50),
 ('tenant_template','capabilities','能力目录','capabilities',60),
 ('tenant_template','tool_deployments','工具部署','tool_deployments',70),
 ('tenant_template','connectors','MCP连接器','connectors',80),
 ('tenant_template','knowledge','知识库','knowledge',90),
 ('tenant_template','memory','记忆','memory',100),
 ('tenant_template','chat','调试会话','chat',110),
 ('tenant_template','sessions','会话记录','sessions',120),
 ('tenant_template','conversation_tasks','任务进展','conversation_tasks',130),
 ('tenant_template','handoff','人工接管','handoff',140),
 ('tenant_template','channels','渠道','channels',150),
 ('tenant_template','api_docs','开放API','api_docs',160),
 ('tenant_template','dashboard','仪表盘','dashboard',170),
 ('tenant_template','billing_records','计费记录','billing_records',180),
 ('tenant_template','governance','治理','governance',200),
 ('tenant_template','marketplace','市场','marketplace',210),
 ('tenant_template','settings','运行设置','settings',220),
 ('tenant_template','foundation_access','组织与权限','foundation.access',230),
 ('tenant_template','foundation_security','账号安全','foundation.security',240),
 ('tenant_template','foundation_catalog','基础目录与设置','foundation.catalog',250),
 ('tenant_template','foundation_messaging','公告与通知','foundation.messaging',260),
 ('tenant_template','foundation_assets','文件与管理任务','foundation.assets',270),
 ('tenant_template','foundation_operations','日志与运维','foundation.operations_foundation',280),
 ('platform','foundation_jobs','管理任务','foundation.jobs',1000),
 ('tenant_template','foundation_jobs','管理任务','foundation.jobs',1000),
 ('platform','management_principals','平台管理身份','management_principals',25),
 ('platform','institutions','机构接入','institutions',26),
 ('platform','institution_credentials','机构执行凭证','institution_credentials',27),
 ('platform','agent_grants','定义直接授权','agent_grants',81),
 ('platform','agent_deployments','机构部署','agent_deployments',82),
 ('platform','usage','平台用量','usage',121),
 ('tenant_template','usage','机构用量','usage',190),
 ('platform','institution_relations','机构层级','institution_relations',28),
 ('tenant_template','sub_institutions','下级机构','sub_institutions',31),
 ('tenant_template','agent_definitions','共享定义','agent_definitions',32),
 ('tenant_template','agent_grants','定义授权','agent_grants',33),
 ('tenant_template','headquarters_usage','下级机构用量','headquarters_usage',191);

CREATE TEMP TABLE navigation_group_defaults(owner_key text,code text,title text,sort_order integer,PRIMARY KEY(owner_key,code)) ON COMMIT DROP;
INSERT INTO navigation_group_defaults(owner_key,code,title,sort_order) VALUES
 ('platform','nav_institutions','机构与接入',10),
 ('platform','nav_agents','智能体与能力',20),
 ('platform','nav_runtime','运行与用量',30),
 ('platform','nav_management','组织与设置',40),
 ('tenant_template','nav_agents','智能体与协作',10),
 ('tenant_template','nav_capabilities','能力与数据',20),
 ('tenant_template','nav_integrations','接入与服务',30),
 ('tenant_template','nav_headquarters','总部管理',40),
 ('tenant_template','nav_governance','用量与治理',50),
 ('tenant_template','nav_management','组织与设置',60);

CREATE TEMP TABLE navigation_default_parents(owner_key text,code text,parent_code text,PRIMARY KEY(owner_key,code)) ON COMMIT DROP;
INSERT INTO navigation_default_parents(owner_key,code,parent_code) VALUES
 ('platform','tenants','nav_institutions'),
 ('platform','management_principals','nav_institutions'),
 ('platform','institutions','nav_institutions'),
 ('platform','institution_credentials','nav_institutions'),
 ('platform','institution_relations','nav_institutions'),
 ('platform','models','nav_agents'),
 ('platform','system_agents','nav_agents'),
 ('platform','system_agent_tools','nav_agents'),
 ('platform','agent_templates','nav_agents'),
 ('platform','agent_grants','nav_agents'),
 ('platform','agent_deployments','nav_agents'),
 ('platform','capabilities','nav_agents'),
 ('platform','skills','nav_agents'),
 ('platform','marketplace','nav_agents'),
 ('platform','dashboard','nav_runtime'),
 ('platform','sessions','nav_runtime'),
 ('platform','usage','nav_runtime'),
 ('platform','billing','nav_runtime'),
 ('platform','audit','nav_runtime'),
 ('platform','foundation_operations','nav_runtime'),
 ('platform','foundation_jobs','nav_runtime'),
 ('platform','runtime_settings','nav_management'),
 ('platform','foundation_access','nav_management'),
 ('platform','foundation_security','nav_management'),
 ('platform','foundation_catalog','nav_management'),
 ('platform','foundation_messaging','nav_management'),
 ('platform','foundation_assets','nav_management'),
 ('tenant_template','agents','nav_agents'),
 ('tenant_template','agent_deployments','nav_agents'),
 ('tenant_template','teams','nav_agents'),
 ('tenant_template','chat','nav_agents'),
 ('tenant_template','sessions','nav_agents'),
 ('tenant_template','conversation_tasks','nav_agents'),
 ('tenant_template','handoff','nav_agents'),
 ('tenant_template','skills','nav_capabilities'),
 ('tenant_template','workflows','nav_capabilities'),
 ('tenant_template','capabilities','nav_capabilities'),
 ('tenant_template','tool_deployments','nav_capabilities'),
 ('tenant_template','connectors','nav_capabilities'),
 ('tenant_template','knowledge','nav_capabilities'),
 ('tenant_template','memory','nav_capabilities'),
 ('tenant_template','marketplace','nav_capabilities'),
 ('tenant_template','channels','nav_integrations'),
 ('tenant_template','api_docs','nav_integrations'),
 ('tenant_template','sub_institutions','nav_headquarters'),
 ('tenant_template','agent_definitions','nav_headquarters'),
 ('tenant_template','agent_grants','nav_headquarters'),
 ('tenant_template','headquarters_usage','nav_headquarters'),
 ('tenant_template','dashboard','nav_governance'),
 ('tenant_template','billing_records','nav_governance'),
 ('tenant_template','usage','nav_governance'),
 ('tenant_template','governance','nav_governance'),
 ('tenant_template','foundation_operations','nav_governance'),
 ('tenant_template','foundation_jobs','nav_governance'),
 ('tenant_template','settings','nav_management'),
 ('tenant_template','foundation_access','nav_management'),
 ('tenant_template','foundation_security','nav_management'),
 ('tenant_template','foundation_catalog','nav_management'),
 ('tenant_template','foundation_messaging','nav_management'),
 ('tenant_template','foundation_assets','nav_management');

CREATE TEMP TABLE navigation_untouched_owners(owner_key text PRIMARY KEY) ON COMMIT DROP;
INSERT INTO navigation_untouched_owners(owner_key)
SELECT expected.owner_key FROM navigation_seed_before expected GROUP BY expected.owner_key
HAVING count(*)=(SELECT count(*) FROM public.nw_foundation_navigation_nodes actual WHERE actual.owner_key=expected.owner_key)
 AND NOT EXISTS(
   SELECT 1 FROM public.nw_foundation_navigation_nodes actual
   LEFT JOIN navigation_seed_before original ON original.owner_key=actual.owner_key AND original.code=actual.code
   WHERE actual.owner_key=expected.owner_key AND (
     original.code IS NULL OR actual.parent_code IS NOT NULL OR actual.tenant_id IS NOT NULL
     OR actual.title IS DISTINCT FROM original.title OR actual.route_key IS DISTINCT FROM original.route_key
     OR actual.sort_order IS DISTINCT FROM original.sort_order OR actual.enabled IS DISTINCT FROM true
     OR actual.revision<>1 OR actual.created_by<>'seed' OR actual.updated_by<>'seed'));

INSERT INTO public.nw_foundation_navigation_nodes(owner_key,code,parent_code,title,route_key,sort_order,enabled,created_by,updated_by)
SELECT groups.owner_key,groups.code,NULL,groups.title,NULL,groups.sort_order,true,'seed','seed'
 FROM navigation_group_defaults groups JOIN navigation_untouched_owners owners ON owners.owner_key=groups.owner_key;

-- 只变更默认叶节点的父组；原标题、路由、排序和启用状态不重写。层级变更推进CAS修订。
UPDATE public.nw_foundation_navigation_nodes actual
 SET parent_code=parents.parent_code,revision=actual.revision+1,updated_at=clock_timestamp(),updated_by='seed'
 FROM navigation_default_parents parents JOIN navigation_untouched_owners owners ON owners.owner_key=parents.owner_key
 WHERE actual.owner_key=parents.owner_key AND actual.code=parents.code;
