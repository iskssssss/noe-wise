/** Reconcile route consumers and visible help controls from complete existing evidence. */
import { readFile, writeFile, mkdir } from 'node:fs/promises'
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
if (relative(resolve('.runtime-logs'), root).startsWith('..'))
  throw Error('Private evidence required')
const data = JSON.parse(await readFile(source, 'utf8'))
if (
  data.mechanical_integrity !== 'PASS' ||
  data.records !== 225 ||
  data.business_acceptance !== 'NOT_RUN'
)
  throw Error('Require full strict original evidence')
const routes = new Map(),
  gaps = []
for (const binding of data.capture_bindings) {
  const bytes = await readFile(binding.result),
    capture = await readFile(binding.capture)
  const sha = (v) => createHash('sha256').update(v).digest('hex')
  if (sha(bytes) !== binding.result_sha256 || sha(capture) !== binding.capture_sha256)
    throw Error('Evidence changed ' + binding.result)
  const result = JSON.parse(bytes)
  const key = binding.host + ':' + binding.template
  if (!routes.has(key))
    routes.set(key, {
      host: binding.host,
      route: binding.template,
      component: result.component,
      business_acceptance: 'NOT_RUN',
      consumer_boundary: 'REAL_PASSWORD_AUTH_ROUTE_DOM_HTTP_SMOKE',
      rounds: [],
      form_help_controls: []
    })
  const route = routes.get(key)
  if (route.rounds.some((r) => r.round === binding.round)) throw Error('Duplicate round')
  route.rounds.push({
    round: binding.round,
    status: binding.status,
    help_status: binding.help_status,
    path: binding.path,
    fixture_id: binding.fixture_id,
    result: binding.result,
    capture: binding.capture,
    http_failures: (result.http || []).filter((h) => h.status >= 400),
    js_errors: result.js_errors || []
  })
  for (const control of result.dom?.help || [])
    if (!control.description_ok) {
      const record = {
        host: binding.host,
        route: binding.template,
        round: binding.round,
        capture: binding.capture,
        result: binding.result,
        control
      }
      gaps.push(record)
      route.form_help_controls.push(record)
    }
}
if (routes.size !== 75 || [...routes.values()].some((r) => r.rounds.length !== 3))
  throw Error('Expected exactly75 routes x3')
await mkdir(root, { recursive: false })
const inventory = {
  source,
  source_sha256: createHash('sha256')
    .update(await readFile(source))
    .digest('hex'),
  status: 'COMPLETE',
  route_count: 75,
  round_records: 225,
  screenshots: 225,
  actual_authentications: 6,
  route_statuses: data.statuses,
  business_acceptance: 'NOT_RUN',
  help_gap_records: gaps.length,
  adapter: 'Institution owned proxy maps Origin to localhost:3000; CORS acceptance NOT_RUN.',
  routes: [...routes.values()]
}
await writeFile(join(root, 'domain-inventory.json'), JSON.stringify(inventory, null, 2), {
  flag: 'wx'
})
await writeFile(join(root, 'help-controls.json'), JSON.stringify(gaps, null, 2), { flag: 'wx' })
console.log(
  JSON.stringify({
    status: inventory.status,
    routes: 75,
    round_records: 225,
    help_gap_controls: gaps.length
  })
)
