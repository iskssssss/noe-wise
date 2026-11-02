/** 记忆 v2 外部正文；仅 value 是开放业务内容，治理字段由宿主决定。 */
export interface MemoryContent {
  value: unknown
  conditions: string[]
  temporal: MemoryTemporal
  revisionProposal?: MemoryRevisionProposal
}

export interface MemoryTemporal {
  kind: 'stable' | 'task' | 'interval' | 'unknown'
  from: string | null
  to: string | null
}

export interface MemoryRevisionProposal {
  factId: string
  expectedRevision: number
}

/** 平台记忆提取调试与正式提取共用的 v2 输入协议。 */
export interface ExtractionInput {
  schemaVersion: 2
  userId: number
  sources: ExtractionSource[]
  existingFacts: ExtractionExistingFact[]
}

export interface ExtractionSource {
  id: string
  text: string
  occurredAt: string
}

export interface ExtractionExistingFact {
  id: string
  type: 'preference' | 'episode'
  scope: 'user' | 'user_agent'
  subject: string
  predicate: string
  object: MemoryContent
  revision: number
  validFrom: string
  validTo: string | null
}

/** UI 边界只识别协议形状；写入权限、时间语义与版本检查仍由服务端负责。 */
export function parseMemoryContent(canonicalJson: string): MemoryContent | undefined {
  try {
    const payload: unknown = JSON.parse(canonicalJson)
    if (
      !payload ||
      typeof payload !== 'object' ||
      !('value' in payload) ||
      !('conditions' in payload) ||
      !Array.isArray(payload.conditions) ||
      !payload.conditions.every(
        (condition): condition is string => typeof condition === 'string'
      ) ||
      !('temporal' in payload) ||
      !isMemoryTemporal(payload.temporal)
    )
      return
    const content: MemoryContent = {
      value: payload.value,
      conditions: payload.conditions,
      temporal: payload.temporal
    }
    if ('revisionProposal' in payload) {
      if (!isMemoryRevisionProposal(payload.revisionProposal)) return
      content.revisionProposal = payload.revisionProposal
    }
    return content
  } catch {
    return
  }
}

/** 批准提示仅序列化真实正文；原始编辑输入不经过此展示投影。 */
export function memoryContentText(content: MemoryContent): string {
  return JSON.stringify({
    value: content.value,
    conditions: content.conditions,
    temporal: content.temporal
  })
}

function isMemoryTemporal(value: unknown): value is MemoryTemporal {
  return (
    !!value &&
    typeof value === 'object' &&
    'kind' in value &&
    (value.kind === 'stable' ||
      value.kind === 'task' ||
      value.kind === 'interval' ||
      value.kind === 'unknown') &&
    'from' in value &&
    (typeof value.from === 'string' || value.from === null) &&
    'to' in value &&
    (typeof value.to === 'string' || value.to === null)
  )
}

function isMemoryRevisionProposal(value: unknown): value is MemoryRevisionProposal {
  return (
    !!value &&
    typeof value === 'object' &&
    'factId' in value &&
    typeof value.factId === 'string' &&
    !!value.factId.trim() &&
    'expectedRevision' in value &&
    typeof value.expectedRevision === 'number' &&
    Number.isInteger(value.expectedRevision) &&
    value.expectedRevision > 0
  )
}
