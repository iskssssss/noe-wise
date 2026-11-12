import type { CapabilityVersionSummary } from '@skillforge/shared/capabilities/catalog'
import { catalogDecimalIdentity } from '@skillforge/shared/integration/decimalIdentity'
import type { RequestOptions } from '@skillforge/shared/utils/http'

import type {
  CapabilityType,
  CapabilityRequirementOption
} from '@skillforge/shared/capabilities/types'
export type { CapabilityType } from '@skillforge/shared/capabilities/types'

/** 服务端 Capability Definition/Version 生命周期状态。 */
export type CapabilityStatus = 'DRAFT' | 'PUBLISHED' | 'ARCHIVED' | 'DEPRECATED'

/** 平台端展示的 Capability Definition。tenantId 为 null 才表示平台资源。 */
export interface CapabilityDefinition {
  id: string
  tenantId: string | null
  capabilityType: CapabilityType
  capabilityKey: string
  name: string
  description: string
  trustLevel: string
  status: CapabilityStatus
  /** Definition CAS revision；版本编辑与发布的 If-Match 值。 */
  revision: number
  defaultVersionId?: string | null
  createdAt?: string | null
  updatedAt?: string | null
}

/** 技能工作台目录只消费摘要，不读取版本descriptor。 */
export interface SkillCatalogItem {
  id: string
  capabilityKey: string
  name: string
  description: string | null
  status: CapabilityStatus
  defaultVersionId: string | null
  defaultVersionKey: string | null
}
interface SkillCatalogWire {
  id: string
  capability_key: string
  name: string
  description: string | null
  status: string
  default_version_id: string | null
  default_version_key: string | null
}

/** 平台端展示的 Capability Version。版本正文在发布后不可变。 */
export interface CapabilityVersion {
  id: string
  definitionId: string
  tenantId: string | null
  capabilityType: CapabilityType
  versionKey: string
  name: string
  description: string
  trustLevel: string
  descriptor: Record<string, unknown>
  contentHash: string
  status: CapabilityStatus
  createdAt?: string | null
  publishedAt?: string | null
}

export interface CapabilityCatalogError {
  code: string
  message: string
}

export interface CapabilityValidation {
  definitionId: string
  versionId: string
  definitionRevision: number
  valid: boolean
  errors: CapabilityCatalogError[]
}

export interface CreateCapabilityDefinitionInput {
  capabilityType: CapabilityType
  capabilityKey: string
  name: string
  description?: string
}

export interface CreateCapabilityVersionInput {
  versionKey: string
  name: string
  description?: string
  contentHash: string
  descriptor: Record<string, unknown>
}

export interface UpdateCapabilityVersionInput {
  name: string
  description?: string
  contentHash: string
  descriptor: Record<string, unknown>
}

export interface SkillPackageImportInput {
  versionKey: string
  modelInvocable: boolean
  userInvocable: boolean
  activationMode: 'ALWAYS' | 'ON_DEMAND'
  conflictGroup?: string | null
  requiredCapabilities?: Array<{ type: CapabilityType; capabilityId: string; versionId: string }>
  files?: Record<string, string>
}

export interface SkillFile {
  path: string
  mediaType: string
  encoding: 'UTF8' | 'BASE64'
  sha256: string
  size: number
}

export interface CapabilityDefinitionQuery {
  capabilityType?: CapabilityType
  status?: CapabilityStatus
}

/** 服务端 SNAKE_CASE wire DTO；页面模型不直接暴露 wire 字段。 */
export interface CapabilityDefinitionWire {
  id: string
  tenant_id: string | null
  capability_type: string
  capability_key: string
  name: string
  description: string
  trust_level: string
  status: string
  revision: number
  default_version_id?: string | null
  created_at?: string | null
  updated_at?: string | null
}

export interface CapabilityVersionWire {
  id: string
  definition_id: string
  tenant_id: string | null
  capability_type: string
  version_key: string
  name: string
  description: string
  trust_level: string
  descriptor: Record<string, unknown>
  content_hash: string
  status: string
  created_at?: string | null
  published_at?: string | null
}

export interface CapabilityValidationWire {
  definition_id: string
  version_id: string
  definition_revision: number
  valid: boolean
  errors?: Array<{ code: string; message: string }>
}

export interface CapabilityCatalogHttp {
  get: <T>(path: string, opts?: RequestOptions) => Promise<T>
  post: <T>(path: string, body?: unknown, opts?: RequestOptions) => Promise<T>
  put: <T>(path: string, body?: unknown, opts?: RequestOptions) => Promise<T>
}

const CAPABILITY_TYPES: ReadonlySet<string> = new Set([
  'SKILL',
  'TOOL',
  'WORKFLOW',
  'KNOWLEDGE_SOURCE'
])

const CAPABILITY_STATUSES: ReadonlySet<string> = new Set([
  'DRAFT',
  'PUBLISHED',
  'ARCHIVED',
  'DEPRECATED'
])

function capabilityType(value: string): CapabilityType {
  const normalized = String(value).trim().toUpperCase()
  if (!CAPABILITY_TYPES.has(normalized)) {
    throw new Error(`不支持的 Capability 类型: ${value}`)
  }
  return normalized as CapabilityType
}

function capabilityStatus(value: string): CapabilityStatus {
  const normalized = String(value).trim().toUpperCase()
  if (!CAPABILITY_STATUSES.has(normalized)) {
    throw new Error(`不支持的 Capability 状态: ${value}`)
  }
  return normalized as CapabilityStatus
}

function definitionFromWire(value: CapabilityDefinitionWire): CapabilityDefinition {
  return {
    id: catalogDecimalIdentity(value.id, 'id'),
    tenantId:
      value.tenant_id === null || value.tenant_id === undefined
        ? null
        : catalogDecimalIdentity(value.tenant_id, 'tenant_id'),
    capabilityType: capabilityType(value.capability_type),
    capabilityKey: value.capability_key,
    name: value.name,
    description: value.description ?? '',
    trustLevel: value.trust_level,
    status: capabilityStatus(value.status),
    revision: value.revision,
    defaultVersionId:
      value.default_version_id === null || value.default_version_id === undefined
        ? null
        : catalogDecimalIdentity(value.default_version_id, 'default_version_id'),
    createdAt: value.created_at,
    updatedAt: value.updated_at
  }
}

function versionFromWire(value: CapabilityVersionWire): CapabilityVersion {
  return {
    id: catalogDecimalIdentity(value.id, 'id'),
    definitionId: catalogDecimalIdentity(value.definition_id, 'definition_id'),
    tenantId:
      value.tenant_id === null || value.tenant_id === undefined
        ? null
        : catalogDecimalIdentity(value.tenant_id, 'tenant_id'),
    capabilityType: capabilityType(value.capability_type),
    versionKey: value.version_key,
    name: value.name,
    description: value.description ?? '',
    trustLevel: value.trust_level,
    descriptor: value.descriptor ?? {},
    contentHash: value.content_hash,
    status: capabilityStatus(value.status),
    createdAt: value.created_at,
    publishedAt: value.published_at
  }
}

function validationFromWire(value: CapabilityValidationWire): CapabilityValidation {
  return {
    definitionId: catalogDecimalIdentity(value.definition_id, 'definition_id'),
    versionId: catalogDecimalIdentity(value.version_id, 'version_id'),
    definitionRevision: value.definition_revision,
    valid: value.valid,
    errors: (value.errors ?? []).map((error) => ({ code: error.code, message: error.message }))
  }
}

function idPath(id: string): string {
  return encodeURIComponent(catalogDecimalIdentity(id, 'id'))
}

/**
 * 平台 Capability Catalog 类型化客户端。
 *
 * <p>所有公共 API 的 SNAKE_CASE wire 映射和 Definition revision If-Match
 * 都在此边界处理，页面只依赖 camelCase 的平台模型。
 */
export function createCapabilityCatalogApi(http: CapabilityCatalogHttp) {
  const importPayload = (input: SkillPackageImportInput) => ({
    version_key: input.versionKey,
    model_invocable: input.modelInvocable,
    user_invocable: input.userInvocable,
    activation_mode: input.activationMode,
    conflict_group: input.conflictGroup ?? null,
    required_capabilities: (input.requiredCapabilities ?? []).map((value) => ({
      type: value.type,
      capability_id: value.capabilityId,
      version_id: value.versionId
    })),
    ...(input.files ? { files: input.files } : {})
  })
  return {
    listVersionSummaries: (definitionId: string, opts?: RequestOptions) =>
      http
        .get<
          Array<{
            id: string
            version_key: string
            name: string
            description: string
            status: string
          }>
        >(
          '/admin/capabilities/versions/summaries?definition_id=' +
            encodeURIComponent(catalogDecimalIdentity(definitionId, 'definition_id')),
          opts
        )
        .then((items) =>
          items.map((item): CapabilityVersionSummary => ({
            id: catalogDecimalIdentity(item.id, 'id'),
            versionKey: item.version_key,
            name: item.name,
            description: item.description,
            status: capabilityStatus(item.status)
          }))
        ),
    listRequirementOptions: (type: CapabilityType, opts?: RequestOptions) =>
      http
        .get<
          Array<{
            definition_id: string
            name: string
            capability_key: string
            description: string | null
            version_id: string
            version_key: string
            skill_estimated_tokens: number | null
          }>
        >(
          '/admin/capabilities/requirements/options?capability_type=' + encodeURIComponent(type),
          opts
        )
        .then((items) =>
          items.map((item): CapabilityRequirementOption => ({
            id: catalogDecimalIdentity(item.definition_id, 'definition_id'),
            name: item.name,
            capabilityKey: item.capability_key,
            description: item.description ?? '',
            versionId: catalogDecimalIdentity(item.version_id, 'version_id'),
            versionKey: item.version_key,
            skillEstimatedTokens: item.skill_estimated_tokens
          }))
        ),
    listSkillCatalog: (opts?: RequestOptions) =>
      http.get<SkillCatalogWire[]>('/admin/capabilities/skills/catalog', opts).then((values) =>
        values.map((value): SkillCatalogItem => ({
          id: catalogDecimalIdentity(value.id, 'id'),
          capabilityKey: value.capability_key,
          name: value.name,
          description: value.description,
          status: capabilityStatus(value.status),
          defaultVersionId:
            value.default_version_id === null
              ? null
              : catalogDecimalIdentity(value.default_version_id, 'default_version_id'),
          defaultVersionKey: value.default_version_key
        }))
      ),
    listDefinitions: (params: CapabilityDefinitionQuery = {}, opts?: RequestOptions) => {
      const query = new URLSearchParams()
      if (params.capabilityType) query.set('capability_type', params.capabilityType)
      if (params.status) query.set('status', params.status)
      const suffix = query.toString() ? `?${query.toString()}` : ''
      return http
        .get<CapabilityDefinitionWire[]>(`/admin/capabilities/definitions${suffix}`, opts)
        .then((values) => values.map(definitionFromWire))
    },

    getDefinition: (definitionId: string, opts?: RequestOptions) =>
      http
        .get<CapabilityDefinitionWire>(
          `/admin/capabilities/definitions/detail?definitionId=${idPath(definitionId)}`,
          opts
        )
        .then(definitionFromWire),

    createDefinition: (input: CreateCapabilityDefinitionInput, opts?: RequestOptions) =>
      http
        .post<CapabilityDefinitionWire>(
          '/admin/capabilities/definitions',
          {
            capability_type: input.capabilityType,
            capability_key: input.capabilityKey,
            name: input.name,
            description: input.description ?? ''
          },
          opts
        )
        .then(definitionFromWire),

    listVersions: (definitionId: string, status?: CapabilityStatus, opts?: RequestOptions) => {
      const query = status ? `&status=${encodeURIComponent(status)}` : ''
      return http
        .get<CapabilityVersionWire[]>(
          `/admin/capabilities/definitions/versions?definitionId=${idPath(definitionId)}${query}`,
          opts
        )
        .then((values) => values.map(versionFromWire))
    },

    getVersion: (versionId: string, opts?: RequestOptions) =>
      http
        .get<CapabilityVersionWire>(
          `/admin/capabilities/versions/detail?versionId=${idPath(versionId)}`,
          opts
        )
        .then(versionFromWire),

    createDraftVersion: (definitionId: string, input: CreateCapabilityVersionInput) =>
      http
        .post<CapabilityVersionWire>('/admin/capabilities/definitions/versions', {
          definition_id: definitionId,
          payload: {
            version_key: input.versionKey,
            name: input.name,
            description: input.description ?? '',
            content_hash: input.contentHash,
            descriptor: input.descriptor
          }
        })
        .then(versionFromWire),

    updateDraftVersion: (
      definitionId: string,
      versionId: string,
      revision: number,
      input: UpdateCapabilityVersionInput
    ) =>
      http
        .put<CapabilityVersionWire>(
          '/admin/capabilities/definitions/versions/update',
          {
            definition_id: definitionId,
            version_id: versionId,
            payload: {
              name: input.name,
              description: input.description ?? '',
              content_hash: input.contentHash,
              descriptor: input.descriptor
            }
          },
          { headers: { 'If-Match': String(revision) } }
        )
        .then(versionFromWire),

    validateVersion: (definitionId: string, versionId: string) =>
      http
        .post<CapabilityValidationWire>('/admin/capabilities/definitions/versions/validate', {
          definition_id: definitionId,
          version_id: versionId
        })
        .then(validationFromWire),

    publishVersion: (definitionId: string, versionId: string, revision: number) =>
      http
        .post<CapabilityVersionWire>(
          '/admin/capabilities/definitions/versions/publish',
          { definition_id: definitionId, version_id: versionId },
          { headers: { 'If-Match': String(revision) } }
        )
        .then(versionFromWire),

    importSkillDirectory: (
      definitionId: string,
      input: SkillPackageImportInput,
      opts?: RequestOptions
    ) =>
      http
        .post<CapabilityVersionWire>(
          '/admin/capabilities/definitions/skill-versions/import-directory',
          { definition_id: definitionId, payload: importPayload(input) },
          opts
        )
        .then(versionFromWire),

    importSkillZip: (
      definitionId: string,
      input: SkillPackageImportInput,
      archive: File,
      opts?: RequestOptions
    ) => {
      const form = new FormData()
      form.append(
        'metadata',
        new Blob([JSON.stringify({ definition_id: definitionId, payload: importPayload(input) })], {
          type: 'application/json'
        })
      )
      form.append('archive', archive, archive.name)
      return http
        .post<CapabilityVersionWire>(
          '/admin/capabilities/definitions/skill-versions/import-zip',
          form,
          opts
        )
        .then(versionFromWire)
    },

    listSkillFiles: (versionId: string, opts?: RequestOptions) =>
      http.get<SkillFile[]>(
        `/admin/capabilities/skill-versions/files?versionId=${idPath(versionId)}`,
        opts
      ),

    getSkillFile: (versionId: string, path: string, opts?: RequestOptions) =>
      http.get<Blob>(
        `/admin/capabilities/skill-versions/file?versionId=${idPath(versionId)}&path=${encodeURIComponent(path)}`,
        { ...opts, responseType: 'blob' }
      ),

    downloadSkill: (versionId: string, opts?: RequestOptions) =>
      http.get<Blob>(`/admin/capabilities/skill-versions/download?versionId=${idPath(versionId)}`, {
        ...opts,
        responseType: 'blob'
      }),

    getSkillDefault: (definitionId: string, opts?: RequestOptions) =>
      http
        .get<CapabilityVersionWire>(
          `/admin/capabilities/definitions/skill-default?definitionId=${idPath(definitionId)}`,
          opts
        )
        .then(versionFromWire),

    setSkillDefault: (
      definitionId: string,
      versionId: string,
      revision: number,
      opts?: RequestOptions
    ) =>
      http
        .post<CapabilityDefinitionWire>(
          '/admin/capabilities/definitions/skill-default/execute',
          { definition_id: definitionId, version_id: versionId },
          { ...opts, headers: { 'If-Match': String(revision) } }
        )
        .then(definitionFromWire)
  }
}

export type CapabilityCatalogApi = ReturnType<typeof createCapabilityCatalogApi>
