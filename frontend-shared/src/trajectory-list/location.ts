import type { LocationQuery, LocationQueryRaw } from 'vue-router'
import type { TrajectoryListQuery } from '../observation/types'

const fields = [
  'tenant_id',
  'trace_id',
  'session_id',
  'agent_id',
  'model',
  'tool',
  'status',
  'started_from',
  'started_to',
  'duration_min_ms',
  'duration_max_ms',
  'input_keyword',
  'output_keyword'
] as const

// 轨迹列表上下文独立于详情资源身份和会话列表条件。
export const trajectoryListQueryKeys = [
  ...fields.map((field) => `trajectory_${field}`),
  'trajectory_page',
  'trajectory_size',
  'trajectory_recent'
]

export function readTrajectoryListQuery(query: LocationQuery): TrajectoryListQuery | undefined {
  if (!trajectoryListQueryKeys.some((key) => query[key] !== undefined)) return undefined
  const page = Number(query.trajectory_page)
  const size = Number(query.trajectory_size)
  const result: TrajectoryListQuery = {
    page: Number.isSafeInteger(page) && page > 0 ? page : 1,
    page_size: size === 50 || size === 100 ? size : 20
  }
  for (const field of fields) {
    const value = query[`trajectory_${field}`]
    result[field] = typeof value === 'string' ? value : ''
  }
  return result
}

export function trajectoryListQuery(query?: TrajectoryListQuery, recent = false): LocationQueryRaw {
  if (!query) return {}
  const result: LocationQueryRaw = {
    trajectory_page: String(query.page),
    trajectory_size: String(query.page_size),
    trajectory_recent: recent ? 'true' : undefined
  }
  for (const field of fields) result[`trajectory_${field}`] = query[field] || undefined
  return result
}
