import { readFile, appendFile } from 'node:fs/promises'
import { resolve } from 'node:path'
const args = Object.fromEntries(
  process.argv.slice(2).reduce((a, v, i, s) => {
    if (v.startsWith('--')) a.push([v.slice(2), s[i + 1]])
    return a
  }, [])
)
const validation = JSON.parse(await readFile(resolve(args.validation), 'utf8'))
const matrix = JSON.parse(await readFile(resolve(args.matrix), 'utf8'))
const report = resolve('.agent-work-state/full-test-20261012/ui.md')
const marker = '## 关键业务补测与75路由最终功能矩阵'
if ((await readFile(report, 'utf8')).includes(marker)) throw Error('Refuse duplicate report append')
if (
  validation.mechanical_integrity !== 'PASS' ||
  validation.records !== 27 ||
  matrix.routes.length !== 75 ||
  matrix.denominators.route_smoke !== 225 ||
  matrix.denominators.lifecycle !== 33
)
  throw Error('Require strict evidence before report')
const link = (label, path) => '[' + label + '](' + path.replaceAll('\\', '/') + ')'
const scopes = {
  'workflow-graph-release':
    '合法治理初图校验；输出节点、原生拖拽、连接条件、草稿及布局回读；治理字段被UI保存丢失',
  'workflow-valid-release':
    '另一个合法owned图，真实UI校验、发布、安装；历史和实例正式回读、工作流目录身份确认；不执行',
  'agent-publish': '真实UI新建Agent、选择模型、草稿、确认发布、修订历史及关键字查询；不聊天',
  'directory-filter':
    'owned工具/Skill目录，大写关键字、类型交集、空结果、正式目录回读；当前没有分页UI',
  'member-pagination': '真实每页10、第2页、行身份与正式目录一致、关键字查询重置第1页',
  'model-settings':
    'UI新建停用LLM配置、凭据安全投影、容量及零价格、保存及发布、历史、关键字/类型/停用交集；不test/discover',
  'system-agent-settings':
    '新自定义键，UI创建、模型及提示词、超时45/步骤3、草稿、发布、停用启用、最终停用及版本回读；不debug/run'
}
const groups = new Map()
for (const record of validation.record_bindings) {
  const key = record.host + ':' + record.key
  if (!groups.has(key)) groups.set(key, [])
  groups.get(key).push(record)
}
const rows = [...groups.entries()].map(([name, records]) => {
  records.sort((a, b) => a.round - b.round)
  return (
    '| ' +
    name +
    ' | ' +
    scopes[records[0].key] +
    ' | ' +
    records.map((r) => link(r.status, r.path)).join(' | ') +
    ' |'
  )
})
const lines = [
  '',
  marker,
  '',
  '本次新增有效分母9条流程×3轮=27，状态' +
    JSON.stringify(validation.statuses) +
    '；原始与纠正完整批次共' +
    validation.raw_records +
    '条，不将纠正后成功路径覆盖原失败。原路由分母225（222 SMOKE_PASS/3 FAIL）及原生命周期33（27 PASS/6 FAIL）保持不变。机械完整性PASS，业务总状态' +
    validation.business_acceptance +
    '。',
  '',
  '严格采集 ' +
    link('validation.json', resolve(args.validation)) +
    '；全部75路由功能矩阵 ' +
    link('matrix.md', resolve(args.matrix).replace('matrix.json', 'matrix.md')) +
    ' / ' +
    link('matrix.json', resolve(args.matrix)) +
    '。矩阵列出已实现功能、源按钮/字段、3轮原截图和当前真实功能证据，未实际执行的独立动作保持NOT_RUN，UI不存在的分页/克隆/归档恢复/部门筛选单列UI_UNSUPPORTED。',
  '',
  '完整批次共' +
    validation.actual_authentications +
    '次真实密码登录及/me full回读，' +
    validation.screenshots +
    '张独立截图。各源绑定258个生产前端/共享/配置输入，SHA漂移0；新纠正批UI每笔提交有磁盘intent和receipt及trace_id，API准备与消费者回读分开。供应商调用0、Runtime运行0、真实机构CORS仍NOT_RUN。',
  '',
  '| 宿主 / 流程 | 验证边界 | 第1轮 | 第2轮 | 第3轮 |',
  '|---|---|---|---|---|',
  ...rows,
  '',
  '**新确认的消费者缺陷：Workflow UI保存丢失meta.capability。** 三轮完整合法定义先validate无error；真实图形编辑保存200后revision1→2，正式回读治理对象从完整8字段变成null，校验WF_CAPABILITY_GOVERNANCE_REQUIRED，发布前置被拒绝。实际节点、消息、两条连接及true条件、原生拖拽布局均持久化成功。frontend-admin/src/utils/workflow.ts definitionMeta/detailToMeta没有治理字段，当前UI也无对应编辑入口。另一路合法定义不经图形编辑保存能真实发布及安装，不关闭该缺陷。',
  '',
  '工具默认空标题422、两端Skill明文/Base64保存422、知识数字ARIA状态不一致、平台Dashboard健康404仍保留此前原证据；新增通过不关闭这些问题。',
  '',
  '**诊断与协作边界。** critical-v1是选择器/状态假设诊断，owned PID15908终止、退出-1；critical-v2由全局System Agent配置冻结要求停止，owned PID49880退出-1，均有interrupted.json，不计完整三轮。首轮v1和v2分别创建System Agent858561583243269（最终published revision5）与858562789318661（最终disabled revision6）；精确写时间/回执在各自result.actions，已交root及chat owner关联Core摘要漂移。冻结期间没有续写/回滚/修改三个builtin purpose，System Agent补测须在root明确解除冻结后完成。',
  '',
  '完整v3保留旧夹具治理前置和allow-create输入未确认的失败；v4以合法治理图证明真实字段丢失，模型创建/保存/发布已成功但旧断言把草稿revision和发布序列混为一谈，安装等待过短。v6合法图真实发布安装3/3通过，模型仅目录选择器假设失败；v7按当前Element Select的真实DOM修正选择器后模型设置3/3通过。纠正只调整工具，按可见drawer、busy完成、独立发布序列和正式实例关联核验，保留全部原诊断，不改生产源码。',
  '',
  '**后续边界和人工介入项。** 其余UI动作仍应按75路由矩阵自动化推进，不能因为只做过路由访问或组件Mock而宣称全部业务验收。当前明确阻断是源码缺陷和按批次隔离全局配置写入；修复后需针对消费者重新验收。涉及真实供应商费用、外部邮件/Webhook、全局配置及数据库清理的动作应先具备该批明确授权和目标环境，不能当作必须人工操作。视觉审美、真实输入法/读屏用户体验若需要额外验收由人工补充。',
  '',
  'owned-resources.json、模型/Agent/Workflow新资源及诊断资源均保留供批次清理。未启动/重启生产服务、运行Gradle、清库、提交或推送。',
  ''
]
await appendFile(report, lines.join('\n'), 'utf8')
console.log(
  JSON.stringify({ report, flows: 9, records: 27, statuses: validation.statuses, route_matrix: 75 })
)
