/** Additional real UI acceptance; never invokes a provider or Runtime. */
import { randomUUID } from 'node:crypto'
import { writeFile } from 'node:fs/promises'
import { join } from 'node:path'

export async function runCritical(context, s, model, h) {
  const {
    api,
    flow,
    navigate,
    settle,
    input,
    field,
    select,
    editNumber,
    capture,
    assert,
    expect,
    redact,
    now
  } = h
  const array = (data) => data.items || data
  const governance = {
    model_invocable: true,
    user_invocable: true,
    input_schema: {},
    output_schema: {},
    supports_hitl: false,
    export_fields: ['messages'],
    timeout_ms: 300000,
    concurrency_safe: true
  }
  const definition = (id) => ({
    version: '1.0',
    entry: 'start',
    nodes: [
      { id: 'start', type: 'start', name: 'Owned start' },
      { id: 'end', type: 'end', name: 'Owned end' }
    ],
    edges: [{ from: 'start', to: 'end' }],
    meta: {
      name: id,
      version: '1.0.0',
      description: 'Owned zero-provider graph',
      capability: structuredClone(governance)
    }
  })
  const escape = (text) => text.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  async function choose(page, locator, label) {
    await locator.click()
    await page
      .locator('.el-select-dropdown__item:visible')
      .filter({ hasText: new RegExp('^' + escape(label) + '$') })
      .last()
      .click()
    await page.keyboard.press('Escape')
    await expect(page.locator('.el-select-dropdown:visible')).toHaveCount(0)
  }
  async function submit(page, scope, button, method, path, status = 200, confirmation = false) {
    const action = {
      kind: 'UI_CLICK_SUBMIT',
      button,
      method,
      path,
      expected_http_status: status,
      at: now()
    }
    s.current.actions.push(action)
    const uiEvidence = 'ui-' + String(s.current.actions.length).padStart(3, '0')
    await writeFile(
      join(s.current.dir, uiEvidence + '-intent.json'),
      JSON.stringify(action, null, 2),
      { flag: 'wx' }
    )
    const pending = page.waitForResponse(
      (r) => new URL(r.url()).pathname === '/api/v1' + path && r.request().method() === method,
      { timeout: 30000 }
    )
    pending.catch(() => {})
    await scope.getByRole('button', { name: button, exact: true }).click()
    if (confirmation) {
      const box = page.locator('.el-message-box:visible')
      await expect(box).toBeVisible()
      await box.getByRole('button', { name: button, exact: true }).click()
      action.confirmation = 'ACTUAL_VISIBLE_CONFIRMATION'
    }
    const response = await pending,
      body = await response.json()
    Object.assign(action, {
      actual_http_status: response.status(),
      actual_code: body.code,
      response: JSON.parse(redact(JSON.stringify(body)))
    })
    await writeFile(
      join(s.current.dir, uiEvidence + '-receipt.json'),
      JSON.stringify(action, null, 2),
      { flag: 'wx' }
    )
    for (const id of [
      body.data?.id,
      body.data?.agent_id,
      body.data?.workflow_id,
      body.data?.instance_id
    ])
      if (id) s.owned.add(String(id))
    assert(
      response.status() === status && body.code === 'OK',
      button + ' HTTP' + response.status() + ' ' + body.code
    )
    await settle(page)
    return body.data
  }
  if (s.host === 'admin') {
    await flow(context, s, 'workflow-graph-release', async (p) => {
      const id = s.prefix + 'graph'
      await api(s, 'POST', '/builder/workflows', {
        workflow_id: id,
        definition: definition(id)
      })
      const initial = await api(s, 'GET', '/builder/workflows/draft?workflowId=' + id)
      const initialValidation = await api(s, 'POST', '/builder/workflows/draft/validate', {
        workflow_id: id
      })
      assert(
        !initialValidation.problems.some((v) => v.severity === 'error'),
        'Legal initial governance fixture has validation errors'
      )
      s.current.governance_roundtrip = {
        before: initial.definition.meta.capability,
        initial_validation_errors: [],
        revision_before: initial.revision
      }
      await navigate(p, s, '/admin/workflows/' + id)
      await choose(p, p.locator('.toolbar .el-select').first(), '输出')
      await p.getByRole('button', { name: '添加节点', exact: true }).click()
      const name = s.prefix + 'output'
      await input(p, '节点名称').fill(name)
      await input(p, '输出消息').fill('Owned deterministic message ' + s.prefix)
      const node = p.locator('.workflow-node').filter({ hasText: name })
      const before = await node.evaluate((el) => ({ left: el.style.left, top: el.style.top }))
      await node.dragTo(p.locator('.workflow-canvas'), { targetPosition: { x: 350, y: 300 } })
      const after = await node.evaluate((el) => ({ left: el.style.left, top: el.style.top }))
      s.current.actions.push({ kind: 'UI_NATIVE_DRAG', before, after })
      assert(
        before.left !== after.left || before.top !== after.top,
        'Native drag did not move workflow node'
      )
      await capture(p, s, 'graph-drag')
      await p.getByRole('tab', { name: '连接与条件', exact: true }).click()
      let rows = p
        .getByRole('tabpanel', { name: '连接与条件', exact: true })
        .locator('.el-table__row:visible')
      await choose(p, rows.nth(0).locator('.el-select').nth(1), name)
      await rows.nth(0).getByPlaceholder('留空表示无条件', { exact: true }).fill('true')
      await p.getByRole('button', { name: '添加连接', exact: true }).click()
      rows = p
        .getByRole('tabpanel', { name: '连接与条件', exact: true })
        .locator('.el-table__row:visible')
      await choose(p, rows.nth(1).locator('.el-select').nth(0), name)
      await choose(p, rows.nth(1).locator('.el-select').nth(1), 'Owned end')
      s.current.actions.push({ kind: 'UI_GRAPH_CONNECTIONS', condition: 'true', edges: 2 })
      await submit(p, p, '保存', 'PUT', '/builder/workflows/draft')
      const draft = await api(s, 'GET', '/builder/workflows/draft?workflowId=' + id)
      const out = draft.definition.nodes.find((n) => n.name === name)
      assert(
        out?.type === 'output' && out.message === 'Owned deterministic message ' + s.prefix,
        'Persisted output node mismatch'
      )
      assert(
        draft.definition.edges.some(
          (e) => e.from === 'start' && e.to === out.id && e.condition === 'true'
        ) && draft.definition.edges.some((e) => e.from === out.id && e.to === 'end'),
        'Persisted connection condition mismatch'
      )
      const snapshot = await api(s, 'GET', '/builder/workflows/draft/snapshot?workflowId=' + id)
      s.current.layout_readback = snapshot
      assert(
        snapshot.draft_revision === draft.revision &&
          snapshot.layout[out.id].x === parseFloat(after.left) &&
          Math.abs(snapshot.layout[out.id].y - parseFloat(after.top)) < 0.002,
        'Actual drag layout was not persisted at saved revision'
      )
      const validation = await submit(p, p, '校验', 'POST', '/builder/workflows/draft/validate')
      Object.assign(s.current.governance_roundtrip, {
        after: draft.definition.meta.capability ?? null,
        revision_after: draft.revision,
        validation_problems: validation.problems
      })
      s.current.subfunctions = {
        node_graph_and_layout: 'PASS',
        governance_preservation: draft.definition.meta.capability ? 'PASS' : 'FAIL'
      }
      await p.getByRole('tab', { name: '校验与变更', exact: true }).click()
      await capture(p, s, 'governance-validation')
      assert(
        draft.definition.meta.capability &&
          !validation.problems.some((v) => v.severity === 'error'),
        'UI_SAVE_DROPPED_VALID_META_CAPABILITY: initial valid governance disappears after UI save; publish prerequisite rejected'
      )
    })
    await flow(context, s, 'workflow-valid-release', async (p) => {
      const id = s.prefix + 'valid-release'
      await api(s, 'POST', '/builder/workflows', { workflow_id: id, definition: definition(id) })
      const before = await api(s, 'POST', '/builder/workflows/draft/validate', { workflow_id: id })
      assert(!before.problems.some((v) => v.severity === 'error'), 'Publication fixture invalid')
      await navigate(p, s, '/admin/workflows/' + id)
      await submit(p, p, '校验', 'POST', '/builder/workflows/draft/validate')
      const published = await submit(p, p, '发布', 'POST', '/builder/workflows/publish')
      const history = await api(s, 'GET', '/builder/workflows/history?workflowId=' + id)
      const current = array(history).find((value) => value.current)
      assert(
        current?.definition_version_id === published.definition_version_id,
        'Published governance/history mismatch'
      )
      const publication = await api(
        s,
        'GET',
        '/builder/workflows/history/detail?workflowId=' +
          encodeURIComponent(id) +
          '&definitionVersionId=' +
          current.definition_version_id
      )
      assert(
        publication.workflow_id === id &&
          publication.definition_version_id === current.definition_version_id &&
          publication.definition.meta.capability,
        'Published governance/history mismatch'
      )
      await p.getByRole('button', { name: '安装', exact: true }).click()
      await settle(p)
      const drawer = p.locator('.el-drawer:visible')
      await expect(drawer).toBeVisible({ timeout: 180000 })
      await expect(drawer.getByRole('button', { name: '安装', exact: true })).toBeEnabled({
        timeout: 30000
      })
      await input(drawer, '自定义提示').fill('Owned valid install ' + s.prefix)
      const installed = await submit(p, drawer, '安装', 'POST', '/builder/workflows/install')
      const instances = await api(s, 'GET', '/builder/workflows/instances')
      assert(
        installed.instance_id &&
          array(instances).some(
            (v) =>
              v.instance_id === installed.instance_id &&
              v.workflow_id === id &&
              v.custom_prompt === 'Owned valid install ' + s.prefix
          ),
        'Installed valid workflow formal readback mismatch'
      )
      await navigate(p, s, '/admin/workflows')
      await expect(p.locator('.el-table__row:visible').filter({ hasText: id })).toHaveCount(1)
      s.current.subfunctions = {
        validation: 'PASS',
        publication: 'PASS',
        installation: 'PASS',
        owned_list_presence: 'PASS',
        runtime_execution: 'NOT_RUN_NO_PROVIDER_CALLS'
      }
    })
    await flow(context, s, 'agent-publish', async (p) => {
      const name = s.prefix + 'released-agent'
      await navigate(p, s, '/admin/agents')
      await p.getByRole('button', { name: '新建智能体', exact: true }).click()
      const drawer = p.locator('.el-drawer:visible')
      await input(drawer, '名称').fill(name)
      await input(drawer, '唯一标识').fill(name)
      await input(drawer, '系统提示词').fill('Owned publication only ' + s.prefix)
      const created = await submit(p, drawer, '保存', 'POST', '/agents', 200)
      const available = await api(s, 'GET', '/settings/available-models')
      const selected = array(available).find((v) => String(v.id) === String(model))
      assert(selected, 'Batch published model unavailable to institution actor')
      await select(p, p, '使用模型', selected.name)
      await submit(p, p, '保存草稿', 'PATCH', '/agents/update')
      await submit(p, p, '发布', 'POST', '/agents/publish', 200, true)
      const detail = await api(s, 'GET', '/agents/detail?agentId=' + created.id)
      const history = await api(s, 'GET', '/agents/revisions?agentId=' + created.id)
      assert(
        detail.published_revision != null && !detail.has_draft_changes,
        'Published Agent draft mismatch'
      )
      assert(array(history).length >= 1, 'Agent revision history empty after publish')
      await navigate(p, s, '/admin/agents')
      await p.getByPlaceholder('搜索智能体', { exact: true }).fill(name)
      await expect(p.locator('.el-table__row:visible')).toHaveCount(1)
      await expect(p.locator('.el-table__row:visible')).toContainText(name)
      s.current.actions.push({ kind: 'UI_AGENT_KEYWORD_FILTER', keyword: name, rows: 1 })
    })
  } else {
    await flow(context, s, 'model-settings', async (p) => {
      const base = await api(s, 'GET', '/admin/models/detail?id=' + model)
      const catalog = await api(s, 'GET', '/admin/models/providers')
      const provider = catalog.providers.find((v) => v.provider_id === base.provider_id)
      const service = provider?.services.find((v) => v.service_id === base.service_id)
      assert(provider && service, 'Batch provider/service missing from current catalog')
      await navigate(p, s, '/platform/models/new')
      const name = s.prefix + 'disabled-model'
      await input(p, '名称').fill(name)
      await select(p, p, '提供方', provider.display_name)
      await select(p, p, '提供方服务', service.display_name)
      if (base.protocol) await select(p, p, '协议', base.protocol)
      if (base.authentication_scheme) await select(p, p, '认证方式', base.authentication_scheme)
      const endpoint = input(p, '服务地址')
      if ((await endpoint.getAttribute('disabled')) === null) await endpoint.fill(base.endpoint)
      else
        assert(
          (await endpoint.inputValue()) === base.endpoint,
          'Disabled endpoint differs from batch provider'
        )
      const dummy = 'ui-unused-private-' + randomUUID()
      h.secrets.add(dummy)
      await input(p, 'API 凭据').fill(dummy)
      const modelInput = field(p, '模型标识').locator('input').first()
      await modelInput.fill(base.model_id)
      await p
        .locator('.el-select-dropdown__item:visible')
        .filter({ hasText: new RegExp('^' + escape(base.model_id) + '$') })
        .last()
        .click()
      await p.keyboard.press('Escape')
      await expect(field(p, '模型标识').locator('.el-select')).toContainText(base.model_id)
      const toggle = field(p, '启用').getByRole('switch')
      if ((await toggle.getAttribute('aria-checked')) === 'true')
        await field(p, '启用').locator('.el-switch').click()
      await expect(toggle).toHaveAttribute('aria-checked', 'false')
      for (const [label, value] of [
        ['超时（秒）', 45],
        ['支持最大输出 Token', base.supported_max_output_tokens],
        ['上下文窗口 Token', base.context_window_tokens],
        ['本次输出 Token 上限', base.max_output_tokens]
      ])
        await editNumber(p, p, label, value, s)
      await input(p, '每百万输入 Token（分）').fill('0')
      await input(p, '每百万输出 Token（分）').fill('0')
      const created = await submit(p, p, '保存草稿', 'POST', '/admin/models', 201)
      assert(
        created.enabled === false && created.credential_configured,
        'New model is enabled or credential was not saved'
      )
      await input(p, '名称').fill(name + '-saved')
      await submit(p, p, '保存草稿', 'PUT', '/admin/models/update')
      await submit(p, p, '发布', 'POST', '/admin/models/publish', 200, true)
      const detail = await api(s, 'GET', '/admin/models/detail?id=' + created.id)
      const history = await api(s, 'GET', '/admin/models/history?id=' + created.id)
      assert(
        !detail.enabled &&
          detail.name === name + '-saved' &&
          detail.timeout_seconds === 45 &&
          detail.published_revision > 0 &&
          !detail.has_unpublished_changes,
        'Model persisted publication mismatch'
      )
      const publishedHistory = array(history).find((v) => v.revision === detail.published_revision)
      assert(
        publishedHistory?.name === name + '-saved' &&
          publishedHistory.enabled === false &&
          publishedHistory.timeout_seconds === 45 &&
          publishedHistory.max_output_tokens === base.max_output_tokens,
        'Model independent published version payload mismatch'
      )
      assert(
        array(history).length >= 1 &&
          !JSON.stringify(detail).includes(dummy) &&
          !('api_key' in detail),
        'Model history or secret projection mismatch'
      )
      await navigate(p, s, '/platform/models')
      await p.getByPlaceholder('搜索模型名称、标识或提供方', { exact: true }).fill(name)
      await choose(p, p.locator('.filters .el-select').nth(0), 'LLM')
      await choose(p, p.locator('.filters .el-select').nth(1), '停用')
      await expect(p.locator('.el-table__row:visible')).toHaveCount(1)
      await expect(p.locator('.el-table__row:visible')).toContainText(name + '-saved')
      s.current.actions.push({
        kind: 'UI_MODEL_COMBINED_FILTER',
        keyword: name,
        kind_filter: 'LLM',
        status: 'disabled',
        rows: 1
      })
    })
    await flow(context, s, 'system-agent-settings', async (p) => {
      const name = s.prefix + 'custom-system-agent'
      await navigate(p, s, '/platform/system-agents')
      await p.getByRole('button', { name: '新增智能体', exact: true }).click()
      const drawer = p.locator('.el-drawer:visible')
      await input(drawer, '唯一标识').fill(name)
      await input(drawer, '名称').fill(name)
      await input(drawer, '说明').fill('Owned unused System Agent')
      const created = await submit(p, drawer, '创建智能体', 'POST', '/admin/system-agents')
      const models = await api(s, 'GET', '/admin/models')
      const available = array(models).find((v) => String(v.id) === String(model))
      assert(available, 'Published batch model not in platform model list')
      await input(p, '名称').fill(name + '-saved')
      await select(p, p, '使用模型', available.name)
      await input(p, '系统提示词').fill('Owned settings only ' + s.prefix)
      await editNumber(p, p, '超时（秒）', 45, s)
      await editNumber(p, p, '最大步骤', 3, s)
      await submit(p, p, '保存草稿', 'PUT', '/admin/system-agents/update')
      await submit(p, p, '发布', 'POST', '/admin/system-agents/publish', 200, true)
      const published = await api(s, 'GET', '/admin/system-agents/detail?agentId=' + created.id)
      assert(
        published.published?.system_prompt === 'Owned settings only ' + s.prefix &&
          published.published?.timeout_seconds === 45 &&
          published.published?.max_business_model_calls === 3,
        'System Agent published config mismatch'
      )
      await submit(p, p, '停用', 'POST', '/admin/system-agents/disable', 200, true)
      const disabled = await api(s, 'GET', '/admin/system-agents/detail?agentId=' + created.id)
      assert(String(disabled.status).toLowerCase() === 'disabled', 'System Agent was not disabled')
      await submit(p, p, '启用', 'POST', '/admin/system-agents/enable', 200, true)
      const enabled = await api(s, 'GET', '/admin/system-agents/detail?agentId=' + created.id)
      const versions = await api(s, 'GET', '/admin/system-agents/versions?agentId=' + created.id)
      assert(
        String(enabled.status).toLowerCase() === 'published' && array(versions).length >= 1,
        'System Agent enable/history mismatch'
      )
      // Retain an unused disabled custom key after acceptance.
      await submit(p, p, '停用', 'POST', '/admin/system-agents/disable', 200, true)
      await navigate(p, s, '/platform/system-agents')
      await p.getByPlaceholder('搜索智能体名称或标识', { exact: true }).fill(name)
      await expect(p.locator('.el-table__row:visible')).toHaveCount(1)
      await expect(p.locator('.el-table__row:visible')).toContainText(name + '-saved')
      s.current.actions.push({ kind: 'UI_SYSTEM_AGENT_KEYWORD_FILTER', keyword: name, rows: 1 })
    })
  }
  await flow(context, s, 'directory-filter', async (p) => {
    const path = s.host === 'admin' ? '/capabilities' : '/admin/capabilities'
    const keyword = s.prefix + 'directory'
    const definitions = []
    for (const type of ['TOOL', 'SKILL'])
      definitions.push(
        await api(s, 'POST', path + '/definitions', {
          capability_type: type,
          capability_key: keyword + '-' + type.toLowerCase(),
          name: keyword + '-' + type.toLowerCase(),
          description: 'Owned catalogue filter fixture'
        })
      )
    await navigate(p, s, '/' + s.host + '/capabilities')
    await p
      .getByRole('textbox', { name: '能力名称或标识', exact: true })
      .fill(keyword.toUpperCase())
    await expect(p.locator('.el-table__row:visible')).toHaveCount(2)
    const dropdown = p
      .locator('.el-select')
      .filter({ has: p.getByRole('combobox', { name: '能力类型', exact: true }) })
    for (const [label, type] of [
      ['工具', 'TOOL'],
      ['技能', 'SKILL']
    ]) {
      await choose(p, dropdown, label)
      await expect(p.locator('.el-table__row:visible')).toHaveCount(1)
      await expect(p.locator('.el-table__row:visible')).toContainText(
        keyword + '-' + type.toLowerCase()
      )
      s.current.actions.push({
        kind: 'UI_CATALOG_COMBINED_FILTER',
        case_insensitive_keyword: true,
        type,
        rows: 1
      })
      await capture(p, s, 'filter-' + type.toLowerCase())
    }
    await p.getByRole('textbox', { name: '能力名称或标识', exact: true }).fill(keyword + '-absent')
    await expect(p.locator('.el-table__row:visible')).toHaveCount(0)
    await expect(p.getByText('暂无符合条件的能力', { exact: true })).toBeVisible()
    await p.getByRole('textbox', { name: '能力名称或标识', exact: true }).fill(keyword)
    const data = await api(s, 'GET', path + '/definitions')
    assert(
      definitions.every((d) => array(data).some((v) => v.id === d.id)),
      'Prepared catalogue identities missing from formal readback'
    )
  })
  await flow(context, s, 'member-pagination', async (p) => {
    const path = s.host === 'admin' ? '/tenant/foundation/access' : '/admin/foundation/access'
    let roster = await api(s, 'GET', path + '/members?page=1&page_size=10&keyword=')
    if (roster.total < 11) {
      const role = await api(s, 'POST', path + '/role', {
        code: s.prefix.replaceAll('-', '_') + 'page_role',
        name: s.prefix + 'page-role',
        data_scope: 'self',
        permissions: s.identity.permissions.filter((v) => v.endsWith('.read')).slice(0, 1),
        department_ids: []
      })
      for (let i = roster.total; i < 11; i++)
        await api(s, 'POST', path + '/member', {
          username: s.prefix + 'page-member-' + i,
          display_name: s.prefix + 'page-member-' + i,
          email: null,
          password: h.fixturePassword,
          department_id: null,
          position_id: null,
          role_ids: [role.id]
        })
      roster = await api(s, 'GET', path + '/members?page=1&page_size=10&keyword=')
    }
    assert(
      roster.total >= 11,
      'Pagination prerequisite still has fewer than 11 owned-accessible members'
    )
    await navigate(p, s, '/' + s.host + '/foundation/access')
    const size = p.locator('.el-pagination__sizes .el-select')
    await size.click()
    await p
      .locator('.el-select-dropdown:visible .el-select-dropdown__item')
      .filter({ hasText: /^\s*10\b/ })
      .click()
    await settle(p)
    const next = p.locator('.el-pagination .btn-next')
    await expect(next).toBeEnabled()
    const pending = p.waitForResponse((r) => {
      const u = new URL(r.url())
      return (
        u.pathname === '/api/v1' + path + '/members' &&
        u.searchParams.get('page') === '2' &&
        u.searchParams.get('page_size') === '10'
      )
    })
    pending.catch(() => {})
    await next.click()
    const response = await pending,
      body = await response.json()
    assert(response.status() === 200 && body.code === 'OK', 'UI next-page request failed')
    await settle(p)
    const read = await api(s, 'GET', path + '/members?page=2&page_size=10&keyword=')
    const rows = p.locator('.el-table__row:visible')
    await expect(rows).toHaveCount(read.items.length)
    for (const member of read.items)
      await expect(rows.filter({ hasText: member.username })).toHaveCount(1)
    s.current.actions.push({
      kind: 'UI_REAL_PAGINATION',
      page: 2,
      page_size: 10,
      total: read.total,
      actual_status: response.status(),
      formal_row_ids: read.items.map((v) => v.id)
    })
    await capture(p, s, 'page-2')
    const chosen = read.items[0]
    await p.getByPlaceholder('用户名或显示名称', { exact: true }).fill(chosen.username)
    await p.getByRole('button', { name: '查询', exact: true }).click()
    await settle(p)
    await expect(rows).toHaveCount(1)
    const filter = await api(
      s,
      'GET',
      path + '/members?page=1&page_size=10&keyword=' + encodeURIComponent(chosen.username)
    )
    assert(
      filter.items.length === 1 && filter.items[0].id === chosen.id,
      'Pagination keyword did not reset/read back one member'
    )
    s.current.actions.push({
      kind: 'UI_PAGINATION_FILTER_RESET',
      page: 1,
      keyword: chosen.username,
      rows: 1
    })
  })
}
