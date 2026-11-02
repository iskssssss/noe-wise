import { workflowScriptResources } from './workflow'

/** 导入采用资源前核对真实文件字节；结构、路径和规范 Base64 由原契约统一校验。 */
export async function verifyWorkflowResourceIntegrity(resources: unknown): Promise<void> {
  for (const resource of workflowScriptResources(resources)) {
    let digest: ArrayBuffer
    try {
      const bytes = Uint8Array.from(atob(resource.content_base64), (character) =>
        character.charCodeAt(0)
      )
      digest = await globalThis.crypto.subtle.digest('SHA-256', bytes)
    } catch {
      throw new Error(`无法校验脚本资源摘要：${resource.path}`)
    }
    const actual = Array.from(new Uint8Array(digest), (byte) =>
      byte.toString(16).padStart(2, '0')
    ).join('')
    if (actual !== resource.sha256)
      throw new Error(`脚本资源 SHA-256 摘要与内容不一致：${resource.path}`)
  }
}
