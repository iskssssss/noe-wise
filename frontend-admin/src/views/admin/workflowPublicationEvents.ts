/** 已确认的工作流发布只提示同账号重新读取目录，不传递工作流内容或授权。 */
export function notifyWorkflowPublished(actor: string) {
  if (!actor || typeof BroadcastChannel === 'undefined') return
  try {
    const channel = new BroadcastChannel('skillforge.workflow.published')
    channel.postMessage(actor)
    channel.close()
  } catch {
    // 无法通知时保留手动刷新入口。
  }
}

export function listenWorkflowPublished(accept: (actor: string) => void) {
  if (typeof BroadcastChannel === 'undefined') return () => {}
  try {
    const channel = new BroadcastChannel('skillforge.workflow.published')
    channel.onmessage = (event) => {
      if (typeof event.data === 'string') accept(event.data)
    }
    return () => channel.close()
  } catch {
    return () => {}
  }
}
