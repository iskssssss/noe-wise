/** 持久 Outbox 对指定 Runtime Run 的只读送达摘要。 */
export interface ChatRunDeliveryStatus {
  run_id: string
  runtime_status: string
  sse: {
    status: 'replay_available' | 'unknown'
    replay_available_count: number
  }
  webhook: {
    status: 'pending' | 'delivered' | 'failed' | 'unknown'
    pending_count: number
    delivered_count: number
    failed_count: number
    unknown_count: number
  }
  failures: Array<{
    event_id: string
    attempts: number | null
    error_code: string | null
    updated_at: string | null
  }>
}

export type ChatRunDeliveryQuery = (
  sessionId: string,
  runId: string
) => Promise<ChatRunDeliveryStatus>
