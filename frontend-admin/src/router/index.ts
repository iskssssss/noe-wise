import { routePermission } from '@skillforge/shared/foundation/navigation'
/**
 * 灵知 机构管理后台路由（Vue 3 + TypeScript）
 *
 * <p>机构管理后台；智能体测试页与管理页面共用导航与鉴权布局。
 */
import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import { adminZhCN } from '../i18n/zh-CN'

const ROUTE_TITLES: Record<string, string> = {
  'admin.app.loginTitle': adminZhCN.app.loginTitle,
  ...Object.fromEntries(
    Object.entries(adminZhCN.route).map(([key, value]) => [`admin.route.${key}`, value])
  )
}

const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/admin',
      component: () => import('../views/admin/Layout.vue'),
      meta: { requiresAuth: true },
      children: [
        {
          path: 'institutions/:tenantId?',
          component: () => import('../views/admin/InstitutionWorkspacePage.vue'),
          meta: { title: '机构工作台', institutionWorkspace: true }
        },
        {
          path: 'sub-institutions',
          component: () => import('../views/admin/SubInstitutionsPage.vue'),
          meta: { title: '下级机构' }
        },
        {
          path: 'agent-definitions',
          component: () => import('../views/admin/InstitutionDefinitionsPage.vue'),
          meta: { title: '机构共享定义' }
        },
        {
          path: 'agent-grants',
          component: () => import('../views/admin/InstitutionGrantsPage.vue'),
          meta: { title: '智能体授权' }
        },
        {
          path: 'headquarters-usage',
          component: () => import('../views/admin/HeadquartersUsagePage.vue'),
          meta: { title: '下级机构用量' }
        },
        {
          path: 'foundation/jobs',
          component: () => import('../views/FoundationPage.vue'),
          meta: { title: '导入导出与任务', foundationArea: 'jobs' }
        },
        {
          path: 'foundation/access',
          component: () => import('../views/FoundationPage.vue'),
          meta: { title: '组织与权限', foundationArea: 'access' }
        },
        {
          path: 'foundation/catalog',
          component: () => import('../views/FoundationPage.vue'),
          meta: { title: '菜单、字典与配置', foundationArea: 'catalog' }
        },
        {
          path: 'foundation/security',
          component: () => import('../views/FoundationPage.vue'),
          meta: { title: '账号安全', foundationArea: 'security' }
        },
        {
          path: 'foundation/messaging',
          component: () => import('../views/FoundationPage.vue'),
          meta: { title: '公告与通知', foundationArea: 'messaging' }
        },
        {
          path: 'foundation/assets',
          component: () => import('../views/FoundationPage.vue'),
          meta: { title: '文件与管理任务', foundationArea: 'assets' }
        },
        {
          path: 'foundation/operations',
          component: () => import('../views/FoundationPage.vue'),
          meta: { title: '日志与运维', foundationArea: 'operations' }
        },
        {
          path: 'chat',
          component: () => import('../views/ChatPage.vue'),
          meta: { titleKey: 'admin.route.chat', chatLayout: true }
        },
        {
          path: 'chat/sessions/:sessionId/trace',
          component: () => import('../views/SessionTracePage.vue'),
          meta: { titleKey: 'admin.route.sessionTrace' }
        },
        {
          path: 'home',
          component: () => import('../views/admin/HomePage.vue'),
          meta: { titleKey: 'admin.route.home' }
        },
        {
          path: 'sessions',
          component: () => import('../views/admin/SessionsPage.vue'),
          meta: { titleKey: 'admin.route.sessions' }
        },
        {
          path: 'trajectories',
          component: () => import('../views/admin/TrajectoriesPage.vue'),
          meta: { title: 'Agent 轨迹' }
        },
        {
          path: 'trajectories/detail',
          component: () => import('../views/TrajectoryDetailPage.vue'),
          meta: { title: '轨迹详情' }
        },
        {
          path: 'conversation-tasks',
          component: () => import('../views/admin/ConversationTasksPage.vue'),
          meta: { title: '任务进展' }
        },
        {
          path: 'workflows',
          component: () => import('../views/admin/WorkflowsPage.vue'),
          meta: { titleKey: 'admin.route.workflows' }
        },
        // 新建路由用 create 而非 new：避免与真实 workflow_id 恰为 "new" 的工作流详情路由冲突（静态路由优先匹配）
        {
          path: 'workflows/create',
          component: () => import('../views/admin/WorkflowDetailPage.vue'),
          meta: { titleKey: 'admin.route.workflowCreate' }
        },
        {
          path: 'workflows/:workflowId',
          component: () => import('../views/admin/WorkflowDetailPage.vue'),
          meta: { titleKey: 'admin.route.workflowDetail' }
        },
        {
          path: 'agents',
          component: () => import('../views/admin/AgentsPage.vue'),
          meta: { titleKey: 'admin.route.agentDetail' }
        },
        {
          path: 'agent-deployments',
          component: () => import('../views/admin/AgentDeploymentsPage.vue'),
          meta: { title: '公共智能体实例' }
        },
        {
          path: 'usage',
          component: () => import('../views/admin/UsagePage.vue'),
          meta: { title: '应用用量' }
        },
        {
          path: 'teams',
          component: () => import('../views/admin/TeamsPage.vue'),
          meta: { titleKey: 'admin.route.teams' }
        },
        {
          path: 'agents/:id',
          component: () => import('../views/admin/AgentManagePage.vue'),
          meta: { titleKey: 'admin.route.agentDetail' }
        },
        {
          path: 'channels',
          component: () => import('../views/admin/ChannelsPage.vue'),
          meta: { titleKey: 'admin.route.channels' }
        },
        {
          path: 'tools',
          component: () => import('../views/admin/ToolsPage.vue'),
          meta: { title: '工具' }
        },
        {
          path: 'connectors',
          component: () => import('../views/admin/ConnectorsPage.vue'),
          meta: { titleKey: 'admin.route.connectors' }
        },
        {
          path: 'tool-deployments',
          component: () => import('../views/admin/ToolDeploymentsPage.vue'),
          meta: { title: '工具部署' }
        },
        {
          path: 'capabilities/:id',
          component: () => import('../views/admin/CapabilityDetailPage.vue'),
          meta: { title: '能力详情' }
        },
        {
          path: 'capabilities',
          component: () => import('../views/admin/CapabilityCatalogPage.vue'),
          meta: { titleKey: 'admin.route.capabilities' }
        },
        {
          path: 'skills',
          component: () => import('../views/admin/SkillWorkbenchListPage.vue'),
          meta: { titleKey: 'admin.route.skills' }
        },
        {
          path: 'skills/:definitionId',
          component: () => import('../views/admin/SkillWorkbenchPage.vue'),
          meta: { titleKey: 'admin.route.skills' }
        },
        {
          path: 'api-docs',
          component: () => import('../views/admin/OpenApiDocsPage.vue'),
          meta: { titleKey: 'admin.route.apiDocs' }
        },
        {
          path: 'knowledge',
          component: () => import('../views/admin/KnowledgePage.vue'),
          meta: { titleKey: 'admin.route.knowledge' }
        },
        {
          path: 'memory',
          component: () => import('../views/admin/MemoryPage.vue'),
          meta: { titleKey: 'admin.route.memory' }
        },
        {
          path: 'settings',
          component: () => import('../views/admin/SettingsPage.vue'),
          meta: { titleKey: 'admin.route.settings' }
        },
        {
          path: 'knowledge/:id',
          component: () => import('../views/admin/KnowledgeManagePage.vue'),
          meta: { titleKey: 'admin.route.knowledge' }
        },
        {
          path: 'dashboard',
          component: () => import('../views/admin/DashboardPage.vue'),
          meta: { titleKey: 'admin.route.dashboard' }
        },
        {
          path: 'billing-records',
          component: () => import('../views/admin/BillingRecordsPage.vue'),
          meta: { titleKey: 'admin.route.billing' }
        },
        {
          path: 'governance',
          component: () => import('../views/admin/GovernancePage.vue'),
          meta: { titleKey: 'admin.route.governance' }
        },
        {
          path: 'handoff',
          component: () => import('../views/admin/HandoffPage.vue'),
          meta: { titleKey: 'admin.route.handoff' }
        },
        {
          path: 'marketplace',
          component: () => import('../views/admin/MarketplacePage.vue'),
          meta: { titleKey: 'admin.route.marketplace' }
        },
        { path: '', redirect: '/admin/home' }
      ]
    },
    {
      path: '/login',
      component: () => import('../views/LoginPage.vue'),
      meta: { public: true, titleKey: 'admin.app.loginTitle' }
    },
    {
      path: '/404',
      component: () => import('../views/NotFoundPage.vue'),
      meta: { requiresAuth: false, titleKey: 'admin.route.notFound' }
    },
    { path: '/403', component: () => import('../views/ForbiddenPage.vue') },
    { path: '/', redirect: '/admin/home' },
    // 规范 §2 无权限/未匹配路由兜底：未知路径统一落 404 页，避免白屏。
    { path: '/:pathMatch(.*)*', redirect: '/404' }
  ]
})

router.afterEach((to) => {
  const titleKey = typeof to.meta.titleKey === 'string' ? to.meta.titleKey : ''
  const pageTitle =
    typeof to.meta.title === 'string'
      ? to.meta.title
      : (ROUTE_TITLES[titleKey] ?? adminZhCN.app.title)
  document.title = `${pageTitle} · ${adminZhCN.app.brand}`
})

router.beforeEach(async (to) => {
  const auth = useAuthStore()
  if (to.meta.requiresAuth) {
    if (!auth.accessToken) return { path: '/login', query: { redirect: to.fullPath } }
    try {
      await auth.fetchMe()
    } catch {
      return { path: '/login', query: { redirect: to.fullPath } }
    }
    if (!auth.isAuthenticated) return { path: '/login', query: { redirect: to.fullPath } }
    const permission = routePermission(to.path, auth.user?.permissions)
    const headquartersUsage =
      to.path === '/admin/headquarters-usage' &&
      ['summary', 'details', 'events'].some((operation) =>
        auth.user?.permissions.includes(`tenant.sub_institutions.usage_${operation}.read`)
      )
    if (permission && !headquartersUsage && !auth.user?.permissions.includes(permission))
      return '/403'
  }
  return true
})

export default router
