import type { ChatReferenceView } from './chatReferences'

/** 最终成果引用的 Runtime 产物文件。文件操作必须使用 file_id，path 只用于展示与 diff。 */
export interface ChatArtifactFile {
  file_id: string
  artifact_id: string
  path: string
  download_name: string
  size: number
  sha256: string
  format: string
  representation_id: string
  primary_preview: boolean
  entry: boolean
}

export interface ArtifactInputReference {
  artifact_id: string
  path: string
}

export interface ArtifactProvenance extends ArtifactInputReference {
  size: number
  sha256: string
}

export interface ArtifactEvidence {
  artifact_id: string
  path: string
  summary: string
}

/** 会话最终成果的修订视图；id 是修订 ID，deliverable_id 跨修订稳定。 */
export interface ChatArtifact {
  id: string
  deliverable_id: string
  revision: number
  current: boolean
  result_run_ids: string[]
  session_id: string
  run_id: string
  type: 'file' | 'website'
  name: string
  size: number
  sha256: string
  expires_at: null
  status: 'ready' | 'expired' | 'failed'
  files: ChatArtifactFile[]
  artifact_inputs: ArtifactProvenance[]
  evidence: ArtifactEvidence[]
}

export interface ArtifactDiffRequest {
  base: ArtifactInputReference
  target: ArtifactInputReference
}

export type ArtifactDiffReference = ArtifactProvenance

export interface ArtifactDiffChange {
  type: 'added' | 'removed' | 'unchanged'
  base_line: number | null
  target_line: number | null
  text: string
}

export interface ArtifactDiffResult {
  status: 'comparable' | 'binary' | 'too_large' | 'too_many_lines'
  base: ArtifactDiffReference
  target: ArtifactDiffReference
  changes: ArtifactDiffChange[]
}

export interface ArtifactRecoveryRequest {
  revision_id: string
}

/** 只表示输入在请求时通过授权与摘要核验，不表示已经执行或生成新版本。 */
export interface ArtifactRecoveryResult {
  status: 'validated'
  session_id: string
  references: ChatReferenceView[]
  artifact_inputs: ArtifactProvenance[]
  base_revision_id?: string
  deliverable_id?: string
}

export interface ArtifactPreviewTicket {
  url: string
  kind: 'html' | 'markdown' | 'text'
}

const PREVIEWABLE_FORMATS = new Set([
  'html',
  'htm',
  'text/html',
  'markdown',
  'md',
  'text/markdown',
  'text',
  'txt',
  'plain',
  'text/plain'
])

export function isArtifactFilePreviewable(file: ChatArtifactFile): boolean {
  const format = file.format.trim().toLocaleLowerCase()
  if (PREVIEWABLE_FORMATS.has(format)) return true
  return /\.(?:html?|md|markdown|txt)$/i.test(file.path)
}

/** 数值型业务 ID 在前端边界统一转成字符串，避免选择器和请求体隐式数值化。 */
export function normalizeChatArtifact(artifact: ChatArtifact): ChatArtifact {
  return {
    ...artifact,
    id: String(artifact.id),
    deliverable_id: String(artifact.deliverable_id),
    session_id: String(artifact.session_id),
    run_id: String(artifact.run_id),
    result_run_ids: (artifact.result_run_ids ?? []).map(String),
    files: (artifact.files ?? []).map((file) => ({
      ...file,
      file_id: String(file.file_id),
      artifact_id: String(file.artifact_id),
      representation_id: String(file.representation_id)
    })),
    artifact_inputs: (artifact.artifact_inputs ?? []).map((input) => ({
      ...input,
      artifact_id: String(input.artifact_id)
    })),
    evidence: (artifact.evidence ?? []).map((item) => ({
      ...item,
      artifact_id: String(item.artifact_id)
    }))
  }
}

export function normalizeArtifactRecoveryResult(
  result: ArtifactRecoveryResult
): ArtifactRecoveryResult {
  return {
    ...result,
    session_id: String(result.session_id),
    ...(result.base_revision_id === null || result.base_revision_id === undefined
      ? {}
      : { base_revision_id: String(result.base_revision_id) }),
    ...(result.deliverable_id === null || result.deliverable_id === undefined
      ? {}
      : { deliverable_id: String(result.deliverable_id) }),
    artifact_inputs: (result.artifact_inputs ?? []).map((input) => ({
      ...input,
      artifact_id: String(input.artifact_id)
    }))
  }
}
