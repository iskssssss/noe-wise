import { routePermission } from '@skillforge/shared/foundation/navigation'
import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '../stores/auth'
const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', redirect: '/platform/home' },
    { path: '/login', component: () => import('../views/LoginPage.vue') },
    {
      path: '/platform',
      component: () => import('../views/platform/Layout.vue'),
      meta: { requiresAuth: true },
      children: [
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
        { path: '', redirect: '/platform/home' },
        {
          path: 'home',
          component: () => import('../views/platform/HomePage.vue'),
          meta: { title: '工作台' }
        },
        {
          path: 'dashboard',
          component: () => import('../views/platform/DashboardPage.vue'),
          meta: { title: '系统状态' }
        },
        {
          path: 'tenants',
          component: () => import('../views/platform/InstitutionWorkspacePage.vue'),
          meta: { title: '机构工作台', institutionWorkspace: true }
        },
        {
          path: 'management-principals',
          component: () => import('../views/platform/ManagementPrincipalsPage.vue'),
          meta: { title: '平台管理身份' }
        },
        {
          path: 'institutions',
          component: () => import('../views/platform/InstitutionIntegrationsPage.vue'),
          meta: { title: '机构开通与映射' }
        },
        {
          path: 'institution-relations',
          component: () => import('../views/platform/InstitutionRelationsPage.vue'),
          meta: { title: '机构层级' }
        },
        {
          path: 'institution-credentials',
          component: () => import('../views/platform/InstitutionCredentialsPage.vue'),
          meta: { title: '机构执行凭证' }
        },
        {
          path: 'agent-grants',
          component: () => import('../views/platform/AgentGrantsPage.vue'),
          meta: { title: '定义直接授权' }
        },
        {
          path: 'agent-deployments',
          component: () => import('../views/platform/AgentDeploymentsPage.vue'),
          meta: { title: '机构智能体部署' }
        },
        {
          path: 'usage',
          component: () => import('../views/platform/UsagePage.vue'),
          meta: { title: '平台用量' }
        },
        {
          path: 'tenants/:id',
          component: () => import('../views/platform/InstitutionWorkspacePage.vue'),
          meta: { title: '机构工作台', institutionWorkspace: true }
        },
        {
          path: 'models',
          component: () => import('../views/platform/ModelsPage.vue'),
          meta: { title: '模型管理' }
        },
        {
          path: 'models/new',
          component: () => import('../views/platform/ModelDetailPage.vue'),
          meta: { title: '新建模型' }
        },
        {
          path: 'models/:id',
          component: () => import('../views/platform/ModelDetailPage.vue'),
          meta: { title: '模型配置' }
        },
        {
          path: 'system-settings',
          component: () => import('../views/platform/SystemSettingsPage.vue'),
          meta: { title: '系统参数' }
        },
        {
          path: 'system-agents',
          component: () => import('../views/platform/SystemAgentsPage.vue'),
          meta: { title: '系统智能体' }
        },
        {
          path: 'system-agents/:id',
          component: () => import('../views/platform/SystemAgentDetailPage.vue'),
          meta: { title: '智能体配置' }
        },
        {
          path: 'system-agent-tools',
          component: () => import('../views/platform/SystemAgentToolsPage.vue'),
          meta: { title: '工具部署' }
        },
        {
          path: 'agent-templates',
          component: () => import('../views/platform/AgentTemplatesPage.vue'),
          meta: { title: '智能体模板' }
        },
        {
          path: 'agent-templates/new',
          component: () => import('../views/platform/AgentTemplateDetailPage.vue'),
          meta: { title: '新建模板' }
        },
        {
          path: 'agent-templates/:id',
          component: () => import('../views/platform/AgentTemplateDetailPage.vue'),
          meta: { title: '模板配置' }
        },
        {
          path: 'capabilities',
          component: () => import('../views/platform/CapabilityCatalogPage.vue'),
          meta: { title: '能力目录' }
        },
        {
          path: 'capabilities/:id',
          component: () => import('../views/platform/CapabilityDetailPage.vue'),
          meta: { title: '能力版本' }
        },
        {
          path: 'skills',
          component: () => import('../views/platform/SkillWorkbenchListPage.vue'),
          meta: { title: 'Skill 工作台' }
        },
        {
          path: 'skills/:definitionId',
          component: () => import('../views/platform/SkillWorkbenchPage.vue'),
          meta: { title: 'Skill 编辑' }
        },
        {
          path: 'sessions',
          component: () => import('../views/platform/SessionsPage.vue'),
          meta: { title: '会话观察' }
        },
        {
          path: 'sessions/detail',
          component: () => import('../views/platform/SessionDetailPage.vue'),
          meta: { title: '会话详情' }
        },
        {
          path: 'trajectories',
          component: () => import('../views/platform/TrajectoriesPage.vue'),
          meta: { title: 'Agent 轨迹' }
        },
        {
          path: 'trajectories/detail',
          component: () => import('../views/platform/TrajectoryDetailPage.vue'),
          meta: { title: '轨迹详情' }
        },
        {
          path: 'billing',
          component: () => import('../views/platform/BillingPage.vue'),
          meta: { title: '模型账单' }
        },
        {
          path: 'operations',
          component: () => import('../views/platform/OperationsPage.vue'),
          meta: { title: '运维审计' }
        },
        {
          path: 'review',
          component: () => import('../views/platform/ReviewPage.vue'),
          meta: { title: '市场审核' }
        }
      ]
    },
    { path: '/403', component: () => import('../views/platform/ForbiddenPage.vue') },
    { path: '/:pathMatch(.*)*', component: () => import('../views/platform/NotFoundPage.vue') }
  ]
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
    const usageAllowed =
      to.path === '/platform/usage' &&
      [
        'platform.usage.events.read',
        'platform.usage.summary.read',
        'platform.usage.details.read'
      ].some((action) => auth.user?.permissions.includes(action))
    if (permission && !auth.user?.permissions.includes(permission) && !usageAllowed) return '/403'
  }
  return true
})
router.afterEach((to) => {
  document.title = `${to.meta.title ?? '平台运营'} · 灵知`
})
export default router
