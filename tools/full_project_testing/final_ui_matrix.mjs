/** Consumer boundary matrix: route smoke is never promoted to business acceptance. */
import { readFile, writeFile, mkdir } from 'node:fs/promises'
import { resolve, join, relative } from 'node:path'
import { createHash } from 'node:crypto'
const args = Object.fromEntries(
  process.argv.slice(2).reduce((a, v, i, s) => {
    if (v.startsWith('--')) a.push([v.slice(2), s[i + 1]])
    return a
  }, [])
)
const root = resolve(args.root),
  repo = resolve('.')
const read = async (p) => JSON.parse(await readFile(p, 'utf8'))
const inventory = await read(
  '.runtime-logs/full-project-test-20261012/ui-domain-inventory-v1/domain-inventory.json'
)
const lifecycle = await read(
  '.runtime-logs/full-project-test-20261012/ui-life-combined-v1/validation.json'
)
const critical = await read(args.critical)
if (
  inventory.route_count !== 75 ||
  inventory.round_records !== 225 ||
  lifecycle.effective_lifecycles !== 33 ||
  critical.records !== 27 ||
  critical.mechanical_integrity !== 'PASS'
)
  throw Error('Require original strict denominators and complete critical collection')
if (relative(resolve('.runtime-logs'), root).startsWith('..'))
  throw Error('Private evidence required')
const features = {
  'foundation/jobs': '动态处理器及参数、文件输入、预检、提交、取消、详情、错误行、轮询、产物下载',
  'foundation/access':
    '成员增改、部门树、岗位、角色权限及数据范围、资源共享、邀请、关键字查询和分页',
  'foundation/catalog': '字典及字典项、菜单、个人偏好、品牌、套餐、功能和配额',
  'foundation/security':
    'MFA、挑战及升级验证、恢复码、密码恢复、设备撤销、邀请激活、安全策略和成员重置',
  'foundation/messaging': '公告发布撤回、收件人、收件箱及已读状态、模板、SMTP、投递重试与核验',
  'foundation/assets': '文件上传、目录查询及分页、下载、删除和引用保护',
  'foundation/operations':
    '审计筛选及导出、保留策略、冻结候选预览及执行、调查 hold 和解除、Runtime 状态',
  home: '权限导航、统计加载及部分失败、身份和品牌显示',
  chat: '会话、流式消息、附件、任务表单、恢复、取消和执行观察',
  'chat/sessions/:sessionId/trace': '执行 Trace、工具结果、用量和公开证据',
  sessions: '会话目录、用户或时间筛选、分页和详情入口',
  'conversation-tasks': '任务目录、会话范围、分页、详情、修订及纠正操作',
  workflows: '工作流目录、新建草稿和详情入口',
  'workflows/create': 'UI 新建工作流、定义导入、节点及边、布局、草稿保存和验证',
  'workflows/:workflowId':
    '节点增删及拖拽、节点参数、连接条件及优先级、导入导出、布局、保存及CAS、校验、发布、安装、历史、diff及恢复',
  agents: '智能体创建、关键字查询、配置入口、模板安装和删除',
  'agents/:id': '名称、模型、系统提示词、运行预算、能力及资源关联、委派、草稿、发布、历史和恢复',
  'agent-deployments': '应用及模板选择、创建实例、资源绑定、就绪原因和CAS',
  'application-usage': '汇总、应用明细、时间条件及游标分页',
  teams: '团队创建及配置、成员角色和发布',
  channels: '渠道创建及配置、Agent关联、token/API key轮换和删除',
  connectors: 'MCP连接器配置、认证、工具发现、编辑和删除',
  'tool-deployments': '工具能力及版本、提供方和连接器关系、部署创建及配置',
  capabilities: '能力定义创建、关键字及类型组合查询和详情入口',
  'capabilities/:id': '能力信息、版本草稿编辑、JSON Schema、校验、发布、默认版本和下载',
  skills: 'Skill定义创建、列表查询和工作台入口',
  'skills/:definitionId':
    '文件增删及编辑、相对路径校验、ZIP导入、版本、草稿保存、校验、发布、默认版本及下载',
  'api-docs': '文档页签、代码展示、复制和OpenAPI接口说明',
  knowledge: '知识库创建、名称及分块/召回配置、目录、上传和删除',
  'knowledge/:id': '文档及分块、上传、内容保存删除、搜索、索引、修订、重建/重试/取消/发布',
  memory: '范围/Agent/用户目录、事实详情、审核批准拒绝纠正、CAS、遗忘及恢复',
  settings: '机构默认模型、本人密码',
  dashboard: '用量、费用、运行统计与依赖健康',
  'billing-records': '消费、请求及Trace查询、账单、生成和供应商价格',
  governance: '服务主体签发/轮换/暂停/吊销、Webhook、审计、知识提案批准和拒绝',
  handoff: '工单查询、详情及历史、接管、回复和解决',
  marketplace: '能力或Workflow查询、分类、分页、详情和安装',
  tenants: '机构目录、关键词/状态/套餐筛选、分页、新建和详情入口',
  'tenants/:id': '机构资料、套餐及配额、应用关联、订阅和生命周期',
  applications: '应用目录、查询、新建及详情',
  'applications/:id': '应用名称、策略和配额配置、凭据与用量',
  models: '模型目录、关键字/类型/状态筛选、重置及详情入口',
  'models/new':
    '新模型类型、提供方/服务/协议/认证、地址和凭据、容量/价格/图片策略、停用状态及草稿创建',
  'models/:id': '模型草稿更新、显式发布、历史恢复、凭据保留、容量/价格/图片策略、连接测试及发现',
  'system-settings': '全局会话工作区、运行预算和系统参数配置',
  'system-agents': '自定义System Agent创建、查询、状态及执行模式筛选',
  'system-agents/:id':
    '模型及提示词、超时/步骤、能力挂载、保存发布、停用启用、历史恢复、调试运行和归档删除',
  'system-agent-tools': '系统工具目录、能力和部署关联、配置及状态',
  'agent-templates': '模板目录、查询、新建、发布、下架和详情',
  'agent-templates/new': '模板UI创建、模型及提示词、分类价格、能力/资源/委派槽',
  'agent-templates/:id': '模板名称/提示词/分类、定义及槽、草稿保存、发布及版本历史',
  'sessions/detail': '会话上下文、版本化执行记录、Loop、工具、耗时、用量和附件',
  billing: '消费及账单查询、平台结算视图和账单详情',
  operations: '平台运行观察、时间查询、分页及Trace入口',
  review: '平台审核目录、详情、批准和拒绝'
}
const consumers = {
  'foundation/access': [
    ['organization-member', '部门创建、成员创建/资料编辑、部门及两个角色选择、关键字查询'],
    ['member-pagination', '每页10条、第2页、实际行身份及关键字重置到第1页']
  ],
  agents: [['agent-publish', 'UI创建及发布后关键字目录查询']],
  'agents/:id': [
    ['agent-config', '名称及系统提示词草稿保存和详情回读'],
    ['agent-publish', '模型选择、正式发布、详情与修订历史回读']
  ],
  'workflows/:workflowId': [
    ['workflow-conflict', '元信息修改、两个真实页面CAS409及刷新恢复、布局快照'],
    [
      'workflow-graph-release',
      '输出节点添加/参数、原生拖拽、两条连接与条件、草稿及布局持久回读',
      'node_graph_and_layout'
    ],
    [
      'workflow-graph-release',
      '合法治理图初始校验、输出节点添加/拖拽、连接条件、保存后的治理字段保留和校验'
    ],
    ['workflow-valid-release', '合法owned图不经图形编辑保存，真实UI发布及安装、历史和实例正式回读']
  ],
  workflows: [['workflow-valid-release', '发布后在真实UI工作流目录确认owned标识及入口']],
  knowledge: [['knowledge-config', '空库名称说明、分块/重叠/召回数的真实Tab键盘配置']],
  'applications/:id': [['application-policy', '应用名称和机构/成员上限配置保存及详情回读']],
  'agent-templates/:id': [['agent-template', '模板名称、提示词、分类草稿保存及详情回读']],
  capabilities: [['directory-filter', '大小写不敏感关键字、类型交集和空查询结果']],
  'capabilities/:id': [['tool-draft', '合法标题下TOOL版本创建编辑、错误名与JSON拒绝和回读']],
  'skills/:definitionId': [
    ['skill-files', '实际文件编辑及提交、路径/空版本拒绝；实际保存422和未覆盖回读']
  ],
  models: [['model-settings', '真实关键字/类型/停用状态组合目录筛选']],
  'models/new': [['model-settings', '真实UI创建独立停用LLM配置、保存凭据/容量/价格，未调用供应商']],
  'models/:id': [
    ['model-settings', '名称二次保存、正式发布、保持停用、公开凭据安全投影与历史回读']
  ],
  'system-agents': [['system-agent-settings', '真实UI创建自定义键和关键字查询']],
  'system-agents/:id': [
    ['system-agent-settings', '模型提示词、超时45/步骤3保存、发布、停用启用后停用、版本回读']
  ]
}
const foundationSources = {
  jobs: 'assets/FoundationJobsPage.vue',
  access: 'access/AccessPage.vue',
  catalog: 'catalog/CatalogPage.vue',
  security: 'security/SecuritySettingsPage.vue',
  messaging: 'messaging/MessagingPage.vue',
  assets: 'assets/FoundationFilesPage.vue',
  operations: 'operations/OperationsPage.vue'
}
const sha = (v) => createHash('sha256').update(v).digest('hex')
const rows = []
for (const item of inventory.routes) {
  const suffix = item.route.replace(new RegExp('^/' + item.host + '/'), '')
  const coverage = []
  for (const [key, scope, subfunction] of consumers[suffix] || []) {
    const candidates = (critical.record_bindings || []).filter(
      (r) => r.host === item.host && r.key === key
    )
    const prior = (lifecycle.effective_records || lifecycle.effective || []).filter(
      (r) => r.host === item.host && r.key === key
    )
    const records = candidates.length ? candidates : prior
    if (records.length !== 3)
      throw Error('Missing exact three business records ' + item.host + ':' + key)
    if (
      subfunction &&
      records.some((r) => !['PASS', 'FAIL'].includes(r.subfunctions?.[subfunction]))
    )
      throw Error('Unproved partial function boundary ' + key + ':' + subfunction)
    const status = (r) => (subfunction ? r.subfunctions[subfunction] : r.status)
    coverage.push({
      function: scope,
      key,
      status: records.every((r) => status(r) === 'PASS') ? 'PASS' : 'FAIL',
      subfunction: subfunction || null,
      consumer_boundary: 'REAL_UI_WITH_FORMAL_API_READBACK',
      rounds: records.map((r) => ({
        round: r.round,
        status: status(r),
        flow_status: r.status,
        result: r.path || r.result,
        reason: r.reason || null
      }))
    })
  }
  let sourcePath
  if (suffix.startsWith('foundation/'))
    sourcePath = resolve('frontend-shared/src/foundation', foundationSources[suffix.split('/')[1]])
  else if (item.component.startsWith('../'))
    sourcePath = resolve('frontend-' + item.host + '/src/router', item.component)
  const source = sourcePath ? await readFile(sourcePath, 'utf8') : ''
  const captions = [
    ...source.matchAll(/<(?:ElButton|el-button)\b[^>]*>([\s\S]*?)<\/(?:ElButton|el-button)>/g)
  ]
    .map((m) =>
      m[1]
        .replace(/<[^>]*>/g, ' ')
        .replace(/\s+/g, ' ')
        .trim()
    )
    .filter((v) => v && !v.includes('{{'))
  const labels = [
    ...source.matchAll(/<(?:ElTabPane|el-tab-pane|ElFormItem|el-form-item)\b[^>]*label="([^"]+)"/g)
  ].map((m) => m[1])
  const unsupported = []
  if (['capabilities', 'agents', 'models', 'system-agents'].includes(suffix))
    unsupported.push({
      function: '目录分页',
      status: 'UI_UNSUPPORTED',
      reason: '当前页面为本地筛选列表，没有分页控件'
    })
  if (['agents', 'agents/:id'].includes(suffix))
    unsupported.push({
      function: '克隆Agent',
      status: 'UI_UNSUPPORTED',
      reason: '当前Agent页面没有克隆入口'
    })
  if (['knowledge', 'knowledge/:id'].includes(suffix))
    unsupported.push({
      function: '知识库归档和恢复',
      status: 'UI_UNSUPPORTED',
      reason: '当前知识库页面没有归档恢复入口'
    })
  if (suffix === 'foundation/access')
    unsupported.push({
      function: '按部门筛选成员目录',
      status: 'UI_UNSUPPORTED',
      reason: '当前成员目录只有关键字查询，无部门筛选控件'
    })
  rows.push({
    host: item.host,
    route: item.route,
    implemented_functions:
      features[suffix] ||
      (item.route === '/'
        ? '默认入口重定向'
        : item.route === '/403'
          ? '公开无权页面'
          : '未匹配路由页面'),
    route_smoke_rounds: item.rounds.map((r) => ({
      round: r.round,
      status: r.status,
      result: r.result,
      capture: r.capture
    })),
    business_function_coverage: coverage,
    remaining_function_coverage: 'NOT_RUN',
    fully_accepted: false,
    unsupported_ui: unsupported,
    source: sourcePath
      ? {
          path: sourcePath,
          sha256: sha(source),
          static_button_captions: [...new Set(captions)],
          static_field_and_tab_labels: [...new Set(labels)]
        }
      : null,
    help_gap_controls: item.form_help_controls.length
  })
}
if (rows.length !== 75) throw Error('Incomplete75 route matrix')
const result = {
  scope: 'ALL_75_DECLARED_ADMIN_PLATFORM_ROUTES',
  fully_accepted: false,
  mechanical_integrity: 'PASS',
  denominators: {
    route_smoke: 225,
    route_smoke_statuses: inventory.route_statuses,
    lifecycle: 33,
    lifecycle_statuses: lifecycle.statuses || lifecycle.effective_statuses,
    critical: 27,
    critical_statuses: critical.statuses
  },
  help_gap_records: 153,
  consumer_failures_preserved: lifecycle.open_consumer_findings || lifecycle.open_findings || [],
  routes: rows
}
await mkdir(root, { recursive: false })
await writeFile(join(root, 'matrix.json'), JSON.stringify(result, null, 2), { flag: 'wx' })
const lines = [
  '# 双后台75路由功能覆盖矩阵',
  '',
  '路由冒烟与功能验收分开。每一行保留原3轮截图；PASS只适用于列出的真实UI及正式回读动作。其余已实现动作仍NOT_RUN，组件单测/Mock不替代真实消费验收。',
  '',
  '| 宿主 / 路由 | 已实现功能 | 已完成真实功能验收 | 其余动作 | UI缺失 |',
  '|---|---|---|---|---|',
  ...rows.map(
    (r) =>
      '| ' +
      r.host +
      ' `' +
      r.route +
      '` | ' +
      r.implemented_functions +
      ' | ' +
      (r.business_function_coverage
        .map(
          (c) =>
            c.status +
            '：' +
            c.function +
            '（' +
            c.rounds
              .map((n) => '[R' + n.round + '](' + n.result.replaceAll('\\', '/') + ')')
              .join(' / ') +
            '）'
        )
        .join('<br>') || 'NOT_RUN；仅路由冒烟') +
      ' | NOT_RUN | ' +
      (r.unsupported_ui.map((v) => v.function + '：UI_UNSUPPORTED').join('；') || '—') +
      ' |'
  )
]
await writeFile(join(root, 'matrix.md'), lines.join('\n') + '\n', { flag: 'wx' })
console.log(
  JSON.stringify({
    routes: 75,
    route_rounds: 225,
    lifecycle_rounds: 33,
    critical_rounds: 27,
    covered_route_function_groups: rows.filter((r) => r.business_function_coverage.length).length,
    fully_accepted: false
  })
)
