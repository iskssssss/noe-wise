import type { RequestOptions } from '@skillforge/shared/utils/http'
export interface KnowledgeRebuild {
  job_id: string | null
  kb_id: string
  status:
    | 'IDLE'
    | 'QUEUED'
    | 'RUNNING'
    | 'READY'
    | 'FAILED'
    | 'UNKNOWN'
    | 'CANCELLED'
    | 'PUBLISHING'
    | 'PUBLISHED'
  revision: number | null
  target_model_id: string | null
  target_model_revision: number | null
  target_dimensions: number | null
  document_count: number
  ready_documents: number
  unknown_batches: number
  error_code: string | null
  published_revision: number | null
  write_locked: boolean
  base_revision: number
}
export interface KnowledgeRebuildPolicy {
  model_invocable: boolean
  user_invocable: boolean
  allowed_operators: string[]
  freshness_policy: string
  citation_required: boolean
}
export interface KnowledgeRebuildPublicationCommand {
  request_id: string
  kb_id: string
  job_id: string
  expected_revision: number
  expected_base_revision: number
  policy: KnowledgeRebuildPolicy
}
export interface KnowledgeRebuildPublicationResolution {
  status: 'UNKNOWN' | 'SUCCEEDED' | 'FENCED'
  snapshot: KnowledgeRebuild | null
}
function exactRebuildIds(value: KnowledgeRebuild): KnowledgeRebuild {
  if (
    !value ||
    typeof value.kb_id !== 'string' ||
    (value.job_id !== null && typeof value.job_id !== 'string') ||
    (value.target_model_id !== null &&
      value.target_model_id !== undefined &&
      typeof value.target_model_id !== 'string')
  )
    throw Error('重建任务标识不是精确字符串，无法安全处理')
  return value
}
function exactResolution(
  value: KnowledgeRebuildPublicationResolution
): KnowledgeRebuildPublicationResolution {
  if (value?.snapshot) exactRebuildIds(value.snapshot)
  return value
}
/** 复用机构唯一HTTP实例；KB控制修订与重建任务修订是独立CAS身份。 */
export function createKnowledgeRebuildApi(http: {
  get: <T>(path: string, options?: RequestOptions) => Promise<T>
  post: <T>(path: string, body: unknown, options?: RequestOptions) => Promise<T>
}) {
  const root = '/knowledge/bases/rebuilds'
  const command = (job: KnowledgeRebuild) => {
    exactRebuildIds(job)
    if (!job.job_id || job.revision === null || job.revision === undefined)
      throw new Error('没有可操作的重建任务')
    return { kb_id: job.kb_id, job_id: job.job_id, expected_revision: job.revision }
  }
  const publicationCommand = (value: KnowledgeRebuildPublicationCommand) => {
    if (typeof value.kb_id !== 'string' || typeof value.job_id !== 'string')
      throw Error('重建发布标识不是精确字符串，未发送请求')
    return value
  }
  return {
    detail: async (kbId: string, options?: RequestOptions) =>
      exactRebuildIds(
        await http.get<KnowledgeRebuild>(`${root}/detail?kbId=${encodeURIComponent(kbId)}`, options)
      ),
    create: (
      kbId: string,
      modelId: string,
      modelRevision: number,
      baseRevision: number,
      key: string
    ) =>
      http
        .post<KnowledgeRebuild>(`${root}/create`, {
          kb_id: kbId,
          target_model_id: modelId,
          target_model_revision: modelRevision,
          expected_base_revision: baseRevision,
          idempotency_key: key
        })
        .then(exactRebuildIds),
    cancel: (job: KnowledgeRebuild) =>
      http.post<KnowledgeRebuild>(`${root}/cancel`, command(job)).then(exactRebuildIds),
    retry: (job: KnowledgeRebuild, confirmUnknownCost: boolean) =>
      http
        .post<KnowledgeRebuild>(`${root}/retry`, {
          ...command(job),
          confirm_unknown_cost: confirmUnknownCost
        })
        .then(exactRebuildIds),
    publish: (job: KnowledgeRebuild, policy: KnowledgeRebuildPolicy) =>
      http
        .post<KnowledgeRebuild>(`${root}/publish`, {
          ...command(job),
          expected_base_revision: job.base_revision,
          policy
        })
        .then(exactRebuildIds),
    publishWithReceipt: (value: KnowledgeRebuildPublicationCommand) =>
      http
        .post<KnowledgeRebuildPublicationResolution>(
          `${root}/publish-with-receipt`,
          publicationCommand(value),
          {
            retryOnUnauthorized: false
          }
        )
        .then(exactResolution),
    publishResolution: (kbId: string, jobId: string, requestId: string) => {
      if (typeof kbId !== 'string' || typeof jobId !== 'string')
        throw Error('重建发布标识不是精确字符串，无法安全核对')
      return http
        .get<KnowledgeRebuildPublicationResolution>(
          `${root}/publish-resolution?${new URLSearchParams({ kb_id: kbId, job_id: jobId, request_id: requestId })}`,
          { silent: true }
        )
        .then(exactResolution)
    },
    publishFence: (value: KnowledgeRebuildPublicationCommand) =>
      http
        .post<KnowledgeRebuildPublicationResolution>(
          `${root}/publish-fence`,
          publicationCommand(value),
          {
            retryOnUnauthorized: false
          }
        )
        .then(exactResolution)
  }
}
