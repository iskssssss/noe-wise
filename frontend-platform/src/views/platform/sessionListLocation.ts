import type { LocationQuery, LocationQueryRaw } from 'vue-router'
import type { SessionListQuery } from '../../api'

const filterFields = [
  'tenant_id',
  'session_id',
  'user_id',
  'agent_id',
  'team_id',
  'channel',
  'status',
  'summary',
  'started_from',
  'started_to'
] as const

// 列表条件与详情的机构、会话、轮次身份分开，返回只携带列表自己的字段。
export const sessionListQueryKeys = [
  ...filterFields.map((field) => `list_${field}`),
  'page',
  'size'
]

export function readSessionListQuery(query: LocationQuery): SessionListQuery {
  const page = typeof query.page === 'string' ? Number(query.page) : 1
  const size = typeof query.size === 'string' ? Number(query.size) : 20
  const result: SessionListQuery = {
    page: Number.isSafeInteger(page) && page > 0 ? page : 1,
    page_size: size === 50 || size === 100 ? size : 20
  }
  for (const field of filterFields) {
    const value = query[`list_${field}`]
    result[field] = typeof value === 'string' ? value : ''
  }
  return result
}

export function sessionListQuery(query: SessionListQuery): LocationQueryRaw {
  const result: LocationQueryRaw = {
    page: query.page === 1 ? undefined : String(query.page),
    size: query.page_size === 20 ? undefined : String(query.page_size)
  }
  for (const field of filterFields) result[`list_${field}`] = query[field] || undefined
  return result
}
