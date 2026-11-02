/** 用户选择的有序正文；媒体ID由服务端上传后返回。 */
export type ChatContent = { type: 'text'; text: string } | { type: 'image'; media_id: string }
export interface ChatMedia {
  media_id: string
  mime_type: string
  byte_length: number
  width: number
  height: number
}
export interface ChatMediaLimits {
  max_file_bytes: number
  max_pixels: number
  mime_types: string[]
}
export interface DraftImage extends ChatMedia {
  name: string
  preview: string
}
export interface ChatMediaClient {
  uploadMedia(session: string, uploadId: string, file: File): Promise<ChatMedia>
  mediaLimits(signal?: AbortSignal): Promise<ChatMediaLimits>
  mediaContent(session: string, media: string, signal?: AbortSignal): Promise<Blob>
}
export function messageContents(
  text: string,
  images: readonly ChatMedia[],
  parts: readonly ChatContent[] = []
): ChatContent[] {
  if (parts.length)
    return [
      ...parts
        .filter((part) => part.type === 'image' || part.text.length > 0)
        .map((part) => ({ ...part })),
      ...(text.length ? [{ type: 'text' as const, text }] : [])
    ]
  return [
    ...(text.length ? [{ type: 'text' as const, text }] : []),
    ...images.map((image) => ({ type: 'image' as const, media_id: image.media_id }))
  ]
}
export function historyImages(metadata: Record<string, unknown> | undefined): string[] {
  if (!Array.isArray(metadata?.content_parts)) return []
  return metadata.content_parts.flatMap((part) =>
    part && part.type === 'image' && typeof part.media_id === 'string' ? [part.media_id] : []
  )
}

export function historyContents(metadata: Record<string, unknown> | undefined): ChatContent[] {
  if (!Array.isArray(metadata?.content_parts)) return []
  return metadata.content_parts.flatMap((part: unknown): ChatContent[] => {
    if (!part || typeof part !== 'object') return []
    const value = part as Record<string, unknown>
    if (value.type === 'text' && typeof value.text === 'string')
      return [{ type: 'text', text: value.text }]
    if (value.type === 'image' && typeof value.media_id === 'string')
      return [{ type: 'image', media_id: value.media_id }]
    return []
  })
}
