/** Strict complete three-round lifecycle collector; diagnostic failures remain visible. */
import { readFile, writeFile, mkdir, access } from 'node:fs/promises'
import { resolve, join, relative } from 'node:path'
import { createHash } from 'node:crypto'
const args = Object.fromEntries(
  process.argv.slice(2).reduce((a, v, i, s) => {
    if (v.startsWith('--')) a.push([v.slice(2), s[i + 1]])
    return a
  }, [])
)
const root = resolve(args.root),
  original = resolve(args.original),
  correction = resolve(args.correction)
if (relative(resolve('.runtime-logs'), root).startsWith('..'))
  throw Error('Private evidence required')
if (
  await access(root).then(
    () => true,
    () => false
  )
)
  throw Error('Refuse overwrite')
const sha = (bytes) => createHash('sha256').update(bytes).digest('hex')
const read = async (path) => JSON.parse(await readFile(path, 'utf8'))
const originalRoster = {
  admin: [
    'tool-draft',
    'skill-files',
    'agent-config',
    'workflow-conflict',
    'knowledge-config',
    'organization-member'
  ],
  platform: [
    'tool-draft',
    'skill-files',
    'application-policy',
    'agent-template',
    'organization-member'
  ]
}
const correctionRoster = {
  admin: ['tool-draft', 'knowledge-config', 'organization-member'],
  platform: ['tool-draft', 'organization-member']
}
const sources = [],
  authentications = [],
  records = [],
  captures = []
for (const [directory, roster, lane] of [
  [original, originalRoster, 'original'],
  [correction, correctionRoster, 'correction']
]) {
  const summary = await read(join(directory, 'summary.json')),
    services = await read(join(directory, 'services.json')),
    bindings = await read(join(directory, 'frontend-inputs.json'))
  if (summary.length !== Object.values(roster).flat().length * 3)
    throw Error('Incomplete ' + lane + ' denominator')
  for (const binding of bindings)
    if (sha(await readFile(resolve(binding.path))) !== binding.sha256)
      throw Error('Frontend source drift ' + binding.path)
  sources.push({
    directory,
    lane,
    summary_sha256: sha(await readFile(join(directory, 'summary.json'))),
    runner_sha256: sha(await readFile(join(directory, 'runner.source.mjs'))),
    frontend_inputs: bindings.length,
    frontend_input_drift: 0
  })
  for (let round = 1; round <= 3; round++)
    for (const [host, keys] of Object.entries(roster)) {
      if (services.filter((s) => s.round === round && s.host === host).length !== 1)
        throw Error('Missing exact service lane')
      const auth = await read(join(directory, 'round-' + round, host, 'login', 'result.json'))
      const me = auth.http.filter(
        (h) => h.path.endsWith('/auth/me') && h.status === 200 && h.code === 'OK'
      )
      if (
        auth.status !== 'PASS' ||
        auth.identity?.mfa_state !== 'full' ||
        !me.length ||
        !auth.identity.permissions?.length
      )
        throw Error('Incomplete actual auth')
      authentications.push({
        lane,
        round,
        host,
        sub: auth.identity.sub,
        mfa_state: 'full',
        permission_count: auth.identity.permissions.length,
        actual_me_200: true
      })
      const owned = await read(join(directory, 'round-' + round, host, 'owned-resources.json'))
      if (
        !owned.prefix.startsWith('ui-life-' + host + '-' + round + '-') ||
        owned.supplier_calls !== 0 ||
        owned.runtime_runs !== 0
      )
        throw Error('Invalid owned resource boundary')
      for (const key of keys) {
        const matching = summary.filter(
          (r) => r.round === round && r.host === host && r.key === key
        )
        if (matching.length !== 1) throw Error('Missing/duplicate flow ' + key)
        const rec = await read(join(directory, 'round-' + round, host, key, 'result.json'))
        if (rec.status !== matching[0].status || !['PASS', 'FAIL'].includes(rec.status))
          throw Error('Invalid status or disagreement')
        if (rec.blocked_writes.length || rec.http.some((h) => h.status === 429))
          throw Error('Guard refusal or rate limiting in complete lane')
        if (
          rec.status === 'PASS' &&
          (!rec.actions.some((a) => a.kind === 'UI_CLICK_SUBMIT' && a.actual_http_status < 300) ||
            !rec.readbacks.length)
        )
          throw Error('PASS without actual UI submit/readback')
        const formalReadbacks = []
        for (const binding of rec.readbacks) {
          const receiptPath = resolve(directory, 'round-' + round, host, binding.evidence)
          if (relative(resolve(directory, 'round-' + round, host), receiptPath).startsWith('..'))
            throw Error('API receipt escaped lane')
          const receipt = await read(receiptPath)
          if (
            receipt.path !== binding.path ||
            receipt.status !== binding.status ||
            receipt.code !== binding.code
          )
            throw Error('API receipt disagreement')
          if (
            receipt.kind === 'FORMAL_API_READBACK' &&
            receipt.method === 'GET' &&
            receipt.status === 200 &&
            receipt.code === 'OK'
          )
            formalReadbacks.push({
              path: receiptPath,
              sha256: sha(await readFile(receiptPath)),
              api_path: receipt.path
            })
        }
        if (rec.status === 'PASS' && !formalReadbacks.length)
          throw Error('PASS has no formal real API readback receipt')
        for (const name of rec.captures) {
          const path = join(directory, 'round-' + round, host, key, name),
            bytes = await readFile(path)
          if (
            bytes.subarray(0, 8).toString('hex') !== '89504e470d0a1a0a' ||
            bytes.readUInt32BE(16) < 1000 ||
            bytes.readUInt32BE(20) < 700
          )
            throw Error('Invalid screenshot')
          captures.push({
            lane,
            round,
            host,
            key,
            path,
            sha256: sha(bytes),
            width: bytes.readUInt32BE(16),
            height: bytes.readUInt32BE(20)
          })
        }
        records.push({
          lane,
          round,
          host,
          key,
          status: rec.status,
          reason: rec.reason || null,
          result: join(directory, 'round-' + round, host, key, 'result.json'),
          result_sha256: sha(
            await readFile(join(directory, 'round-' + round, host, key, 'result.json'))
          ),
          actual_ui_submits: rec.actions
            .filter((a) => a.kind === 'UI_CLICK_SUBMIT')
            .map((a) => ({
              button: a.button,
              path: a.path,
              method: a.method,
              status: a.actual_http_status,
              code: a.actual_code
            })),
          api_receipts: rec.readbacks,
          formal_readback_bindings: formalReadbacks,
          aria_disabled_mismatches: rec.actions.filter((a) => a.kind === 'ARIA_DISABLED_MISMATCH'),
          failed_save_persistence: rec.failed_save_persistence,
          optional_tool_title_empty_rejected:
            key === 'tool-draft' &&
            lane === 'original' &&
            rec.http.some(
              (h) =>
                h.method === 'POST' &&
                h.path.endsWith('/definitions/versions') &&
                h.status === 422 &&
                h.request_body?.payload?.descriptor?.title === ''
            )
        })
      }
    }
}
const effective = []
for (let round = 1; round <= 3; round++)
  for (const [host, keys] of Object.entries(originalRoster))
    for (const key of keys) {
      const selected = records.filter((r) => r.round === round && r.host === host && r.key === key)
      effective.push(selected.find((r) => r.lane === 'correction') || selected[0])
    }
const statuses = (rows) => rows.reduce((a, r) => ((a[r.status] = (a[r.status] || 0) + 1), a), {})
const titleFailures = records.filter((r) => r.optional_tool_title_empty_rejected)
const skillFailures = records.filter((r) => r.key === 'skill-files' && r.lane === 'original')
if (
  titleFailures.length !== 6 ||
  skillFailures.length !== 6 ||
  skillFailures.some(
    (r) =>
      r.status !== 'FAIL' ||
      r.failed_save_persistence?.revision_before !== r.failed_save_persistence?.revision_after ||
      Object.keys(r.failed_save_persistence?.files || {}).length !== 0
  )
)
  throw Error('Original consumer failure evidence incomplete')
const openConsumerFindings = [
  {
    code: 'TOOL_OPTIONAL_TITLE_DEFAULT_EMPTY_REJECTED',
    status: 'FAIL',
    actual_round_records: 6,
    trigger:
      'Normal optional tool title left at default empty string; shared UI always serializes title:""; backend optionalText rejects.',
    successful_filled_title_does_not_close_this_issue: true,
    evidence: titleFailures.map((r) => r.result)
  },
  {
    code: 'SKILL_FILE_WIRE_ENCODING_CONFLICT',
    status: 'FAIL',
    actual_round_records: 6,
    trigger: 'CodeMirror file text is serialized directly; backend requires Base64 strings.',
    failed_save_revision_and_empty_files_unchanged: true,
    evidence: skillFailures.map((r) => r.result)
  },
  {
    code: 'KB_ARIA_DISABLED_NATIVE_STATE_DIVERGENCE',
    status: 'FAIL',
    actual_control_records: records.flatMap((r) => r.aria_disabled_mismatches).length,
    trigger:
      '3 numeric controls declare aria-disabled=true while native disabled is absent; actual Tab keyboard input/save succeeds.',
    successful_keyboard_path_does_not_close_accessibility_issue: true,
    evidence: records.filter((r) => r.aria_disabled_mismatches.length).map((r) => r.result)
  }
]
const validation = {
  mechanical_integrity: 'PASS',
  effective_lifecycles: 33,
  effective_statuses: statuses(effective),
  original_statuses: statuses(records.filter((r) => r.lane === 'original')),
  correction_statuses: statuses(records.filter((r) => r.lane === 'correction')),
  actual_authentications: authentications.length,
  consumer_boundary: 'REAL_UI_FORM_CLICK_SUBMIT_WITH_FORMAL_API_READBACK',
  business_status: effective.some((r) => r.status === 'FAIL') ? 'FAIL' : 'PASS',
  sources,
  authentications,
  captures,
  records,
  effective,
  open_consumer_findings: openConsumerFindings,
  supplier_calls: 0,
  runtime_runs: 0,
  ui_unsupported: [
    'Agent clone UI',
    'Knowledge base archive UI',
    'Knowledge base restore UI',
    'Foundation member department filter UI (only keyword query exists)'
  ]
}
await mkdir(root, { recursive: false })
await writeFile(join(root, 'validation.json'), JSON.stringify(validation, null, 2), { flag: 'wx' })
console.log(
  JSON.stringify({
    mechanical_integrity: 'PASS',
    effective_lifecycles: 33,
    effective_statuses: validation.effective_statuses,
    actual_authentications: validation.actual_authentications,
    captures: captures.length,
    business_status: validation.business_status
  })
)
