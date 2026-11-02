/** Owned platform identity: formal auth, real role catalogue, single create dispatch. */
import { readFile, writeFile, mkdir } from 'node:fs/promises'
import { resolve, join, relative } from 'node:path'
const args = Object.fromEntries(
  process.argv.slice(2).reduce((a, v, i, s) => {
    if (v.startsWith('--')) a.push([v.slice(2), s[i + 1]])
    return a
  }, [])
)
const root = resolve(args.root),
  envFile = resolve(args.env)
if (relative(resolve('.runtime-logs'), root).startsWith('..'))
  throw Error('Private evidence required')
await mkdir(root, { recursive: false })
const text = await readFile(envFile, 'utf8')
const env = Object.fromEntries(
  text.split(/\r?\n/).flatMap((x) => {
    const m = x.match(/^\s*([A-Z_0-9]+)=(.*)$/)
    return m ? [[m[1], m[2].trim().replace(/^(['"])(.*)\1$/, '$2')]] : []
  })
)
const state = JSON.parse(await readFile(resolve(args.environment), 'utf8'))
const origin = state.services['platform-service'].base_url
if (new URL(origin).hostname !== '127.0.0.1') throw Error('Loopback required')
const save = (name, value) =>
  writeFile(join(root, name), JSON.stringify(value, null, 2), { flag: 'wx' })
let token,
  seq = 0
async function api(method, path, body, credential = false) {
  const id = String(++seq).padStart(2, '0')
  await save(id + '-intent.json', {
    method,
    path,
    body: credential ? 'PRIVATE_CREDENTIAL_BODY' : body,
    at: new Date().toISOString()
  })
  const res = await fetch(origin + '/api/v1' + path, {
    method,
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { 'X-Platform-Token': 'Bearer ' + token } : {})
    },
    ...(body ? { body: JSON.stringify(body) } : {}),
    signal: AbortSignal.timeout(30000)
  })
  const b = await res.json()
  await save(id + '-receipt.json', {
    method,
    path,
    status: res.status,
    code: b.code,
    data: path.includes('/auth/')
      ? { sub: b.data?.sub, roles: b.data?.roles, mfa_state: b.data?.mfa_state }
      : b.data,
    at: new Date().toISOString()
  })
  if (!res.ok || b.code !== 'OK') throw Error(`${method} ${path} ${res.status} ${b.code}`)
  return b.data
}
try {
  const auth = await api(
    'POST',
    '/platform/auth/login',
    { username: env.SF_PLATFORM_USER, password: env.SF_PLATFORM_PASSWORD },
    true
  )
  if (auth.mfa_state !== 'full') throw Error('Full auth required')
  token = auth.access_token
  const roles = await api('GET', '/admin/foundation/access/roles')
  const role = roles.find((r) => r.code === 'platform_admin' && r.enabled)
  if (!role) throw Error('Actual platform admin role missing')
  const username = args.username
  if (!/^ui-life-platform-/.test(username)) throw Error('Owned username prefix required')
  const existing = await api(
    'GET',
    '/admin/foundation/access/members?keyword=' + encodeURIComponent(username) + '&page_size=100'
  )
  if (existing.items.some((r) => r.username === username))
    throw Error('Refuse duplicate actor create')
  const member = await api(
    'POST',
    '/admin/foundation/access/member',
    {
      username,
      display_name: '本批真实UI平台验收',
      email: username + '@example.test',
      password: env.SF_PLATFORM_PASSWORD,
      department_id: null,
      position_id: null,
      role_ids: [role.id]
    },
    true
  )
  token = undefined
  const proof = await api(
    'POST',
    '/platform/auth/login',
    { username, password: env.SF_PLATFORM_PASSWORD },
    true
  )
  token = proof.access_token
  const me = await api('GET', '/platform/auth/me')
  if (me.sub !== member.id || me.mfa_state !== 'full' || !me.roles.includes('platform_admin'))
    throw Error('Dedicated actor consumer auth failed')
  await writeFile(join(root, 'isolated-ui.env'), text + '\nSF_PLATFORM_USER=' + username + '\n', {
    flag: 'wx'
  })
  await save('manifest.json', {
    username,
    member_id: member.id,
    role_id: role.id,
    identity: me,
    cleanup:
      'Owned platform actor retained for this acceptance; disable after dependent UI lanes finish.',
    supplier_calls: 0
  })
  console.log(
    JSON.stringify({ status: 'PASS', member_id: member.id, permissions: me.permissions.length })
  )
} catch (e) {
  await save('failure.json', { error: e.message })
  process.exitCode = 1
  console.error(e.message)
}
