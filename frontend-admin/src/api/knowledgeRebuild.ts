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
/** 复用机构唯一HTTP实例；KB控制修订与重建任务修订是独立CAS身份。 */
export function createKnowledgeRebuildApi(http: {
  get: <T>(path: string, options?: RequestOptions) => Promise<T>
  post: <T>(path: string, body: unknown, options?: RequestOptions) => Promise<T>
}) {
  const root = '/knowledge/bases/rebuilds'
  const command = (job: KnowledgeRebuild) => {
    if (!job.job_id || job.revision === null || job.revision === undefined)
      throw new Error('没有可操作的重建任务')
    return { kb_id: job.kb_id, job_id: job.job_id, expected_revision: job.revision }
  }
  return {
    detail: (kbId: string, options?: RequestOptions) =>
      http.get<KnowledgeRebuild>(`${root}/detail?kbId=${encodeURIComponent(kbId)}`, options),
    create: (
      kbId: string,
      modelId: string,
      modelRevision: number,
      baseRevision: number,
      key: string
    ) =>
      http.post<KnowledgeRebuild>(`${root}/create`, {
        kb_id: kbId,
        target_model_id: modelId,
        target_model_revision: modelRevision,
        expected_base_revision: baseRevision,
        idempotency_key: key
      }),
    cancel: (job: KnowledgeRebuild) => http.post<KnowledgeRebuild>(`${root}/cancel`, command(job)),
    retry: (job: KnowledgeRebuild, confirmUnknownCost: boolean) =>
      http.post<KnowledgeRebuild>(`${root}/retry`, {
        ...command(job),
        confirm_unknown_cost: confirmUnknownCost
      }),
    publish: (job: KnowledgeRebuild, policy: KnowledgeRebuildPolicy) =>
      http.post<KnowledgeRebuild>(`${root}/publish`, {
        ...command(job),
        expected_base_revision: job.base_revision,
        policy
      })
  }
}
