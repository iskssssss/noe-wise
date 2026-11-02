/** HTML 成果必须通过与管理页面隔离的预览 origin 加载。 */
export function artifactPreviewOrigin(value?: string): string | undefined {
  if (!value?.trim()) return undefined
  try {
    const url = new URL(value)
    if (
      !['http:', 'https:'].includes(url.protocol) ||
      url.username ||
      url.password ||
      value !== url.origin
    )
      throw new Error()
    return url.origin
  } catch {
    throw new Error('VITE_ARTIFACT_PREVIEW_ORIGIN 必须是无路径、凭据或查询参数的 HTTP(S) origin')
  }
}

export function artifactPreviewCsp(origin: string | undefined): string {
  const validated = artifactPreviewOrigin(origin)
  return 'frame-src blob:' + (validated ? ' ' + validated : '')
}

export function isArtifactPreviewUrlAllowed(
  value: string,
  previewOrigin: string | undefined,
  applicationOrigin: string
): boolean {
  if (!previewOrigin) return false
  try {
    const expected = artifactPreviewOrigin(previewOrigin)
    const target = new URL(value)
    const application = new URL(applicationOrigin)
    return (
      !!expected &&
      target.origin === expected &&
      target.hostname !== application.hostname &&
      !target.username &&
      !target.password
    )
  } catch {
    return false
  }
}
