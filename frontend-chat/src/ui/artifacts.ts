import type { Deliverable, Reference } from '../protocol'

const previewFormats = new Set(['html', 'md', 'txt', 'csv', 'json', 'yaml', 'yml'])

export const canPreview = (file: Deliverable['files'][number]) =>
  previewFormats.has(file.format.toLowerCase())

/** 公开成果可能由成员产出并被主运行采用，两种明确关联都归入本轮。 */
export function artifactsForRuns(artifacts: Deliverable[], runIds: string[]) {
  const runs = new Set(runIds.filter(Boolean))
  return artifacts.filter(
    (artifact) =>
      (artifact.run_id && runs.has(artifact.run_id)) ||
      artifact.result_run_ids?.some((run) => runs.has(run))
  )
}

/** 引用固定文件身份，后续发布新版本不会悄悄改变用户选中的内容。 */
export function artifactFileReference(
  artifact: Deliverable,
  file: Deliverable['files'][number]
): Reference {
  return {
    label: `${artifact.name} · v${artifact.revision} / ${file.download_name}`,
    source: {
      kind: 'artifact_files',
      files: [{ artifact_id: file.artifact_id, path: file.path }]
    }
  }
}

export function artifactUrl(value: string, base: string) {
  const url = new URL(value, base)
  if (!['https:', 'http:'].includes(url.protocol) || url.username || url.password)
    throw new Error('服务返回的链接无效。')
  return url.href
}

export function fileSize(bytes: number) {
  return bytes >= 1_000_000
    ? `${(bytes / 1_000_000).toFixed(1)} MB`
    : `${Math.max(1, Math.ceil(bytes / 1024))} KB`
}
