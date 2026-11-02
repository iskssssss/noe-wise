import type { RequestOptions } from '@skillforge/shared/utils/http'
import { catalogDecimalIdentity } from '@skillforge/shared/integration/decimalIdentity'

/** Builder 服务端 Problems 面板条目。 */
export interface WorkflowValidationProblem {
  code: string
  severity: 'error' | 'warning'
  objectRef: string
  fieldPath: string
  message: string
}

/** 可变草稿 revision；仅经 If-Match 保存，不能覆盖已发布 DefinitionVersion。 */
export interface WorkflowDraft {
  workflowId: string
  status: 'draft' | 'published' | 'archived'
  revision: number
  activeDefinitionVersionId: string | null
  definition: Record<string, unknown>
  problems: WorkflowValidationProblem[]
}

/** 当前租户的 Builder 容器摘要；管理页不再混入旧模板列表。 */
export interface WorkflowDraftListItem {
  workflowId: string
  status: WorkflowDraft['status']
  revision: number
  activeDefinitionVersionId: string | null
  name: string
  description: string
}
export interface WorkflowPublication {
  definitionVersionId: string
  definitionVersion: number
  capabilityVersionId: string | null
  createdAt: string
  current: boolean
}
export interface WorkflowPublicationDetail extends WorkflowPublication {
  workflowId: string
  definition: Record<string, unknown>
}
export interface WorkflowDraftList {
  total: number
  items: WorkflowDraftListItem[]
}

/** 已安装的 Builder 工作流实例，只使用不可变发布身份。 */
export interface BuilderWorkflowInstance {
  id: string
  instanceId: string
  workflowId: string
  definitionVersionId: string
  installationRevision: string | null
  capabilityVersionId: string | null
  status: string
  config: Record<string, unknown>
  customPrompt: string | null
  createdAt: string
}
export interface BuilderWorkflowInstanceList {
  total: number
  items: BuilderWorkflowInstance[]
}

export interface WorkflowPublishResult {
  workflowId: string
  draftRevision: number
  definitionVersionId: string
  definitionVersion: number
}

/** 保存并发布命令只持有原请求身份，结果以服务端回执为准。 */
export interface WorkflowSaveAndPublishCommand {
  request_id: string
  workflow_id: string
  revision: number
  definition: Record<string, unknown>
  layout: Record<string, unknown>
  viewport: Record<string, unknown>
}
export interface WorkflowSaveAndPublishReceipt {
  request_id: string
  workflow_id: string
  status: 'PROCESSING' | 'SUCCEEDED' | 'REJECTED'
  publication?: {
    workflow_id: string
    draft_revision: number
    definition_version_id: string
    definition_version: number
    capability_version_id: string
  } | null
  error_code?: string | null
  error_detail?: unknown
}

export interface WorkflowCapabilityBinding {
  slot: string
  capability: { type: 'TOOL' | 'KNOWLEDGE_SOURCE'; capabilityId: string; versionId: string }
  tool_deployment_id?: string
  tool_deployment_revision?: number
  knowledge_revision?: number
}
export interface WorkflowChildBinding {
  workflow: string
  instance_id: string
  definition_version_id: string
  installation_revision: string
}
export interface WorkflowAgentBinding {
  slot: string
  agent_id: string
}
export interface WorkflowInstallationBindings {
  capabilities: WorkflowCapabilityBinding[]
  workflows: WorkflowChildBinding[]
  agents: WorkflowAgentBinding[]
}
/** CapabilityRef 在 HTTP 边界采用服务端 snake_case，不改变页面模型。 */
export function workflowBindingsToWire(bindings: WorkflowInstallationBindings) {
  return {
    ...bindings,
    capabilities: bindings.capabilities.map(({ capability, ...binding }) => ({
      ...binding,
      capability: {
        type: capability.type,
        capability_id: capability.capabilityId,
        version_id: capability.versionId
      }
    }))
  }
}
export interface WorkflowInstallResult {
  status: string
  workflowId: string
  tenantId: string
  instanceId: string
  installationRevision: string
}
export interface WorkflowInstallReceipt {
  request_id: string
  workflow_id: string
  instance_id: string
  installation_revision: string
}

/** 发布前草稿审阅的 JSON Pointer 差异项。 */
export interface WorkflowDefinitionDiffEntry {
  operation: 'add' | 'remove' | 'replace'
  path: string
  beforeValue: unknown
  afterValue: unknown
}

export interface WorkflowDraftDiff {
  draftRevision: number
  baseDefinitionVersion: number | null
  changes: WorkflowDefinitionDiffEntry[]
}

/** 与当前 draft revision 绑定的画布位置和视口；不属于 Workflow Definition。 */
export interface WorkflowBuilderSnapshot {
  workflowId: string
  draftRevision: number
  layout: Record<string, unknown>
  viewport: { zoom?: number; panX?: number; panY?: number }
}

type WorkflowValidationProblemWire = {
  code: string
  severity: 'error' | 'warning'
  object_ref: string
  field_path: string
  message: string
}
type WorkflowDraftWire = {
  workflow_id: string
  status: WorkflowDraft['status']
  revision: number
  active_definition_version_id: string | number | null
  definition: Record<string, unknown>
  problems: WorkflowValidationProblemWire[]
}
type WorkflowDraftListWire = {
  total: number
  items: Array<{
    workflow_id: string
    status: WorkflowDraft['status']
    revision: number
    active_definition_version_id: string | number | null
    name: string
    description: string
  }>
}
type BuilderWorkflowInstanceListWire = {
  total: number
  items: Array<{
    id: string
    instance_id: string
    workflow_id: string
    definition_version_id: string
    installation_revision?: string | number | null
    capability_version_id: string | null
    status: string
    config?: Record<string, unknown> | null
    custom_prompt?: string | null
    created_at: string
  }>
}
type WorkflowPublishResultWire = {
  workflow_id: string
  draft_revision: number
  definition_version_id: string
  definition_version: number
}
type WorkflowInstallResultWire = {
  status: string
  workflow_id: string
  tenant_id: string
  instance_id: string
  installation_revision: string | number
}
type WorkflowDraftDiffWire = {
  draft_revision: number
  base_definition_version: number | null
  changes: Array<{
    operation: WorkflowDefinitionDiffEntry['operation']
    path: string
    before_value: unknown
    after_value: unknown
  }>
}
type WorkflowBuilderSnapshotWire = {
  workflow_id: string
  draft_revision: number
  layout: Record<string, unknown>
  viewport: { zoom?: number; panX?: number; panY?: number }
}

type WorkflowDraftHttp = {
  get: <T>(path: string, opts?: RequestOptions) => Promise<T>
  post: <T>(path: string, body?: unknown, opts?: RequestOptions) => Promise<T>
  put: <T>(path: string, body?: unknown, opts?: RequestOptions) => Promise<T>
}

function problemFromWire(problem: WorkflowValidationProblemWire): WorkflowValidationProblem {
  return {
    code: problem.code,
    severity: problem.severity,
    objectRef: problem.object_ref,
    fieldPath: problem.field_path,
    message: problem.message
  }
}

function publicationReceiptFromWire(receipt: WorkflowSaveAndPublishReceipt | undefined) {
  if (!receipt) return receipt
  const detail = receipt.error_detail as { problems?: WorkflowValidationProblemWire[] } | undefined
  if (detail && Array.isArray(detail.problems))
    return {
      ...receipt,
      error_detail: { ...detail, problems: detail.problems.map(problemFromWire) }
    }
  return receipt
}

function draftFromWire(draft: WorkflowDraftWire): WorkflowDraft {
  return {
    workflowId: draft.workflow_id,
    status: draft.status,
    revision: draft.revision,
    activeDefinitionVersionId:
      draft.active_definition_version_id === null ||
      draft.active_definition_version_id === undefined
        ? null
        : catalogDecimalIdentity(
            draft.active_definition_version_id,
            'active_definition_version_id'
          ),
    definition: draft.definition,
    problems: draft.problems.map(problemFromWire)
  }
}

function snapshotFromWire(snapshot: WorkflowBuilderSnapshotWire): WorkflowBuilderSnapshot {
  return {
    workflowId: snapshot.workflow_id,
    draftRevision: snapshot.draft_revision,
    layout: snapshot.layout,
    viewport: snapshot.viewport
  }
}

/**
 * Builder 的版本化设计 API。服务端统一返回 SNAKE_CASE，页面模型仅在此边界转换。
 */
export function createWorkflowDraftApi({ get, post, put }: WorkflowDraftHttp) {
  return {
    list: () =>
      get<WorkflowDraftListWire>('/builder/workflows').then((result) => ({
        total: result.total,
        items: result.items.map((item) => ({
          workflowId: item.workflow_id,
          status: item.status,
          revision: item.revision,
          activeDefinitionVersionId:
            item.active_definition_version_id === null ||
            item.active_definition_version_id === undefined
              ? null
              : catalogDecimalIdentity(
                  item.active_definition_version_id,
                  'active_definition_version_id'
                ),
          name: item.name,
          description: item.description
        }))
      })),
    instances: () =>
      get<BuilderWorkflowInstanceListWire>('/builder/workflows/instances').then((result) => ({
        total: result.total,
        items: result.items.map((item) => ({
          id: catalogDecimalIdentity(item.id, 'workflow_instance_id'),
          instanceId: item.instance_id,
          workflowId: item.workflow_id,
          definitionVersionId: catalogDecimalIdentity(
            item.definition_version_id,
            'definition_version_id'
          ),
          installationRevision:
            item.installation_revision === null || item.installation_revision === undefined
              ? null
              : String(item.installation_revision),
          capabilityVersionId:
            item.capability_version_id === null || item.capability_version_id === undefined
              ? null
              : String(item.capability_version_id),
          status: item.status,
          config: item.config ?? {},
          customPrompt: item.custom_prompt ?? null,
          createdAt: item.created_at
        }))
      })),
    create: (workflowId: string, definition: Record<string, unknown>) =>
      post<WorkflowDraftWire>(
        '/builder/workflows',
        { workflow_id: workflowId, definition },
        { retryOnUnauthorized: false }
      ).then(draftFromWire),
    get: (workflowId: string, opts?: RequestOptions) =>
      get<WorkflowDraftWire>(
        `/builder/workflows/draft?workflowId=${encodeURIComponent(workflowId)}`,
        opts
      ).then(draftFromWire),
    history: (workflowId: string) =>
      get<{
        items: Array<{
          definition_version_id: string | number
          definition_version: number
          capability_version_id: string | number | null
          created_at: string
          current: boolean
        }>
      }>(`/builder/workflows/history?workflowId=${encodeURIComponent(workflowId)}`).then((result) =>
        result.items.map((item) => ({
          definitionVersionId: catalogDecimalIdentity(
            item.definition_version_id,
            'definition_version_id'
          ),
          definitionVersion: item.definition_version,
          capabilityVersionId:
            item.capability_version_id === null || item.capability_version_id === undefined
              ? null
              : catalogDecimalIdentity(item.capability_version_id, 'capability_version_id'),
          createdAt: item.created_at,
          current: item.current
        }))
      ),
    publicationDetail: (
      workflowId: string,
      definitionVersionId: string
    ): Promise<WorkflowPublicationDetail> =>
      get<{
        workflow_id: string
        definition_version_id: string | number
        definition_version: number
        capability_version_id: string | number | null
        created_at: string
        current: boolean
        definition: Record<string, unknown>
      }>(
        `/builder/workflows/history/detail?workflowId=${encodeURIComponent(workflowId)}&definitionVersionId=${definitionVersionId}`
      ).then((item) => ({
        workflowId: item.workflow_id,
        definitionVersionId: catalogDecimalIdentity(
          item.definition_version_id,
          'definition_version_id'
        ),
        definitionVersion: item.definition_version,
        capabilityVersionId:
          item.capability_version_id === null || item.capability_version_id === undefined
            ? null
            : catalogDecimalIdentity(item.capability_version_id, 'capability_version_id'),
        createdAt: item.created_at,
        current: item.current,
        definition: item.definition
      })),
    restore: (workflowId: string, revision: number, definitionVersionId: string) =>
      post<WorkflowDraftWire>(
        '/builder/workflows/draft/restore',
        {
          workflow_id: workflowId,
          definition_version_id: definitionVersionId
        },
        { headers: { 'If-Match': String(revision) } }
      ).then(draftFromWire),
    diff: (workflowId: string) =>
      get<WorkflowDraftDiffWire>(
        `/builder/workflows/draft/diff?workflowId=${encodeURIComponent(workflowId)}`
      ).then((result) => ({
        draftRevision: result.draft_revision,
        baseDefinitionVersion: result.base_definition_version,
        changes: result.changes.map((change) => ({
          operation: change.operation,
          path: change.path,
          beforeValue: change.before_value,
          afterValue: change.after_value
        }))
      })),
    snapshot: {
      get: (workflowId: string) =>
        get<WorkflowBuilderSnapshotWire>(
          `/builder/workflows/draft/snapshot?workflowId=${encodeURIComponent(workflowId)}`
        ).then(snapshotFromWire),
      save: (
        workflowId: string,
        revision: number,
        layout: Record<string, unknown>,
        viewport: Record<string, unknown>
      ) =>
        put<WorkflowBuilderSnapshotWire>(
          `/builder/workflows/draft/snapshot`,
          { workflow_id: workflowId, payload: { layout, viewport } },
          {
            headers: { 'If-Match': String(revision) },
            retryOnUnauthorized: false
          }
        ).then(snapshotFromWire)
    },
    save: (workflowId: string, revision: number, definition: Record<string, unknown>) =>
      put<WorkflowDraftWire>(
        `/builder/workflows/draft`,
        { workflow_id: workflowId, payload: { definition } },
        {
          headers: { 'If-Match': String(revision) },
          retryOnUnauthorized: false
        }
      ).then(draftFromWire),
    fork: (workflowId: string, revision: number) =>
      post<WorkflowDraftWire>(
        `/builder/workflows/draft/fork`,
        { workflow_id: workflowId },
        {
          headers: { 'If-Match': String(revision) }
        }
      ).then(draftFromWire),
    validate: (workflowId: string) =>
      post<{ revision: number; problems: WorkflowValidationProblemWire[] }>(
        `/builder/workflows/draft/validate`,
        { workflow_id: workflowId }
      ).then((result) => ({
        revision: result.revision,
        problems: result.problems.map(problemFromWire)
      })),
    validateNewContent: (definition: Record<string, unknown>) =>
      post<{ revision: number; problems: WorkflowValidationProblemWire[] }>(
        '/builder/workflows/validate-content',
        { definition }
      ).then((result) => ({
        revision: result.revision,
        problems: result.problems.map(problemFromWire)
      })),
    validateContent: (workflowId: string, revision: number, definition: Record<string, unknown>) =>
      post<{ revision: number; problems: WorkflowValidationProblemWire[] }>(
        '/builder/workflows/draft/validate-content',
        { workflow_id: workflowId, definition },
        { headers: { 'If-Match': String(revision) } }
      ).then((result) => ({
        revision: result.revision,
        problems: result.problems.map(problemFromWire)
      })),
    publish: (workflowId: string, revision: number) =>
      post<WorkflowPublishResultWire>(
        `/builder/workflows/publish`,
        { workflow_id: workflowId },
        {
          headers: { 'If-Match': String(revision) }
        }
      ).then((result) => ({
        workflowId: result.workflow_id,
        draftRevision: result.draft_revision,
        definitionVersionId: catalogDecimalIdentity(
          result.definition_version_id,
          'definition_version_id'
        ),
        definitionVersion: result.definition_version
      })),
    saveAndPublish: (command: WorkflowSaveAndPublishCommand) =>
      post<WorkflowSaveAndPublishReceipt>('/builder/workflows/save-and-publish', command, {
        retryOnUnauthorized: false
      }).then(publicationReceiptFromWire),
    saveAndPublishResult: (workflowId: string, requestId: string) =>
      get<WorkflowSaveAndPublishReceipt | undefined>(
        `/builder/workflows/save-and-publish/result?workflow_id=${encodeURIComponent(workflowId)}&request_id=${encodeURIComponent(requestId)}`
      ).then(publicationReceiptFromWire),
    install: (
      workflowId: string,
      bindings: WorkflowInstallationBindings,
      config: Record<string, unknown> = {},
      customPrompt = ''
    ) =>
      post<WorkflowInstallResultWire>(`/builder/workflows/install`, {
        workflow_id: workflowId,
        payload: { config, custom_prompt: customPrompt, bindings: workflowBindingsToWire(bindings) }
      }).then((result) => ({
        status: result.status,
        workflowId: result.workflow_id,
        tenantId: String(result.tenant_id),
        instanceId: String(result.instance_id),
        installationRevision: String(result.installation_revision)
      })),
    installWithReceipt: (
      requestId: string,
      workflowId: string,
      bindings: WorkflowInstallationBindings,
      config: Record<string, unknown>,
      customPrompt: string
    ) =>
      post<WorkflowInstallReceipt>(
        '/builder/workflows/install-with-receipt',
        {
          request_id: requestId,
          workflow_id: workflowId,
          payload: {
            config,
            custom_prompt: customPrompt,
            bindings: workflowBindingsToWire(bindings)
          }
        },
        { retryOnUnauthorized: false }
      ),
    installReceipt: (workflowId: string, requestId: string) =>
      get<WorkflowInstallReceipt | undefined>(
        `/builder/workflows/install-with-receipt/result?workflow_id=${encodeURIComponent(workflowId)}&request_id=${encodeURIComponent(requestId)}`
      )
  }
}
