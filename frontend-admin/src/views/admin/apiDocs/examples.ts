import type { ApiDocEndpoint } from './types'

/** 基地址仅接受 HTTP(S)，防止误把凭证、查询串或 shell 片段复制到请求中。 */
export function normalizeBaseUrl(value: string): string | null {
  try {
    const url = new URL(value.trim())
    if (
      !['http:', 'https:'].includes(url.protocol) ||
      url.username ||
      url.password ||
      url.search ||
      url.hash
    )
      return null
    return url.href.replace(/\/$/, '')
  } catch {
    return null
  }
}
function shellQuote(value: string): string {
  return `'${value.replace(/'/g, "'\\''")}'`
}

/** 生成 Bash/cURL 模板；凭证与资源使用占位值。 */
export function buildCurl(endpoint: ApiDocEndpoint, origin: string): string {
  const query = new URLSearchParams(
    Object.entries(endpoint.queryExample ?? {}).map(([key, value]) => [key, String(value)])
  )
  const lines = [
    `curl${endpoint.responseKind === 'sse' || endpoint.responseType.includes('SSE') ? ' -N' : ''} -X ${endpoint.method} ${shellQuote(`${origin}${endpoint.path}${query.size ? `?${query}` : ''}`)}`
  ]
  if (endpoint.auth === 'service') {
    lines.push(
      "  -H 'X-Api-Key: YOUR_SERVICE_PRINCIPAL_API_KEY'",
      "  -H 'X-Timestamp: UNIX_SECONDS'",
      "  -H 'X-Signature: COMPUTED_HMAC_HEX'"
    )
  } else if (endpoint.auth === 'management') {
    lines.push("  -H 'Authorization: Bearer YOUR_MANAGEMENT_ACCESS_TOKEN'")
  } else if (endpoint.auth === 'callback') {
    lines.push("  -H 'X-Timestamp: UNIX_SECONDS'", "  -H 'X-Signature: COMPUTED_CALLBACK_HMAC_HEX'")
  } else if (endpoint.auth === 'openapi') {
    lines.push(
      "  -H 'X-Api-Key: YOUR_OPENAPI_API_KEY'",
      "  -H 'X-Timestamp: UNIX_SECONDS'",
      "  -H 'X-Signature: COMPUTED_HMAC_HEX'"
    )
  } else {
    lines.push("  -H 'X-Channel-Token: YOUR_CHANNEL_TOKEN'")
  }
  for (const [name, value] of Object.entries(endpoint.requestHeaders ?? {}))
    lines.push(`  -H ${shellQuote(`${name}: ${value}`)}`)
  for (const parameter of endpoint.parameters ?? []) {
    if (
      parameter.location === 'header' &&
      parameter.required &&
      !['X-Timestamp', 'X-Signature'].includes(parameter.name)
    )
      lines.push(
        `  -H '${parameter.name}: ${parameter.name === 'Idempotency-Key' ? 'YOUR_REQUEST_KEY' : 'YOUR_' + parameter.name.replace(/^X-/, '').replace(/-/g, '_').toUpperCase()}'`
      )
  }
  if (endpoint.requestExample)
    lines.push(
      "  -H 'Content-Type: application/json'",
      `  --data-raw '${JSON.stringify(endpoint.requestExample, null, 2).replace(/'/g, "'\\''")}'`
    )
  if (endpoint.requestFile)
    lines.push(
      "  -H 'Content-Type: application/octet-stream'",
      `  --data-binary ${shellQuote(`@${endpoint.requestFile}`)}`
    )
  return lines.join(' \\\n')
}
