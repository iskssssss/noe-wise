import { catalogDecimalIdentity } from '../integration/decimalIdentity'
import type { RequestOptions } from '../utils/http'
import type { CapabilityType, SkillActivationMode } from './types'

export interface SkillRequirement {
  type: CapabilityType
  capabilityId: string
  versionId: string
}

export interface SkillDraftContent {
  id: string
  definitionId: string
  baseVersionId: string | null
  revision: number
  versionKey: string
  /** 标准包文本路径为原始 UTF-8 正文，其他路径保留 Base64 字节表示。 */
  files: Record<string, string>
  modelInvocable: boolean
  userInvocable: boolean
  activationMode: SkillActivationMode
  conflictGroup: string | null
  requiredCapabilities: SkillRequirement[]
  publishedVersionId: string | null
  /** 只读投影：精确历史已解析；普通工作草稿未解析时为null。 */
  requiresPlan: boolean | null
  updatedAt: string
}

export interface SkillValidationIssue {
  path: string | null
  line: number | null
  /** ERROR 阻止发布；WARNING / INFO 仅描述未支持的声明或未验证的环境。 */
  severity: 'ERROR' | 'WARNING' | 'INFO'
  code: string
  message: string
}

export interface SkillDraftValidation {
  /** 标准包与平台配置是否有效；不表示已验证任意 Agent 的运行环境。 */
  valid: boolean
  issues: SkillValidationIssue[]
}

export interface SkillDraftPublishResult {
  versionId: string
  definitionRevision: number
}

export interface SaveSkillDraftInput {
  draftId: string
  revision: number
  versionKey: string
  /** 与读取模型相同：文本正文在 API 边界编码，非文本文件传递原 Base64。 */
  files: Record<string, string>
  modelInvocable: boolean
  userInvocable: boolean
  activationMode: SkillActivationMode
  conflictGroup: string | null
  requiredCapabilities: SkillRequirement[]
}

interface SkillDraftWire {
  id: string
  definition_id: string
  base_version_id: string | null
  revision: number
  version_key: string
  /** HTTP 文件内容统一采用 Base64 保存原始字节。 */
  files: Record<string, string>
  model_invocable: boolean
  user_invocable: boolean
  activation_mode: SkillActivationMode
  conflict_group: string | null
  required_capabilities: Array<{ type: CapabilityType; capability_id: string; version_id: string }>
  published_version_id: string | null
  requires_plan: boolean | null
  updated_at: string
}

interface SkillDraftHttp {
  get<T>(path: string, opts?: RequestOptions): Promise<T>
  post<T>(path: string, body?: unknown, opts?: RequestOptions): Promise<T>
  put<T>(path: string, body?: unknown, opts?: RequestOptions): Promise<T>
}

/** 与 SkillPackageValidator 标准包的 UTF8/BASE64 分类一致，不以解码失败推断文件类型。 */
const textExtensions = new Set([
  'md',
  'txt',
  'json',
  'yaml',
  'yml',
  'csv',
  'tsv',
  'xml',
  'html',
  'css',
  'js',
  'mjs',
  'cjs',
  'ts',
  'py',
  'sh',
  'ps1',
  'java',
  'kt',
  'sql'
])

/** 文件类型只按领域声明的扩展名判定；路径合法性继续由原包校验边界处理。 */
export function isSkillTextFile(path: string): boolean {
  const dot = path.lastIndexOf('.')
  return dot >= 0 && textExtensions.has(path.slice(dot + 1).toLowerCase())
}

/** 严格解码 wire 字节，保留正文中的 BOM、换行和空白；非法 UTF-8 直接拒绝。 */
function decodeText(value: string): string {
  const binary = atob(value)
  const bytes = Uint8Array.from(binary, (character) => character.charCodeAt(0))
  return new TextDecoder('utf-8', { fatal: true, ignoreBOM: true }).decode(bytes)
}

/** 编码编辑器正文，不接受会被 TextEncoder 替换的孤立代理字符。 */
function encodeText(value: string): string {
  const bytes = new TextEncoder().encode(value)
  if (new TextDecoder('utf-8', { fatal: true, ignoreBOM: true }).decode(bytes) !== value) {
    throw new Error('技能文件正文包含非法 Unicode 字符')
  }
  let binary = ''
  // 文件可达 MiB 级；分块避免一次 spread 超过浏览器调用参数上限。
  for (let offset = 0; offset < bytes.length; offset += 0x8000) {
    binary += String.fromCharCode(...bytes.subarray(offset, offset + 0x8000))
  }
  return btoa(binary)
}

const mapContent = (value: SkillDraftWire): SkillDraftContent => ({
  id: catalogDecimalIdentity(value.id, 'id'),
  definitionId: catalogDecimalIdentity(value.definition_id, 'definition_id'),
  baseVersionId:
    value.base_version_id === null || value.base_version_id === undefined
      ? null
      : catalogDecimalIdentity(value.base_version_id, 'base_version_id'),
  revision: value.revision,
  versionKey: value.version_key ?? '',
  files: Object.fromEntries(
    Object.entries(value.files ?? {}).map(([path, content]) => [
      path,
      isSkillTextFile(path) ? decodeText(content) : content
    ])
  ),
  modelInvocable: value.model_invocable,
  userInvocable: value.user_invocable,
  activationMode: value.activation_mode,
  conflictGroup: value.conflict_group,
  requiredCapabilities: (value.required_capabilities ?? []).map((item) => ({
    type: item.type,
    capabilityId: item.capability_id,
    versionId: item.version_id
  })),
  publishedVersionId:
    value.published_version_id === null || value.published_version_id === undefined
      ? null
      : catalogDecimalIdentity(value.published_version_id, 'published_version_id'),
  requiresPlan: value.requires_plan,
  updatedAt: value.updated_at
})

const id = (value: string) => encodeURIComponent(catalogDecimalIdentity(value, 'id'))

export const skillDraftSavePayload = (input: SaveSkillDraftInput) => ({
  draft_id: input.draftId,
  version_key: input.versionKey,
  files: Object.fromEntries(
    Object.entries(input.files).map(([path, content]) => [
      path,
      isSkillTextFile(path) ? encodeText(content) : content
    ])
  ),
  model_invocable: input.modelInvocable,
  user_invocable: input.userInvocable,
  activation_mode: input.activationMode,
  conflict_group: input.conflictGroup,
  required_capabilities: input.requiredCapabilities.map((item) => ({
    type: item.type,
    capability_id: item.capabilityId
  }))
})

export function createSkillDraftApi(http: SkillDraftHttp, prefix: string) {
  const root = `${prefix}/skill-drafts`
  return {
    createWithReceipt: (
      requestId: string,
      payload: { capabilityKey?: string; name: string; description: string }
    ) =>
      http.post<SkillCreationReceipt>(
        `${root}/create-with-receipt`,
        {
          request_id: requestId,
          payload: {
            ...(payload.capabilityKey ? { capability_key: payload.capabilityKey } : {}),
            name: payload.name,
            description: payload.description
          }
        },
        { retryOnUnauthorized: false }
      ),
    creationReceipt: (requestId: string) =>
      http.get<SkillCreationReceipt | undefined>(
        `${root}/create-with-receipt/result?request_id=${encodeURIComponent(requestId)}`
      ),
    createSkill: (
      metadata: { capabilityKey: string; name: string; description: string },
      opts?: RequestOptions
    ) =>
      http
        .post<SkillDraftWire>(
          `${root}/new`,
          {
            capability_key: metadata.capabilityKey,
            name: metadata.name,
            description: metadata.description
          },
          opts
        )
        .then(mapContent),
    getActive: (definitionId: string, opts?: RequestOptions) =>
      http
        .get<SkillDraftWire | undefined>(`${root}?definitionId=${id(definitionId)}`, opts)
        .then((value) => (value ? mapContent(value) : undefined)),
    create: (definitionId: string, baseVersionId?: string | null, opts?: RequestOptions) =>
      http
        .post<SkillDraftWire>(
          root,
          {
            definition_id: definitionId,
            ...(baseVersionId ? { base_version_id: baseVersionId } : {})
          },
          opts
        )
        .then(mapContent),
    get: (draftId: string, opts?: RequestOptions) =>
      http.get<SkillDraftWire>(`${root}/detail?draftId=${id(draftId)}`, opts).then(mapContent),
    getVersion: (versionId: string, opts?: RequestOptions) =>
      http.get<SkillDraftWire>(`${root}/version?versionId=${id(versionId)}`, opts).then(mapContent),
    save: (input: SaveSkillDraftInput, opts?: RequestOptions) =>
      http
        .put<SkillDraftWire>(root, skillDraftSavePayload(input), {
          ...opts,
          headers: {
            ...Object.fromEntries(new Headers(opts?.headers).entries()),
            'If-Match': String(input.revision)
          }
        })
        .then(mapContent),
    importZip: (draftId: string, revision: number, archive: File, opts?: RequestOptions) => {
      const form = new FormData()
      form.append('draft_id', String(draftId))
      form.append('archive', archive, archive.name)
      return http
        .post<SkillDraftWire>(`${root}/import-zip`, form, {
          ...opts,
          headers: {
            ...Object.fromEntries(new Headers(opts?.headers).entries()),
            'If-Match': String(revision)
          }
        })
        .then(mapContent)
    },
    validate: (draftId: string, revision: number, opts?: RequestOptions) =>
      http
        .post<{ valid: boolean; issues?: SkillValidationIssue[] }>(
          `${root}/validate`,
          { draft_id: draftId, revision },
          opts
        )
        .then((value) => ({ valid: value.valid, issues: value.issues ?? [] })),
    validateContent: (input: SaveSkillDraftInput, opts?: RequestOptions) =>
      http
        .post<{ valid: boolean; issues?: SkillValidationIssue[] }>(
          `${root}/validate-content`,
          skillDraftSavePayload(input),
          {
            ...opts,
            headers: {
              ...Object.fromEntries(new Headers(opts?.headers).entries()),
              'If-Match': String(input.revision)
            }
          }
        )
        .then((value) => ({ valid: value.valid, issues: value.issues ?? [] })),
    publish: (
      draftId: string,
      revision: number,
      definitionRevision: number,
      opts?: RequestOptions
    ) =>
      http
        .post<{
          version_id: string
          definition_revision: number
          validation?: SkillDraftValidation | null
        }>(
          `${root}/publish`,
          {
            draft_id: draftId,
            revision,
            definition_revision: definitionRevision
          },
          opts
        )
        .then((value) => ({
          versionId: catalogDecimalIdentity(value.version_id, 'version_id'),
          definitionRevision: value.definition_revision,
          validation: value.validation ?? null
        }))
  }
}

export interface SkillCreationReceipt {
  request_id: string
  definition_id: string
  draft_id: string
  revision: number
}

export type SkillDraftApi = ReturnType<typeof createSkillDraftApi>
