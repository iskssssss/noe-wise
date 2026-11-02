/** 递归排序对象键，避免 JSON 字段顺序影响能力内容摘要。 */
export function canonicalJson(value: unknown): string {
  const normalize = (item: unknown): unknown => {
    if (Array.isArray(item)) return item.map(normalize)
    if (item && typeof item === 'object') {
      return Object.fromEntries(
        Object.entries(item as Record<string, unknown>)
          .sort(([left], [right]) => (left < right ? -1 : left > right ? 1 : 0))
          .map(([key, child]) => [key, normalize(child)])
      )
    }
    return item
  }
  return JSON.stringify(normalize(value))
}

/** 按能力目录约定计算内容摘要的前端预览值。 */
export async function capabilityContentHash(
  name: string,
  description: string,
  descriptor: Record<string, unknown>
): Promise<string> {
  const content = canonicalJson({ name, description, descriptor })
  const digest = await globalThis.crypto.subtle.digest('SHA-256', new TextEncoder().encode(content))
  return Array.from(new Uint8Array(digest), (byte) => byte.toString(16).padStart(2, '0')).join('')
}
