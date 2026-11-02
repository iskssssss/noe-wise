import { readFile, appendFile } from 'node:fs/promises'
import { resolve, relative, join } from 'node:path'
const args = Object.fromEntries(
  process.argv.slice(2).reduce((a, v, i, s) => {
    if (v.startsWith('--')) a.push([v.slice(2), s[i + 1]])
    return a
  }, [])
)
const validationPath = resolve(args.validation),
  report = resolve(args.report),
  inventoryPath = resolve(args.inventory)
if (relative(resolve('.agent-work-state/full-test-20261012'), report).startsWith('..'))
  throw Error('Owned report required')
const v = JSON.parse(await readFile(validationPath, 'utf8')),
  inventory = JSON.parse(await readFile(inventoryPath, 'utf8'))
if (
  v.mechanical_integrity !== 'PASS' ||
  v.effective_lifecycles !== 33 ||
  inventory.route_count !== 75
)
  throw Error('Complete strict evidence required')
const marker = '## 真实 UI 业务生命周期三轮验收（追加）'
if ((await readFile(report, 'utf8')).includes(marker)) throw Error('Refuse duplicate report append')
const grouped = new Map()
for (const r of v.effective) {
  const key = r.host + ':' + r.key
  if (!grouped.has(key)) grouped.set(key, [])
  grouped.get(key).push(r)
}
const descriptions = {
  'tool-draft':
    '名称必填和错误 JSON 拒绝；填写合法工具标题；创建201、二次编辑200；正式目录回读描述与 Schema',
  'skill-files':
    '路径越界拒绝、真实文件编辑、空版本拒绝；保存实际422；独立回读 revision1及空files保持未覆盖',
  'agent-config': '空名拒绝；修改名称及系统提示词，真实草稿保存；详情接口回读；未发布',
  'workflow-conflict':
    '工作流信息修改、布局快照；两个真实页面同 revision1，先保存200、陈旧保存409 CONFLICT、刷新后保存200；回读 winner/resolved 与 revision',
  'knowledge-config':
    '空名拒绝；真实Tab键盘修改分块640/重叠40/召回7；名称说明保存；空库正式详情回读',
  'application-policy':
    '空名拒绝；名称、机构上限3和成员上限9保存；正式详情回读，allow_provision=false',
  'agent-template': '空名拒绝；名称、系统提示词和分类草稿保存；正式详情回读 draft',
  'organization-member':
    'UI新建部门；新建成员时选择该部门及两个自定义只读角色；关键字查询单行；资料编辑保存；正式成员目录回读'
}
const lines = [
  '',
  marker,
  '',
  `本批完整原始执行 v2：33 条生命周期记录，12 PASS / 21 FAIL（实际退出1）；追加 v3：15 条记录，15 PASS（实际退出0）。以成功路径为单位汇总 11 项 × 3 轮 = 33 条：27 PASS / 6 FAIL。汇总只说明已选正向路径，默认空标题与 ARIA 状态故障仍作为独立未解决消费者问题保留。严格采集器仅确认机械证据完整，业务总状态为 FAIL。`,
  '',
  `两批分别完成 6 次真实密码登录及 /me full 回读，共 ${v.actual_authentications} 次；每轮使用新建 ui-life 资源，机构专属账号 ui-smoke-admin-20261012，平台专属账号 ui-life-platform-20261012（成员 858552488624133）。实际 Vite 配置、随机 loopback端口与原有机构同源代理复用；CORS验收仍为NOT_RUN。供应商调用0、Runtime执行0。`,
  '',
  `严格数据：[validation.json](${validationPath.replaceAll('\\', '/')})；路由机器清单：[domain-inventory.json](${inventoryPath.replaceAll('\\', '/')})。路由清单保持75入口×3轮、225结果与225截图，225条的business_acceptance=NOT_RUN；153条具体help缺口控件映射到原始截图，不把路由冒烟改写为业务验收。`,
  '',
  '| 宿主 / 流程 | 真实 UI 操作及回读边界 | 第1轮 | 第2轮 | 第3轮 |',
  '|---|---|---|---|---|'
]
for (const [key, rows] of grouped) {
  rows.sort((a, b) => a.round - b.round)
  lines.push(
    `| ${key} | ${descriptions[rows[0].key]} | ${rows.map((r) => `[${r.status}](${r.result.replaceAll('\\', '/')})`).join(' | ')} |`
  )
}
lines.push(
  '',
  '### 仍未解决的真实消费者问题',
  '',
  '1. **工具标题可选字段默认空值保存422**：两端各3轮。共享页面 form.title 默认空字符串，无必填规则，descriptor 无条件写入 title；ToolDefinition.optionalText 对空字符串拒绝。真实请求、422信封和截图在v2 tool-draft结果中。填写合法标题后成功保存只证明合法路径，不关闭此缺陷。',
  '2. **Skill明文文件与Base64传输契约冲突**：两端各3轮。CodeMirror真实输入SKILL.md正文，shared skillDrafts payload.files直接传递编辑器字符串，后端SkillDraftService.decodeFiles使用Base64解码。实际保存422 VALIDATION_ERROR；随后独立GET确认revision1→1且files{}，没有覆盖或发布。',
  '3. **知识库数字输入ARIA与原生状态不一致**：3个控件×3轮=9记录。aria-disabled=true，但原生disabled不存在；真实Tab可到达控件并输入保存成功，鼠标/fill路径原始超时保留。成功键盘路径不关闭可访问性缺陷。',
  '',
  '平台 Dashboard /health/deps 404、平台Session执行记录关联/Loop/实际耗时缺口仍沿用此前原始路由证据，由对应领域进行归因；未修改业务源码。',
  '',
  '### UI缺失与未执行范围',
  '',
  '- 当前Agent页面没有克隆操作；知识库页面没有归档/恢复操作；组织成员页面只有关键字查询，没有部门筛选控件。分别记UI_UNSUPPORTED，未用API操作冒充UI覆盖。',
  '- 成员用例的两个自定义只读角色由API准备；UI真正测试的是部门及角色选择、成员创建、查询、资料编辑。角色编辑器、权限联合下的该成员执行请求仍是独立覆盖项。',
  '- 工作流本批覆盖信息修改、真实CAS冲突及自动布局快照；节点拖拽、条件编辑、导入、发布、安装和运行是独立覆盖项。',
  '- 未提交聊天/LLM、知识内容索引/搜索/重建、发布/安装/执行、平台凭证或机构开通。',
  '',
  '### 原始失败、纠正与完整性',
  '',
  'v1为选择器/边界诊断批，标记INTERRUPTED_DIAGNOSTIC，原始截图与请求收据保留，不计三轮验收。v2完整保留21次失败：默认空标题6、Skill编码6、数字fill3、API准备编码不合规6。v3只追加合法标题路径、真实Tab数字操作和合法下划线组织编码；没有修改前端或清空旧证据。',
  '',
  `两批各绑定258个机构/平台/共享源码及配置文件，最终SHA漂移0；脚本快照保存runner.source.mjs。采集器验证严格分母、六轮宿主登录、正式API收据、结果一致、PNG尺寸/哈希、owned资源、429及写守卫。API准备intent→receipt和UI提交/回读分开标识，无法确认的写入不重放。`,
  '',
  '保留新资源的owned-resources.json与专属平台账号manifest.json，以便批次结束后清理；本域未删除旧资源、重启生产服务、运行Gradle、提交或推送。',
  '',
  '执行命令及真实退出码见 ui-real-lifecycles-v2-command.log（exit1）与 ui-real-lifecycles-v3-command.log（exit0）。新增采集器 incomplete negative：exit1且没有输出目录；overwrite negative：拒绝覆盖且原validation与报告SHA保持不变。',
  ''
)
await appendFile(report, lines.join('\n'), 'utf8')
console.log(JSON.stringify({ status: 'APPENDED', report, rows: grouped.size }))
