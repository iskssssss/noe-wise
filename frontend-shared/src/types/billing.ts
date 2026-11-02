/** 金额为精确十进制字符串，账单金额单位为元。 */
export type MediaPriceUnit =
  | 'OUTPUT_IMAGE'
  | 'OUTPUT_VIDEO'
  | 'VIDEO_SECOND'
  | 'INPUT_AUDIO_SECOND'
  | 'OUTPUT_AUDIO_SECOND'
  | 'BILLING_CHARACTER'
  | 'TOKENS'
export interface ModelBillingLine {
  id: string
  event_type: string
  provider: string | null
  model: string | null
  occurred_at: string | null
  invocation_id: string | null
  attempt_id: string | null
  session_id: string | null
  run_id: string | null
  quantity: string | null
  prompt_tokens: string | null
  completion_tokens: string | null
  image_count: string | null
  video_count: string | null
  characters: string | null
  duration_seconds: string | null
  pricing_snapshot_version: string | null
  pricing_model_id: string | null
  pricing_model_revision: number | null
  original_amount: string | null
  original_currency: string | null
  amount: string | null
  cost_status: string
}
export interface ModelBillingMonthly {
  month: string
  currency: 'CNY'
  model_calls: number
  known_amount: string
  total_amount: string | null
  missing_cost_count: number
  prompt_tokens: string | null
  completion_tokens: string | null
  total: number
  page: number
  page_size: number
  items: ModelBillingLine[]
}
