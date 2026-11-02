import type { RequestOptions } from '@skillforge/shared/utils/http'
import type {
  ObservationPage,
  TrajectoryContent,
  TrajectoryDetail,
  TrajectoryListPage,
  TrajectoryListQuery
} from '@skillforge/shared/observation/types'

export interface TrajectoryResource {
  tenant_id: string
  session_id: string
  root_input_id: string
}
export interface TrajectoryNodesQuery extends TrajectoryResource {
  cursor?: string
  snapshot?: string
  node_id?: string
  query?: string
  page_size?: number
}

function query(params: object) {
  const value = new URLSearchParams()
  Object.entries(params).forEach(([key, item]) => {
    if (item !== undefined && item !== null && item !== '') value.set(key, String(item))
  })
  return value.toString()
}

/** 平台跨机构定位始终显式携带目标机构；服务端逐资源重验管理员作用域。 */
export function createTrajectoryApi(get: <T>(path: string, opts?: RequestOptions) => Promise<T>) {
  return {
    list: (input: TrajectoryListQuery, opts?: RequestOptions) =>
      get<TrajectoryListPage>(`/platform/trajectories?${query(input)}`, opts),
    detail: (input: TrajectoryResource, opts?: RequestOptions) =>
      get<TrajectoryDetail>(`/platform/trajectories/detail?${query(input)}`, opts),
    nodes: (input: TrajectoryNodesQuery, opts?: RequestOptions) =>
      get<ObservationPage>(`/platform/trajectories/nodes?${query(input)}`, opts),
    content: (input: TrajectoryResource & { content_ref: string }, opts?: RequestOptions) =>
      get<TrajectoryContent>(`/platform/trajectories/content?${query(input)}`, opts)
  }
}
