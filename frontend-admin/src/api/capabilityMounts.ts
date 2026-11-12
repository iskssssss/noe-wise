import type { CapabilityVersionSummary } from '@skillforge/shared/capabilities/catalog'
import { catalogDecimalIdentity } from '@skillforge/shared/integration/decimalIdentity'
import type { RequestOptions } from '@skillforge/shared/utils/http'

import type {
  CapabilityType,
  CapabilityRequirementOption
} from '@skillforge/shared/capabilities/types'
export type { CapabilityType } from '@skillforge/shared/capabilities/types'

/** Definition 和 Version 生命周期采用四值封闭联合类型。 */
export type CapabilityStatus = 'DRAFT' | 'PUBLISHED' | 'ARCHIVED' | 'DEPRECATED'

/** 机构Skill历史选择器只读取版本身份与标签。 */
export interface SkillVersionSummary {
  id: string
  versionKey: string
  status: CapabilityStatus
}

/** ToolDeployment 接受的 Provider 身份类型；TEST_MOCK 仅供测试使用。 */
export type ToolDeploymentProviderType = 'PLATFORM' | 'CONNECTOR' | 'MCP' | 'TEST_MOCK'
export type ToolDeploymentEnvironment = 'DEVELOPMENT' | 'TEST' | 'PRODUCTION'
export type ToolDeploymentStatus = 'ACTIVE' | 'DISABLED'

import type { SkillActivationMode } from '@skillforge/shared/capabilities/types'
export type { SkillActivationMode } from '@skillforge/shared/capabilities/types'
export type KnowledgeBindingMode = 'FOLLOW_ACTIVE'
export type CapabilityMountStatus = 'ACTIVE' | 'DISABLED'

export interface SkillMountConfig {
  activationMode: SkillActivationMode
  tokenBudget: number
}

export interface ToolMountConfig {
  toolDeploymentId: string
  toolDeploymentRevision?: number
}

export interface WorkflowMountOption {
  id: string
  instanceId: string
  workflowId: string
  installationRevision: string
  definitionId: string
}

export interface WorkflowMountConfig {
  workflowInstanceId: string
  workflowInstanceRevision?: number
}

export interface KnowledgeMountConfig {
  mode: KnowledgeBindingMode
  pinnedRevisionId: string | null
}

/** 类型化挂载响应；所选配置保证与 capabilityType 匹配。 */
export type CapabilityMount = {
  mountId: string
  capabilityType: CapabilityType
  capabilityDefinitionId: string
  capabilityVersionId: string
  priority: number
  revision: number
  status: CapabilityMountStatus
} & (
  | {
      capabilityType: 'SKILL'
      skillConfig: SkillMountConfig
      toolConfig: null
      workflowConfig: null
      knowledgeConfig: null
    }
  | {
      capabilityType: 'TOOL'
      skillConfig: null
      toolConfig: ToolMountConfig
      workflowConfig: null
      knowledgeConfig: null
    }
  | {
      capabilityType: 'WORKFLOW'
      skillConfig: null
      toolConfig: null
      workflowConfig: WorkflowMountConfig
      knowledgeConfig: null
    }
  | {
      capabilityType: 'KNOWLEDGE_SOURCE'
      skillConfig: null
      toolConfig: null
      workflowConfig: null
      knowledgeConfig: KnowledgeMountConfig
    }
)

export interface CapabilityMountSet {
  generation: number
  mounts: CapabilityMount[]
}

/** 类型化创建/更新命令；所选类型必须且只能提供一个配置。 */
export type CapabilityMountRequest = {
  capabilityType: CapabilityType
  capabilityDefinitionId: string
  capabilityVersionId?: string
  priority?: number
  status?: CapabilityMountStatus
} & (
  | {
      capabilityType: 'SKILL'
      skillConfig: { activationMode?: SkillActivationMode; tokenBudget: number }
      toolConfig?: never
      workflowConfig?: never
      knowledgeConfig?: never
    }
  | {
      capabilityType: 'TOOL'
      skillConfig?: never
      toolConfig: ToolMountConfig
      workflowConfig?: never
      knowledgeConfig?: never
    }
  | {
      capabilityType: 'WORKFLOW'
      skillConfig?: never
      toolConfig?: never
      workflowConfig: WorkflowMountConfig
      knowledgeConfig?: never
    }
  | {
      capabilityType: 'KNOWLEDGE_SOURCE'
      skillConfig?: never
      toolConfig?: never
      workflowConfig?: never
      knowledgeConfig: KnowledgeMountConfig
    }
)

export interface CapabilityDefinition {
  id: string
  tenantId: string | null
  capabilityType: CapabilityType
  capabilityKey: string
  name: string
  description: string
  trustLevel: string
  status: CapabilityStatus
  revision: number
  defaultVersionId?: string | null
  createdAt?: string | null
  updatedAt?: string | null
}

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

export interface CapabilityDeployment {
  id: string
  tenantId: string
  deploymentKey: string
  toolVersionId: string
  toolDefinitionId: string | null
  toolName: string | null
  toolVersionKey: string | null
  providerType: ToolDeploymentProviderType
  providerRef: string
  environment: ToolDeploymentEnvironment
  status: ToolDeploymentStatus
  revision: number
  createdAt?: string | null
  updatedAt?: string | null
}

export interface DeploymentStateReceipt {
  request_id: string
  status: 'SUCCEEDED' | 'REJECTED'
  deployment_key: string
  deployment_status: string | null
  revision: number | null
  error_code: string | null
  error_message: string | null
}

export interface DeploymentCreateReceipt {
  request_id: string
  status: 'SUCCEEDED' | 'REJECTED'
  deployment_id: string | null
  deployment_key: string
  error_code?: string | null
  message?: string | null
}

/** 唯一事实源 PlatformToolCatalog 的公开只读投影。 */
export interface PlatformTool {
  name: string
  description: string
  contentHash: string
  descriptor: Record<string, unknown>
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
  activationMode: SkillActivationMode
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

export interface CreateCapabilityDeploymentInput {
  deploymentKey?: string
  toolVersionId: string
  providerType: ToolDeploymentProviderType
  providerRef: string
  environment: ToolDeploymentEnvironment
  status?: ToolDeploymentStatus
}

type SkillMountConfigWire = {
  activation_mode: SkillActivationMode
  token_budget: number
}
type ToolMountConfigWire = {
  tool_deployment_id: string
  tool_deployment_revision: number
}
type WorkflowMountConfigWire = {
  workflow_instance_id: string
  workflow_instance_revision: number
}
type KnowledgeMountConfigWire = {
  mode: KnowledgeBindingMode
  pinned_revision_id: string | null
}

export type CapabilityMountWire = {
  mount_id: string
  capability_type: string
  capability_definition_id: string
  capability_version_id: string
  skill_config?: SkillMountConfigWire | null
  tool_config?: ToolMountConfigWire | null
  workflow_config?: WorkflowMountConfigWire | null
  knowledge_config?: KnowledgeMountConfigWire | null
  priority: number
  revision: number
  status: string
}

export type CapabilityMountSetWire = {
  generation: number
  mounts: CapabilityMountWire[]
}

export type CapabilityDefinitionWire = {
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

export type CapabilityVersionWire = {
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

export type CapabilityDeploymentWire = {
  id: string
  tenant_id: string
  deployment_key: string
  tool_version_id: string
  definition_id: string | null
  tool_name: string | null
  tool_version_key: string | null
  provider_type: string
  provider_ref: string
  environment: string
  status: string
  revision: number
  created_at?: string | null
  updated_at?: string | null
}

export type PlatformToolWire = {
  name: string
  description?: string | null
  content_hash: string
  descriptor: Record<string, unknown>
}

export type CapabilityValidationWire = {
  definition_id: string
  version_id: string
  definition_revision: number
  valid: boolean
  errors?: Array<{ code: string; message: string }>
}

export type CapabilityMountHttp = {
  get: <T>(path: string, opts?: RequestOptions) => Promise<T>
  post: <T>(path: string, body?: unknown, opts?: RequestOptions) => Promise<T>
  put: <T>(path: string, body?: unknown, opts?: RequestOptions) => Promise<T>
  del: <T>(path: string, body?: unknown, opts?: RequestOptions) => Promise<T>
}

const CAPABILITY_TYPES: readonly CapabilityType[] = [
  'SKILL',
  'TOOL',
  'WORKFLOW',
  'KNOWLEDGE_SOURCE'
]
const CAPABILITY_STATUSES: readonly CapabilityStatus[] = [
  'DRAFT',
  'PUBLISHED',
  'ARCHIVED',
  'DEPRECATED'
]
const MOUNT_STATUSES: readonly CapabilityMountStatus[] = ['ACTIVE', 'DISABLED']
const DEPLOYMENT_PROVIDER_TYPES: readonly ToolDeploymentProviderType[] = [
  'PLATFORM',
  'CONNECTOR',
  'MCP',
  'TEST_MOCK'
]
const DEPLOYMENT_ENVIRONMENTS: readonly ToolDeploymentEnvironment[] = [
  'DEVELOPMENT',
  'TEST',
  'PRODUCTION'
]
const SKILL_MODES: readonly SkillActivationMode[] = ['ALWAYS', 'ON_DEMAND']
const KNOWLEDGE_MODES: readonly KnowledgeBindingMode[] = ['FOLLOW_ACTIVE']

function fail(message: string): never {
  throw new Error(`CapabilityMount wire contract violation: ${message}`)
}

function positiveNumber(value: unknown, field: string): number {
  if (typeof value !== 'number' || !Number.isSafeInteger(value) || value <= 0) {
    return fail(`${field} must be a positive integer`)
  }
  return value
}

function enumValue<T extends string>(value: unknown, values: readonly T[], field: string): T {
  if (typeof value !== 'string' || !values.includes(value as T)) return fail(`${field} is invalid`)
  return value as T
}

function textValue(value: unknown, field: string): string {
  if (typeof value !== 'string' || value.trim() === '') return fail(`${field} is required`)
  return value
}

function objectValue(value: unknown, field: string): Record<string, unknown> {
  if (!value || typeof value !== 'object' || Array.isArray(value)) {
    return fail(`${field} must be an object`)
  }
  return value as Record<string, unknown>
}

function ifMatchOptions(opts: RequestOptions | undefined, revision: number): RequestOptions {
  const headers = new Headers(opts?.headers)
  headers.set('If-Match', String(positiveNumber(revision, 'revision')))
  return { ...opts, headers }
}

function nullableString(value: unknown, field: string): string | null {
  if (value !== null && typeof value !== 'string') return fail(`${field} must be a string or null`)
  return value
}

function oneConfig(
  mount: CapabilityMountWire
): ['skill' | 'tool' | 'workflow' | 'knowledge', unknown] {
  const configs: Array<['skill' | 'tool' | 'workflow' | 'knowledge', unknown]> = [
    ['skill', mount.skill_config],
    ['tool', mount.tool_config],
    ['workflow', mount.workflow_config],
    ['knowledge', mount.knowledge_config]
  ]
  const selected = configs.filter(([, config]) => config !== null && config !== undefined)
  if (selected.length !== 1) return fail('exactly one typed config is required')
  return selected[0]
}

function mapMount(wire: CapabilityMountWire): CapabilityMount {
  const type = enumValue(wire.capability_type, CAPABILITY_TYPES, 'capability_type')
  const status = enumValue(wire.status, MOUNT_STATUSES, 'status')
  const base = {
    mountId:
      typeof wire.mount_id === 'string' && wire.mount_id
        ? wire.mount_id
        : fail('mount_id is required'),
    capabilityType: type,
    capabilityDefinitionId: catalogDecimalIdentity(
      wire.capability_definition_id,
      'capability_definition_id'
    ),
    capabilityVersionId: catalogDecimalIdentity(
      wire.capability_version_id,
      'capability_version_id'
    ),
    priority:
      typeof wire.priority === 'number' && Number.isSafeInteger(wire.priority)
        ? wire.priority
        : fail('priority must be an integer'),
    revision: positiveNumber(wire.revision, 'revision'),
    status
  }
  const [configType, config] = oneConfig(wire)
  if (type === 'SKILL') {
    if (configType !== 'skill' || !config || typeof config !== 'object')
      return fail('SKILL config does not match capability_type')
    const value = config as SkillMountConfigWire
    return {
      ...base,
      capabilityType: 'SKILL',
      skillConfig: {
        activationMode: enumValue(
          value.activation_mode,
          SKILL_MODES,
          'skill_config.activation_mode'
        ),
        tokenBudget: positiveNumber(value.token_budget, 'skill_config.token_budget')
      },
      toolConfig: null,
      workflowConfig: null,
      knowledgeConfig: null
    }
  }
  if (type === 'TOOL') {
    if (configType !== 'tool' || !config || typeof config !== 'object')
      return fail('TOOL config does not match capability_type')
    const value = config as ToolMountConfigWire
    return {
      ...base,
      capabilityType: 'TOOL',
      skillConfig: null,
      toolConfig: {
        toolDeploymentId:
          typeof value.tool_deployment_id === 'string' && value.tool_deployment_id
            ? value.tool_deployment_id
            : fail('tool_config.tool_deployment_id is required'),
        toolDeploymentRevision: positiveNumber(
          value.tool_deployment_revision,
          'tool_config.tool_deployment_revision'
        )
      },
      workflowConfig: null,
      knowledgeConfig: null
    }
  }
  if (type === 'WORKFLOW') {
    if (configType !== 'workflow' || !config || typeof config !== 'object')
      return fail('WORKFLOW config does not match capability_type')
    const value = config as WorkflowMountConfigWire
    return {
      ...base,
      capabilityType: 'WORKFLOW',
      skillConfig: null,
      toolConfig: null,
      workflowConfig: {
        workflowInstanceId:
          typeof value.workflow_instance_id === 'string' && value.workflow_instance_id
            ? value.workflow_instance_id
            : fail('workflow_config.workflow_instance_id is required'),
        workflowInstanceRevision: positiveNumber(
          value.workflow_instance_revision,
          'workflow_config.workflow_instance_revision'
        )
      },
      knowledgeConfig: null
    }
  }
  if (configType !== 'knowledge' || !config || typeof config !== 'object')
    return fail('KNOWLEDGE_SOURCE config does not match capability_type')
  const value = config as KnowledgeMountConfigWire
  const mode = enumValue(value.mode, KNOWLEDGE_MODES, 'knowledge_config.mode')
  const pinnedRevisionId = nullableString(
    value.pinned_revision_id,
    'knowledge_config.pinned_revision_id'
  )
  if (mode === 'FOLLOW_ACTIVE' && pinnedRevisionId !== null)
    return fail('FOLLOW_ACTIVE must not pin a revision')
  return {
    ...base,
    capabilityType: 'KNOWLEDGE_SOURCE',
    skillConfig: null,
    toolConfig: null,
    workflowConfig: null,
    knowledgeConfig: { mode, pinnedRevisionId }
  }
}

export function mapCapabilityMountSet(wire: CapabilityMountSetWire): CapabilityMountSet {
  if (!wire || typeof wire !== 'object') return fail('mount set must be an object')
  return {
    generation: positiveNumber(wire.generation, 'generation'),
    mounts: Array.isArray(wire.mounts) ? wire.mounts.map(mapMount) : fail('mounts must be an array')
  }
}

function mapDefinition(wire: CapabilityDefinitionWire): CapabilityDefinition {
  if (!wire || typeof wire !== 'object') return fail('definition must be an object')
  return {
    id: catalogDecimalIdentity(wire.id, 'id'),
    tenantId: wire.tenant_id === null ? null : catalogDecimalIdentity(wire.tenant_id, 'tenant_id'),
    capabilityType: enumValue(wire.capability_type, CAPABILITY_TYPES, 'capability_type'),
    capabilityKey: textValue(wire.capability_key, 'capability_key'),
    name: textValue(wire.name, 'name'),
    description:
      typeof wire.description === 'string'
        ? wire.description
        : fail('description must be a string'),
    trustLevel: textValue(wire.trust_level, 'trust_level'),
    status: enumValue(wire.status, CAPABILITY_STATUSES, 'status'),
    revision: positiveNumber(wire.revision, 'revision'),
    defaultVersionId:
      wire.default_version_id === null || wire.default_version_id === undefined
        ? null
        : catalogDecimalIdentity(wire.default_version_id, 'default_version_id'),
    createdAt: wire.created_at,
    updatedAt: wire.updated_at
  }
}

function mapVersion(wire: CapabilityVersionWire): CapabilityVersion {
  if (!wire || typeof wire !== 'object') return fail('version must be an object')
  return {
    id: catalogDecimalIdentity(wire.id, 'id'),
    definitionId: catalogDecimalIdentity(wire.definition_id, 'definition_id'),
    tenantId: wire.tenant_id === null ? null : catalogDecimalIdentity(wire.tenant_id, 'tenant_id'),
    capabilityType: enumValue(wire.capability_type, CAPABILITY_TYPES, 'capability_type'),
    versionKey: textValue(wire.version_key, 'version_key'),
    name: textValue(wire.name, 'name'),
    description:
      typeof wire.description === 'string'
        ? wire.description
        : fail('description must be a string'),
    trustLevel: textValue(wire.trust_level, 'trust_level'),
    descriptor: objectValue(wire.descriptor, 'descriptor'),
    contentHash: textValue(wire.content_hash, 'content_hash'),
    status: enumValue(wire.status, CAPABILITY_STATUSES, 'status'),
    createdAt: wire.created_at,
    publishedAt: wire.published_at
  }
}

function mapDeployment(wire: CapabilityDeploymentWire): CapabilityDeployment {
  if (!wire || typeof wire !== 'object') return fail('deployment must be an object')
  return {
    id: catalogDecimalIdentity(wire.id, 'id'),
    tenantId: catalogDecimalIdentity(wire.tenant_id, 'tenant_id'),
    deploymentKey: textValue(wire.deployment_key, 'deployment_key'),
    toolVersionId: catalogDecimalIdentity(wire.tool_version_id, 'tool_version_id'),
    toolDefinitionId:
      wire.definition_id === null
        ? null
        : catalogDecimalIdentity(wire.definition_id, 'definition_id'),
    toolName: nullableString(wire.tool_name, 'tool_name'),
    toolVersionKey: nullableString(wire.tool_version_key, 'tool_version_key'),
    providerType: enumValue(wire.provider_type, DEPLOYMENT_PROVIDER_TYPES, 'provider_type'),
    providerRef: textValue(wire.provider_ref, 'provider_ref'),
    environment: enumValue(wire.environment, DEPLOYMENT_ENVIRONMENTS, 'environment'),
    status: enumValue(wire.status, MOUNT_STATUSES, 'status'),
    revision: positiveNumber(wire.revision, 'revision'),
    createdAt: wire.created_at,
    updatedAt: wire.updated_at
  }
}

function mapPlatformTool(wire: PlatformToolWire): PlatformTool {
  if (!wire || typeof wire !== 'object') return fail('platform tool must be an object')
  return {
    name: textValue(wire.name, 'name'),
    description:
      wire.description === null || wire.description === undefined
        ? ''
        : textValue(wire.description, 'description'),
    contentHash: textValue(wire.content_hash, 'content_hash'),
    descriptor: objectValue(wire.descriptor, 'descriptor')
  }
}

function mapValidation(wire: CapabilityValidationWire): CapabilityValidation {
  if (!wire || typeof wire !== 'object') return fail('validation must be an object')
  if (typeof wire.valid !== 'boolean') return fail('valid must be a boolean')
  const errors = wire.errors ?? []
  if (!Array.isArray(errors)) return fail('errors must be an array')
  return {
    definitionId: catalogDecimalIdentity(wire.definition_id, 'definition_id'),
    versionId: catalogDecimalIdentity(wire.version_id, 'version_id'),
    definitionRevision: positiveNumber(wire.definition_revision, 'definition_revision'),
    valid: wire.valid,
    errors: errors.map((entry, index) => ({
      code: textValue(entry?.code, `errors[${index}].code`),
      message: textValue(entry?.message, `errors[${index}].message`)
    }))
  }
}

function requestToWire(request: CapabilityMountRequest): Record<string, unknown> {
  if (request.priority !== undefined && !Number.isSafeInteger(request.priority)) {
    return fail('priority must be an integer')
  }
  if (request.status !== undefined && !MOUNT_STATUSES.includes(request.status)) {
    return fail('status is invalid')
  }
  const base = {
    capability_type: request.capabilityType,
    capability_definition_id: catalogDecimalIdentity(
      request.capabilityDefinitionId,
      'capabilityDefinitionId'
    ),
    ...(request.capabilityVersionId === undefined
      ? {}
      : {
          capability_version_id: catalogDecimalIdentity(
            request.capabilityVersionId,
            'capabilityVersionId'
          )
        }),
    ...(request.priority === undefined ? {} : { priority: request.priority }),
    ...(request.status === undefined ? {} : { status: request.status })
  }
  if (request.capabilityType === 'SKILL') {
    if (!request.skillConfig || typeof request.skillConfig !== 'object') {
      return fail('SKILL config is required')
    }
    if (
      request.skillConfig.activationMode !== undefined &&
      request.skillConfig.activationMode !== 'ALWAYS' &&
      request.skillConfig.activationMode !== 'ON_DEMAND'
    )
      return fail('SKILL activationMode is invalid')
    return {
      ...base,
      skill_config: {
        ...(request.skillConfig.activationMode === undefined
          ? {}
          : { activation_mode: request.skillConfig.activationMode }),
        token_budget: positiveNumber(request.skillConfig.tokenBudget, 'skillConfig.tokenBudget')
      }
    }
  }
  if (request.capabilityType === 'TOOL') {
    if (!request.toolConfig || typeof request.toolConfig !== 'object') {
      return fail('TOOL config is required')
    }
    return {
      ...base,
      tool_config: {
        tool_deployment_id:
          typeof request.toolConfig.toolDeploymentId === 'string' &&
          request.toolConfig.toolDeploymentId
            ? request.toolConfig.toolDeploymentId
            : fail('toolConfig.toolDeploymentId is required'),
        ...(request.toolConfig.toolDeploymentRevision === undefined
          ? {}
          : {
              tool_deployment_revision: positiveNumber(
                request.toolConfig.toolDeploymentRevision,
                'toolConfig.toolDeploymentRevision'
              )
            })
      }
    }
  }
  if (request.capabilityType === 'WORKFLOW') {
    if (!request.workflowConfig || typeof request.workflowConfig !== 'object') {
      return fail('WORKFLOW config is required')
    }
    return {
      ...base,
      workflow_config: {
        workflow_instance_id:
          typeof request.workflowConfig.workflowInstanceId === 'string' &&
          request.workflowConfig.workflowInstanceId
            ? request.workflowConfig.workflowInstanceId
            : fail('workflowConfig.workflowInstanceId is required'),
        ...(request.workflowConfig.workflowInstanceRevision === undefined
          ? {}
          : {
              workflow_instance_revision: positiveNumber(
                request.workflowConfig.workflowInstanceRevision,
                'workflowConfig.workflowInstanceRevision'
              )
            })
      }
    }
  }
  if (!request.knowledgeConfig || typeof request.knowledgeConfig !== 'object') {
    return fail('KNOWLEDGE_SOURCE config is required')
  }
  const { mode, pinnedRevisionId } = request.knowledgeConfig
  if (!KNOWLEDGE_MODES.includes(mode)) return fail('KNOWLEDGE_SOURCE mode is invalid')
  if (mode === 'FOLLOW_ACTIVE' && pinnedRevisionId !== null)
    return fail('FOLLOW_ACTIVE must not pin a revision')
  return { ...base, knowledge_config: { mode, pinned_revision_id: pinnedRevisionId } }
}

export function createCapabilityMountApi(http: CapabilityMountHttp) {
  return {
    workflowOptions: () =>
      http
        .get<
          Array<{
            id: string
            instance_id: string
            workflow_id: string
            installation_revision: string
            definition_id: string
          }>
        >('/agents/capability-mounts/workflow-options')
        .then((items) => {
          if (!Array.isArray(items)) return fail('workflow options must be an array')
          return items.map((item): WorkflowMountOption => ({
            id: catalogDecimalIdentity(item.id, 'id'),
            instanceId: textValue(item.instance_id, 'instance_id'),
            workflowId: textValue(item.workflow_id, 'workflow_id'),
            installationRevision: catalogDecimalIdentity(
              item.installation_revision,
              'installation_revision'
            ),
            definitionId: catalogDecimalIdentity(item.definition_id, 'definition_id')
          }))
        }),
    list: (agentId: string) =>
      http
        .get<CapabilityMountSetWire>(
          `/agents/capability-mounts?agentId=${encodeURIComponent(agentId)}`
        )
        .then(mapCapabilityMountSet),
    create: (agentId: string, request: CapabilityMountRequest) =>
      http
        .post<CapabilityMountSetWire>(`/agents/capability-mounts`, {
          agent_id: agentId,
          payload: requestToWire(request)
        })
        .then(mapCapabilityMountSet),
    update: (
      agentId: string,
      mountId: string,
      expectedRevision: number,
      request: CapabilityMountRequest
    ) =>
      http
        .put<CapabilityMountSetWire>(
          `/agents/capability-mounts/update`,
          { agent_id: agentId, mount_id: mountId, payload: requestToWire(request) },
          { headers: { 'If-Match': String(positiveNumber(expectedRevision, 'expectedRevision')) } }
        )
        .then(mapCapabilityMountSet),
    delete: (agentId: string, mountId: string, expectedRevision: number) =>
      http
        .del<CapabilityMountSetWire>(
          `/agents/capability-mounts/delete`,
          { agent_id: agentId, mount_id: mountId },
          { headers: { 'If-Match': String(positiveNumber(expectedRevision, 'expectedRevision')) } }
        )
        .then(mapCapabilityMountSet)
  }
}

export function createCapabilityCatalogApi(http: CapabilityMountHttp) {
  const encodedId = (id: string, field: string): string =>
    encodeURIComponent(String(catalogDecimalIdentity(id, field)))

  const listDefinitions = (params: CapabilityDefinitionQuery = {}, opts?: RequestOptions) => {
    const query = new URLSearchParams()
    if (params.capabilityType) {
      query.set(
        'capability_type',
        enumValue(params.capabilityType, CAPABILITY_TYPES, 'capabilityType')
      )
    }
    if (params.status) query.set('status', enumValue(params.status, CAPABILITY_STATUSES, 'status'))
    const suffix = query.toString() ? `?${query}` : ''
    return http
      .get<CapabilityDefinitionWire[]>(`/capabilities/definitions${suffix}`, opts)
      .then((items) => {
        if (!Array.isArray(items)) return fail('definitions must be an array')
        return items.map(mapDefinition)
      })
  }

  const getDefinition = (definitionId: string, opts?: RequestOptions) =>
    http
      .get<CapabilityDefinitionWire>(
        `/capabilities/definitions/detail?definitionId=${encodedId(definitionId, 'definitionId')}`,
        opts
      )
      .then(mapDefinition)

  const createDefinition = (input: CreateCapabilityDefinitionInput, opts?: RequestOptions) => {
    const capabilityType = enumValue(input.capabilityType, CAPABILITY_TYPES, 'capabilityType')
    return http
      .post<CapabilityDefinitionWire>(
        '/capabilities/definitions',
        {
          capability_type: capabilityType,
          capability_key: textValue(input.capabilityKey, 'capabilityKey'),
          name: textValue(input.name, 'name'),
          description: input.description ?? ''
        },
        opts
      )
      .then(mapDefinition)
  }

  const listVersions = (definitionId: string, status?: CapabilityStatus, opts?: RequestOptions) => {
    const query = status
      ? `?status=${encodeURIComponent(enumValue(status, CAPABILITY_STATUSES, 'status'))}`
      : ''
    return http
      .get<CapabilityVersionWire[]>(
        `/capabilities/definitions/versions?definitionId=${encodedId(definitionId, 'definitionId')}${query.replace('?', '&')}`,
        opts
      )
      .then((items) => {
        if (!Array.isArray(items)) return fail('versions must be an array')
        return items.map(mapVersion)
      })
  }

  const getVersion = (versionId: string, opts?: RequestOptions) =>
    http
      .get<CapabilityVersionWire>(
        `/capabilities/versions/detail?versionId=${encodedId(versionId, 'versionId')}`,
        opts
      )
      .then(mapVersion)

  const createDraftVersion = (
    definitionId: string,
    input: CreateCapabilityVersionInput,
    opts?: RequestOptions
  ) =>
    http
      .post<CapabilityVersionWire>(
        `/capabilities/definitions/versions`,
        {
          definition_id: catalogDecimalIdentity(definitionId, 'definitionId'),
          payload: {
            version_key: textValue(input.versionKey, 'versionKey'),
            name: textValue(input.name, 'name'),
            description: input.description ?? '',
            content_hash: textValue(input.contentHash, 'contentHash'),
            descriptor: objectValue(input.descriptor, 'descriptor')
          }
        },
        opts
      )
      .then(mapVersion)

  const updateDraftVersion = (
    definitionId: string,
    versionId: string,
    revision: number,
    input: UpdateCapabilityVersionInput,
    opts?: RequestOptions
  ) =>
    http
      .put<CapabilityVersionWire>(
        `/capabilities/definitions/versions/update`,
        {
          definition_id: catalogDecimalIdentity(definitionId, 'definitionId'),
          version_id: catalogDecimalIdentity(versionId, 'versionId'),
          payload: {
            name: textValue(input.name, 'name'),
            description: input.description ?? '',
            content_hash: textValue(input.contentHash, 'contentHash'),
            descriptor: objectValue(input.descriptor, 'descriptor')
          }
        },
        ifMatchOptions(opts, revision)
      )
      .then(mapVersion)

  const validateVersion = (definitionId: string, versionId: string, opts?: RequestOptions) =>
    http
      .post<CapabilityValidationWire>(
        `/capabilities/definitions/versions/validate`,
        {
          definition_id: catalogDecimalIdentity(definitionId, 'definitionId'),
          version_id: catalogDecimalIdentity(versionId, 'versionId')
        },
        opts
      )
      .then(mapValidation)

  const publishVersion = (
    definitionId: string,
    versionId: string,
    revision: number,
    opts?: RequestOptions
  ) =>
    http
      .post<CapabilityVersionWire>(
        `/capabilities/definitions/versions/publish`,
        {
          definition_id: catalogDecimalIdentity(definitionId, 'definitionId'),
          version_id: catalogDecimalIdentity(versionId, 'versionId')
        },
        ifMatchOptions(opts, revision)
      )
      .then(mapVersion)

  const importPayload = (input: SkillPackageImportInput) => ({
    version_key: textValue(input.versionKey, 'versionKey'),
    model_invocable: input.modelInvocable,
    user_invocable: input.userInvocable,
    activation_mode: enumValue(input.activationMode, SKILL_MODES, 'activationMode'),
    conflict_group: input.conflictGroup ?? null,
    required_capabilities: (input.requiredCapabilities ?? []).map((value) => ({
      type: value.type,
      capability_id: value.capabilityId,
      version_id: value.versionId
    })),
    ...(input.files ? { files: input.files } : {})
  })

  const importSkillDirectory = (
    definitionId: string,
    input: SkillPackageImportInput,
    opts?: RequestOptions
  ) =>
    http
      .post<CapabilityVersionWire>(
        `/capabilities/definitions/skill-versions/import-directory`,
        {
          definition_id: catalogDecimalIdentity(definitionId, 'definitionId'),
          payload: importPayload(input)
        },
        opts
      )
      .then(mapVersion)

  const importSkillZip = (
    definitionId: string,
    input: SkillPackageImportInput,
    archive: File,
    opts?: RequestOptions
  ) => {
    const form = new FormData()
    form.append(
      'definition_id',
      new Blob([JSON.stringify(catalogDecimalIdentity(definitionId, 'definitionId'))], {
        type: 'application/json'
      })
    )
    form.append(
      'metadata',
      new Blob([JSON.stringify(importPayload(input))], { type: 'application/json' })
    )
    form.append('archive', archive, archive.name)
    return http
      .post<CapabilityVersionWire>(
        '/capabilities/definitions/skill-versions/import-zip',
        form,
        opts
      )
      .then(mapVersion)
  }

  const listSkillFiles = (versionId: string, opts?: RequestOptions) =>
    http.get<SkillFile[]>(
      `/capabilities/skill-versions/files?versionId=${encodedId(versionId, 'versionId')}`,
      opts
    )

  const getSkillFile = (versionId: string, path: string, opts?: RequestOptions) =>
    http.get<Blob>(
      `/capabilities/skill-versions/file?versionId=${encodedId(versionId, 'versionId')}&path=${encodeURIComponent(path)}`,
      { ...opts, responseType: 'blob' }
    )

  const downloadSkill = (versionId: string, opts?: RequestOptions) =>
    http.get<Blob>(
      `/capabilities/skill-versions/download?versionId=${encodedId(versionId, 'versionId')}`,
      { ...opts, responseType: 'blob' }
    )

  const getSkillDefault = (definitionId: string, opts?: RequestOptions) =>
    http
      .get<CapabilityVersionWire>(
        `/capabilities/definitions/skill-default?definitionId=${encodedId(definitionId, 'definitionId')}`,
        opts
      )
      .then(mapVersion)

  const setSkillDefault = (
    definitionId: string,
    versionId: string,
    revision: number,
    opts?: RequestOptions
  ) =>
    http
      .post<CapabilityDefinitionWire>(
        `/capabilities/definitions/skill-default/execute`,
        {
          definition_id: catalogDecimalIdentity(definitionId, 'definitionId'),
          version_id: catalogDecimalIdentity(versionId, 'versionId')
        },
        ifMatchOptions(opts, revision)
      )
      .then(mapDefinition)

  const listDeployments = (status?: ToolDeploymentStatus, opts?: RequestOptions) => {
    const query = status
      ? `?status=${encodeURIComponent(enumValue(status, MOUNT_STATUSES, 'status'))}`
      : ''
    return http
      .get<CapabilityDeploymentWire[]>(`/capabilities/deployments${query}`, opts)
      .then((items) => {
        if (!Array.isArray(items)) return fail('deployments must be an array')
        return items.map(mapDeployment)
      })
  }

  const getDeployment = (deploymentKey: string, opts?: RequestOptions) =>
    http
      .get<CapabilityDeploymentWire>(
        `/capabilities/deployments/detail?deploymentKey=${encodeURIComponent(textValue(deploymentKey, 'deploymentKey'))}`,
        opts
      )
      .then(mapDeployment)

  const deploymentPayload = (input: CreateCapabilityDeploymentInput) => ({
    ...(input.deploymentKey
      ? { deployment_key: textValue(input.deploymentKey, 'deploymentKey') }
      : {}),
    tool_version_id: catalogDecimalIdentity(input.toolVersionId, 'toolVersionId'),
    provider_type: enumValue(input.providerType, DEPLOYMENT_PROVIDER_TYPES, 'providerType'),
    provider_ref: textValue(input.providerRef, 'providerRef'),
    environment: enumValue(input.environment, DEPLOYMENT_ENVIRONMENTS, 'environment'),
    ...(input.status === undefined
      ? {}
      : { status: enumValue(input.status, MOUNT_STATUSES, 'status') })
  })

  const createDeployment = (input: CreateCapabilityDeploymentInput, opts?: RequestOptions) =>
    http
      .post<CapabilityDeploymentWire>('/capabilities/deployments', deploymentPayload(input), opts)
      .then(mapDeployment)

  const createDeploymentWithReceipt = (requestId: string, input: CreateCapabilityDeploymentInput) =>
    http.post<DeploymentCreateReceipt>(
      '/capabilities/deployments/create-with-receipt',
      { request_id: requestId, payload: deploymentPayload(input) },
      { retryOnUnauthorized: false }
    )

  const deploymentCreateReceipt = (requestId: string) =>
    http.get<DeploymentCreateReceipt | undefined>(
      `/capabilities/deployments/create-with-receipt/result?request_id=${encodeURIComponent(requestId)}`,
      { silent: true }
    )

  const setDeploymentActive = (
    deploymentKey: string,
    revision: number,
    active: boolean,
    opts?: RequestOptions
  ) =>
    http
      .post<CapabilityDeploymentWire>(
        `/capabilities/deployments/${active ? 'start' : 'stop'}`,
        { deployment_key: textValue(deploymentKey, 'deploymentKey') },
        ifMatchOptions(opts, revision)
      )
      .then(mapDeployment)

  const listPlatformTools = (opts?: RequestOptions) =>
    http.get<PlatformToolWire[]>('/capabilities/platform-tools', opts).then((items) => {
      if (!Array.isArray(items)) return fail('platform tools must be an array')
      return items.map(mapPlatformTool)
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
          '/capabilities/versions/summaries?definition_id=' +
            encodeURIComponent(catalogDecimalIdentity(definitionId, 'definition_id')),
          opts
        )
        .then((items) =>
          items.map((item): CapabilityVersionSummary => ({
            id: catalogDecimalIdentity(item.id, 'id'),
            versionKey: item.version_key,
            name: item.name,
            description: item.description,
            status: enumValue(item.status, CAPABILITY_STATUSES, 'status')
          }))
        ),
    listSkillVersionSummaries: (definitionId: string, opts?: RequestOptions) =>
      http
        .get<Array<{ id: string; version_key: string; status: string }>>(
          '/capabilities/skills/versions/summaries?definition_id=' +
            encodeURIComponent(catalogDecimalIdentity(definitionId, 'definition_id')),
          opts
        )
        .then((items) =>
          items.map((item): SkillVersionSummary => ({
            id: catalogDecimalIdentity(item.id, 'id'),
            versionKey: item.version_key,
            status: enumValue(item.status, CAPABILITY_STATUSES, 'status')
          }))
        ),
    listRequirementOptions: (type: CapabilityType, opts?: RequestOptions) =>
      http
        .get<
          Array<{
            definition_id: string
            name: string
            capability_key: string
            description: string
            version_id: string
            version_key: string
            skill_estimated_tokens: number | null
          }>
        >('/capabilities/requirements/options?capability_type=' + encodeURIComponent(type), opts)
        .then((items) =>
          items.map((item): CapabilityRequirementOption => ({
            id: catalogDecimalIdentity(item.definition_id, 'definition_id'),
            name: item.name,
            capabilityKey: textValue(item.capability_key, 'capability_key'),
            description:
              typeof item.description === 'string'
                ? item.description
                : fail('description must be a string'),
            versionId: catalogDecimalIdentity(item.version_id, 'version_id'),
            versionKey: item.version_key,
            skillEstimatedTokens: item.skill_estimated_tokens
          }))
        ),
    // 已在生产环境保留 Agent 挂载客户端使用的简短别名。
    definitions: (type?: CapabilityType, status?: CapabilityStatus, opts?: RequestOptions) =>
      listDefinitions({ capabilityType: type, status }, opts),
    versions: listVersions,
    version: getVersion,
    deployments: listDeployments,

    listDefinitions,
    getDefinition,
    createDefinition,
    listVersions,
    getVersion,
    createDraftVersion,
    updateDraftVersion,
    validateVersion,
    publishVersion,
    importSkillDirectory,
    importSkillZip,
    listSkillFiles,
    getSkillFile,
    downloadSkill,
    getSkillDefault,
    setSkillDefault,
    listDeployments,
    getDeployment,
    createDeployment,
    createDeploymentWithReceipt,
    deploymentCreateReceipt,
    setDeploymentActiveWithReceipt: (
      requestId: string,
      deploymentKey: string,
      revision: number,
      active: boolean
    ) =>
      http.post<DeploymentStateReceipt>(
        '/capabilities/deployments/state-with-receipt',
        {
          request_id: requestId,
          deployment_key: deploymentKey,
          expected_revision: revision,
          active
        },
        { retryOnUnauthorized: false }
      ),
    deploymentStateReceipt: (requestId: string) =>
      http.get<DeploymentStateReceipt | undefined>(
        `/capabilities/deployments/state-with-receipt/result?request_id=${encodeURIComponent(requestId)}`,
        { silent: true }
      ),
    startDeployment: (deploymentKey: string, revision: number, opts?: RequestOptions) =>
      setDeploymentActive(deploymentKey, revision, true, opts),
    stopDeployment: (deploymentKey: string, revision: number, opts?: RequestOptions) =>
      setDeploymentActive(deploymentKey, revision, false, opts),
    listPlatformTools,
    platformTools: listPlatformTools
  }
}

export const capabilityTypes = CAPABILITY_TYPES
