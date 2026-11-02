package com.skillforge.core.foundation.catalog.navigation;

import com.skillforge.core.foundation.catalog.CatalogValidation;
import com.skillforge.core.foundation.institution.usage.HeadquartersUsageActions;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** 代码拥有的导航路由目录；数据库只保存 key，不持有任意路径或组件导入。 */
public final class ControlledRoutes {
    private static final List<String> MESSAGING_PERMISSIONS=List.of("foundation.messaging.inbox","foundation.messaging.read",
            "foundation.messaging.publish","foundation.messaging.configure","foundation.messaging.operate");
    private ControlledRoutes() { }
    /** @param key 稳定目录键 @param title 默认标题 @param path 已编译的宿主静态路由 @param permission 真实入口读取权限 @param platform 所属宿主 */
    public record Route(String key,String title,String path,String permission,boolean platform) { }
    /** 根据宿主返回有限代码目录，菜单保存不得扩展此集合。 */
    public static List<Route> list(boolean platform) {
        var routes=new ArrayList<Route>();
        if(platform) {
            add(routes,true,"home","工作台","home","tenants"); add(routes,true,"dashboard","系统状态","dashboard","audit");
            add(routes,true,"tenants","机构管理","tenants","tenants");
            routes.add(new Route("management_principals","平台管理身份","/platform/management-principals","platform.management_principals.read",true));
            routes.add(new Route("institutions","机构接入","/platform/institutions","platform.institutions.read",true));
            routes.add(new Route("institution_relations","机构层级","/platform/institution-relations","platform.institution_relations.read",true));
            routes.add(new Route("institution_credentials","机构执行凭证","/platform/institution-credentials","platform.institution_credentials.read",true));
            routes.add(new Route("agent_grants","定义直接授权","/platform/agent-grants","platform.agent_grants.read",true));
            routes.add(new Route("agent_deployments","机构部署","/platform/agent-deployments","platform.agent_deployments.read",true));
            routes.add(new Route("usage","平台用量","/platform/usage","platform.usage.summary.read",true));
            add(routes,true,"models","模型管理","models","models"); add(routes,true,"runtime_settings","运行配置","system-settings","runtime_settings");
            add(routes,true,"system_agents","系统智能体","system-agents","system_agents"); add(routes,true,"system_agent_tools","系统工具","system-agent-tools","system_agent_tools");
            routes.add(new Route("agent_templates","公共定义","/platform/agent-templates","platform.agent_definitions.read",true)); add(routes,true,"capabilities","能力目录","capabilities","capabilities");
            add(routes,true,"skills","技能工作台","skills","skills"); add(routes,true,"sessions","会话观察","sessions","sessions");
            add(routes,true,"trajectories","Agent 轨迹","trajectories","sessions");
            add(routes,true,"billing","账务管理","billing","billing"); add(routes,true,"audit","运维审计","operations","audit"); add(routes,true,"marketplace","市场审核","review","marketplace");
        } else {
            add(routes,false,"home","工作台","home","agents");
            add(routes,false,"trajectories","Agent 轨迹","trajectories","sessions");
            routes.add(new Route("sub_institutions","下级机构","/admin/sub-institutions","tenant.sub_institutions.read",false));
            routes.add(new Route("agent_definitions","共享定义","/admin/agent-definitions","tenant.agent_definitions.read",false));
            routes.add(new Route("agent_grants","定义授权","/admin/agent-grants","tenant.agent_grants.read",false));
            routes.add(new Route("headquarters_usage","下级机构用量","/admin/headquarters-usage","tenant.sub_institutions.usage_summary.read",false));
            for(String[] row:new String[][] {{"agents","智能体"},{"agent-deployments","公共智能体实例"},{"teams","团队"},{"skills","技能工作台"},{"workflows","工作流"},{"capabilities","能力目录"},{"tools","工具"},{"tool-deployments","工具部署"},{"connectors","MCP连接器"},{"knowledge","知识库"},{"memory","记忆"},{"chat","调试会话"},{"sessions","会话记录"},{"conversation-tasks","任务进展"},{"handoff","人工接管"},{"channels","渠道"},{"api-docs","开放API"},{"dashboard","仪表盘"},{"billing-records","计费记录"},{"usage","机构用量"},{"governance","治理"},{"marketplace","市场"},{"settings","运行设置"}}) {
                String area=switch(row[0]) { case "agent-deployments","marketplace"->"agents";case "tools","tool-deployments"->"capabilities";case "chat","conversation-tasks"->"sessions";case "api-docs"->"service_principals";case "dashboard","usage"->"usage";case "billing-records"->"billing";case "governance"->"audit";case "settings"->"models";default->row[0]; };
                if (row[0].equals("agent-deployments")) {
                    routes.add(new Route("agent_deployments",row[1],"/admin/agent-deployments","tenant.agent_deployments.read",false));
                    continue;
                }
                add(routes,false,row[0].replace('-','_'),row[1],row[0],area);
            }
        }
        for(String[] row:new String[][] {{"access","组织与权限","access","access.read"},{"security","账号安全","security","security.self"},{"catalog","菜单与字典","catalog","preferences.manage"},{"messaging","公告与通知","messaging","messaging.inbox"},{"assets","公共文件","assets","files.read"},{"jobs","管理任务","jobs","jobs.read"},{"operations_foundation","日志与运维","operations","operations.audit_read"}}) {
            routes.add(new Route("foundation."+row[0],row[1],(platform?"/platform/":"/admin/")+"foundation/"+row[2],"foundation."+row[3],platform));
        }
        return List.copyOf(routes);
    }
    /** 未注册 key 或另一宿主 key 明确拒绝。 */
    public static Route require(String key,boolean platform) { return list(platform).stream().filter(r->r.key().equals(key)).findFirst().orElseThrow(()->CatalogValidation.invalid("路由不在受控目录中")); }
    /** 聚合入口允许已有的独立读取动作；投影返回实际获准动作，不推导其他读取权。 */
    public static String visiblePermission(Route route,Set<String> permissions) {
        List<String> candidates;
        if(route.platform() && route.key().equals("usage")) candidates=List.of("platform.usage.summary.read","platform.usage.details.read","platform.usage.events.read");
        else if(!route.platform() && route.key().equals("headquarters_usage")) candidates=List.of(HeadquartersUsageActions.SUMMARY,HeadquartersUsageActions.DETAILS,HeadquartersUsageActions.EVENTS);
        else if(route.key().equals("foundation.messaging")) candidates=MESSAGING_PERMISSIONS;
        else if(!route.platform() && route.key().equals("tools")) candidates=List.of("foundation.capabilities.read","foundation.connectors.read");
        else if(!route.platform() && route.key().equals("governance")) candidates=List.of("foundation.audit.read","foundation.service_principals.read","foundation.webhooks.read","foundation.knowledge.read");
        else candidates=List.of(route.permission());
        return candidates.stream().filter(permissions::contains).findFirst().orElse(null);
    }
    /** 路径与权限只由代码计算，服务端不接受用户传入。 */
    private static void add(List<Route> routes,boolean platform,String key,String title,String path,String area) { routes.add(new Route(key,title,(platform?"/platform/":"/admin/")+path,"foundation."+area+".read",platform)); }
}
