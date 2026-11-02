/** 实际采用的知识依据标记，只允许本消息锚点，不授予私有材料读取权限。 */
export interface ChatCitationView {
  ordinal: number
  item_id: string
  source_id: string
  source_version: string
  label: string
  citation_id: string
}

/** 元数据只提取已声明的公开字段，附带材料路径和URL不会成为展示或链接。 */
export function chatCitations(value: unknown): ChatCitationView[] {
  if (!Array.isArray(value)) return []
  return value.flatMap((item: unknown) => {
    if (!item || typeof item !== 'object') return []
    const citation = item as Record<string, unknown>
    if (
      !Number.isSafeInteger(citation.ordinal) ||
      Number(citation.ordinal) < 1 ||
      typeof citation.citation_id !== 'string' ||
      !/^k[0-9a-f]{16}$/.test(citation.citation_id) ||
      !['item_id', 'source_id', 'source_version', 'label'].every(
        (key) => typeof citation[key] === 'string' && citation[key].length > 0
      )
    )
      return []
    return [
      {
        ordinal: Number(citation.ordinal),
        item_id: String(citation.item_id),
        source_id: String(citation.source_id),
        source_version: String(citation.source_version),
        label: String(citation.label),
        citation_id: citation.citation_id
      }
    ]
  })
}
