/** 已确认的技能发布只提示同账号重新读取目录，不传递技能内容或授权。 */
export function notifySkillPublished(actor: string) {
  if (!actor || typeof BroadcastChannel === 'undefined') return
  try {
    const channel = new BroadcastChannel('skillforge.skill.published')
    channel.postMessage(actor)
    channel.close()
  } catch {
    // 无法通知时保留手动刷新入口。
  }
}

export function listenSkillPublished(accept: (actor: string) => void) {
  if (typeof BroadcastChannel === 'undefined') return () => {}
  try {
    const channel = new BroadcastChannel('skillforge.skill.published')
    channel.onmessage = (event) => {
      if (typeof event.data === 'string') accept(event.data)
    }
    return () => channel.close()
  } catch {
    return () => {}
  }
}
