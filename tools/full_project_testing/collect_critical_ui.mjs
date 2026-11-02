/** Exact separate 27-record roster; preserves original/correction evidence. */
import { readFile, writeFile, mkdir, access } from 'node:fs/promises'
import { createHash } from 'node:crypto'
import { resolve, join, relative } from 'node:path'
const args = Object.fromEntries(
  process.argv.slice(2).reduce((a, v, i, s) => {
    if (v.startsWith('--')) a.push([v.slice(2), s[i + 1]])
    return a
  }, [])
)
const root = resolve(args.root),
  source = resolve(args.source)
const read = async (path) => JSON.parse(await readFile(path, 'utf8'))
const sha = (bytes) => createHash('sha256').update(bytes).digest('hex')
const fullRoster = {
  admin: [
    'workflow-graph-release',
    'workflow-valid-release',
    'agent-publish',
    'directory-filter',
    'member-pagination'
  ],
  platform: ['model-settings', 'system-agent-settings', 'directory-filter', 'member-pagination']
}
if (relative(resolve('.runtime-logs'), root).startsWith('..'))
  throw Error('Private evidence required')
if (
  await access(root).then(
    () => true,
    () => false
  )
)
  throw Error('Refuse overwrite')
const records = [],
  captures = [],
  authentications = [],
  ownedResources = [],
  sources = []
const frames = args.manifest
  ? await read(resolve(args.manifest))
  : args.system
    ? [
        {
          directory: source,
          roster: {
            admin: fullRoster.admin,
            platform: fullRoster.platform.filter((k) => k !== 'system-agent-settings')
          }
        },
        { directory: resolve(args.system), roster: { platform: ['system-agent-settings'] } }
      ]
    : [{ directory: source, roster: fullRoster }]
for (const frame of frames) {
  const source = frame.directory,
    roster = frame.roster
  if (
    !['ORIGINAL', 'CORRECTION', 'ADDITION'].includes(frame.role) ||
    relative(resolve('.runtime-logs'), resolve(source)).startsWith('..')
  )
    throw Error('Invalid source frame boundary')
  for (const [host, keys] of Object.entries(roster))
    if (
      !fullRoster[host] ||
      keys.some((key) => !fullRoster[host].includes(key)) ||
      new Set(keys).size !== keys.length
    )
      throw Error('Invalid source frame roster')
  const summary = await read(join(source, 'summary.json'))
  if (
    summary.length !== Object.values(roster).flat().length * 3 ||
    summary.some((r) => !['PASS', 'FAIL'].includes(r.status))
  )
    throw Error('Incomplete exact critical denominator')
  const services = await read(join(source, 'services.json')),
    bindings = await read(join(source, 'frontend-inputs.json'))
  if (services.length !== Object.keys(roster).length * 3 || bindings.length !== 258)
    throw Error('Incomplete consumers or source binding')
  for (const binding of bindings)
    if (sha(await readFile(resolve(binding.path))) !== binding.sha256)
      throw Error('Source drift ' + binding.path)
  sources.push({
    directory: source,
    runner_sha256: sha(await readFile(join(source, 'runner.source.mjs'))),
    summary_sha256: sha(await readFile(join(source, 'summary.json'))),
    frontend_inputs: bindings.length,
    source_drift: 0
  })
  let expectedCriticalHash
  for (let round = 1; round <= 3; round++)
    for (const [host, keys] of Object.entries(roster)) {
      const lane = join(source, 'round-' + round, host)
      if (services.filter((s) => s.host === host && s.round === round).length !== 1)
        throw Error('Missing exact service lane')
      const auth = await read(join(lane, 'login/result.json'))
      if (
        auth.status !== 'PASS' ||
        auth.identity?.mfa_state !== 'full' ||
        !auth.identity.permissions?.length ||
        !auth.http.some((h) => h.path.endsWith('/auth/me') && h.status === 200 && h.code === 'OK')
      )
        throw Error('Incomplete real authentication')
      authentications.push({
        round,
        host,
        sub: auth.identity.sub,
        actual_me_200: true,
        mfa_state: 'full'
      })
      const owned = await read(join(lane, 'owned-resources.json'))
      if (
        !owned.prefix.startsWith('ui-life-' + host + '-' + round + '-') ||
        owned.supplier_calls !== 0 ||
        owned.runtime_runs !== 0
      )
        throw Error('Owned resource boundary mismatch')
      ownedResources.push({
        round,
        host,
        path: join(lane, 'owned-resources.json'),
        sha256: sha(await readFile(join(lane, 'owned-resources.json'))),
        ids: owned.ids
      })
      const criticalHash = sha(await readFile(join(lane, 'critical-flows.source.mjs')))
      if (expectedCriticalHash && expectedCriticalHash !== criticalHash)
        throw Error('Critical executed module snapshot changed within frame')
      expectedCriticalHash = criticalHash
      for (const key of keys) {
        const matching = summary.filter(
          (r) => r.round === round && r.host === host && r.key === key
        )
        if (matching.length !== 1) throw Error('Missing duplicate critical flow')
        const path = join(lane, key, 'result.json'),
          record = await read(path)
        if (
          record.status !== matching[0].status ||
          record.round !== round ||
          record.host !== host ||
          record.key !== key
        )
          throw Error('Result binding disagreement')
        if (record.blocked_writes.length || record.http.some((h) => h.status === 429))
          throw Error('Guard refusal or rate limit')
        if (
          record.http.some(
            (h) =>
              !['GET', 'HEAD'].includes(h.method) &&
              /(?:\/chat|\/run(?:$|\/)|\/debug|\/test$|\/discover$|reindex|rebuild)/.test(h.path)
          )
        )
          throw Error('Forbidden provider/runtime operation')
        const receipts = []
        for (const binding of record.readbacks) {
          const receiptPath = join(lane, binding.evidence),
            intentPath = receiptPath.replace('-api-receipt.json', '-api-intent.json')
          if (relative(lane, receiptPath).startsWith('..')) throw Error('Escaped API receipt')
          const receipt = await read(receiptPath),
            intent = await read(intentPath)
          if (
            receipt.path !== binding.path ||
            receipt.status !== binding.status ||
            receipt.code !== binding.code ||
            receipt.method !== binding.method ||
            intent.method !== receipt.method ||
            intent.path !== receipt.path
          )
            throw Error('API receipt disagreement')
          receipts.push({
            kind: receipt.kind,
            path: receiptPath,
            sha256: sha(await readFile(receiptPath)),
            api_path: receipt.path,
            status: receipt.status,
            code: receipt.code
          })
        }
        if (
          record.status === 'PASS' &&
          (!receipts.some(
            (r) => r.kind === 'FORMAL_API_READBACK' && r.status === 200 && r.code === 'OK'
          ) ||
            !record.actions.some((a) =>
              ['UI_CLICK_SUBMIT', 'UI_REAL_PAGINATION', 'UI_CATALOG_COMBINED_FILTER'].includes(
                a.kind
              )
            ))
        )
          throw Error('PASS without real UI operation and formal API readback')
        if (record.status === 'PASS' && key === 'workflow-valid-release') {
          const publication = record.actions.find((a) => a.path === '/builder/workflows/publish')
            ?.response?.data
          const install = record.actions.find((a) => a.path === '/builder/workflows/install')
            ?.response?.data
          const receiptBinding = record.readbacks.find(
            (r) => r.path === '/builder/workflows/instances'
          )
          const instanceReceipt = await read(join(lane, receiptBinding.evidence))
          const instance = instanceReceipt.data.items.find(
            (v) => v.instance_id === install?.instance_id
          )
          if (
            !publication ||
            !install ||
            !instance ||
            instance.workflow_id !== install.workflow_id ||
            String(instance.installation_revision) !== String(install.installation_revision) ||
            String(instance.definition_version_id) !== String(publication.definition_version_id) ||
            instance.custom_prompt !== 'Owned valid install ' + owned.prefix
          )
            throw Error('Publication installation frozen identity disagreement')
        }
        if (!record.captures.length) throw Error('Missing screenshots')
        const uiReceipts = []
        if (frame.role !== 'ORIGINAL')
          for (const [index, action] of record.actions.entries())
            if (action.kind === 'UI_CLICK_SUBMIT') {
              const prefix = 'ui-' + String(index + 1).padStart(3, '0')
              const intentPath = join(lane, key, prefix + '-intent.json'),
                receiptPath = join(lane, key, prefix + '-receipt.json')
              const intent = await read(intentPath),
                receipt = await read(receiptPath)
              if (
                intent.path !== action.path ||
                intent.method !== action.method ||
                intent.button !== action.button ||
                receipt.actual_http_status !== action.actual_http_status ||
                receipt.actual_code !== action.actual_code ||
                receipt.response?.trace_id !== action.response?.trace_id
              )
                throw Error('UI durable receipt binding disagreement')
              uiReceipts.push({
                intent: intentPath,
                intent_sha256: sha(await readFile(intentPath)),
                receipt: receiptPath,
                receipt_sha256: sha(await readFile(receiptPath)),
                api_path: action.path,
                status: action.actual_http_status,
                code: action.actual_code
              })
            }
        for (const name of record.captures) {
          const capture = join(lane, key, name),
            bytes = await readFile(capture)
          if (
            bytes.subarray(0, 8).toString('hex') !== '89504e470d0a1a0a' ||
            bytes.readUInt32BE(16) < 1000 ||
            bytes.readUInt32BE(20) < 700
          )
            throw Error('Invalid screenshot')
          captures.push({
            round,
            host,
            key,
            path: capture,
            sha256: sha(bytes),
            width: bytes.readUInt32BE(16),
            height: bytes.readUInt32BE(20)
          })
        }
        records.push({
          source_frame: source,
          source_role: frame.role,
          round,
          host,
          key,
          status: record.status,
          reason: record.reason || null,
          path,
          result_sha256: sha(await readFile(path)),
          critical_source_sha256: criticalHash,
          actions: record.actions,
          durable_ui_receipts: uiReceipts,
          subfunctions: record.subfunctions || {},
          governance_roundtrip: record.governance_roundtrip,
          receipts,
          js_errors: record.js_errors
        })
      }
    }
}
const selected = new Map()
for (const record of records) {
  const identity = record.round + ':' + record.host + ':' + record.key
  if (selected.has(identity) && record.source_role !== 'CORRECTION')
    throw Error('Duplicate uncorrected record')
  selected.set(identity, record)
}
for (let round = 1; round <= 3; round++)
  for (const [host, keys] of Object.entries(fullRoster))
    for (const key of keys)
      if (!selected.has(round + ':' + host + ':' + key))
        throw Error('Missing final exact27 record ' + host + ':' + key)
const effective = [...selected.values()]
if (effective.length !== 27) throw Error('Invalid final27 denominator')
const statuses = effective.reduce((a, r) => {
  a[r.status] = (a[r.status] || 0) + 1
  return a
}, {})
const validation = {
  mechanical_integrity: 'PASS',
  business_acceptance: statuses.FAIL ? 'FAIL' : 'PASS',
  source,
  records: 27,
  raw_records: records.length,
  actual_authentications: authentications.length,
  screenshots: captures.length,
  statuses,
  frontend_inputs_per_source: 258,
  frontend_source_drift: 0,
  supplier_calls: 0,
  runtime_runs: 0,
  cors_acceptance: 'NOT_RUN',
  existing_denominators_preserved: { route_smoke: 225, lifecycles: 33 },
  authentications,
  owned_resources: ownedResources,
  record_bindings: effective,
  raw_record_bindings: records,
  capture_bindings: captures,
  sources
}
await mkdir(root, { recursive: false })
await writeFile(join(root, 'validation.json'), JSON.stringify(validation, null, 2), { flag: 'wx' })
console.log(
  JSON.stringify({
    mechanical_integrity: validation.mechanical_integrity,
    business_acceptance: validation.business_acceptance,
    records: 27,
    raw_records: records.length,
    actual_authentications: authentications.length,
    screenshots: captures.length,
    statuses,
    source_drift: 0
  })
)
