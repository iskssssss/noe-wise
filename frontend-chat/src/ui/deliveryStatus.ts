import type { RunDeliveryStatus } from '../protocol'

/** 面板与 /delivery 命令采用相同的执行、读取和回调语义。 */
export function deliveryStatusLabels(delivery: RunDeliveryStatus) {
  const runtime: Record<string, string> = {
    queued: '等待执行',
    running: '执行中',
    waiting_hitl: '等待确认',
    suspended: '已挂起',
    resuming: '恢复中',
    completed: '已完成',
    terminated: '已终止',
    failed: '执行失败',
    cancelled: '已取消',
    outcome_unknown: '结果待确认'
  }
  return {
    runtime: runtime[delivery.runtime_status] ?? '状态待核实',
    sse: {
      replay_available: '可重新读取',
      unknown: '尚未确认可重新读取'
    }[delivery.sse.status],
    webhook: {
      pending: '正在送达',
      delivered: '接收方已确认',
      failed: '存在送达失败',
      unknown: '未确认送达'
    }[delivery.webhook.status]
  }
}
