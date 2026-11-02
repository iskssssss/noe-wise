/** 同一浏览器中的已确认发布只触发目录重读，不传递知识正文或授予权限。 */
export function notifyKnowledgePublished(actor: string) {
  if (!actor || typeof BroadcastChannel === 'undefined') return
  try {
    const channel = new BroadcastChannel('skillforge.knowledge.published')
    channel.postMessage(actor)
    channel.close()
  } catch {
    // 浏览器不支持通知时保留页面手动刷新。
  }
}

export function listenKnowledgePublished(accept: (actor: string) => void) {
  if (typeof BroadcastChannel === 'undefined') return () => {}
  try {
    const channel = new BroadcastChannel('skillforge.knowledge.published')
    channel.onmessage = (event) => {
      if (typeof event.data === 'string') accept(event.data)
    }
    return () => channel.close()
  } catch {
    return () => {}
  }
}
