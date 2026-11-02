import type { RequestOptions } from '@skillforge/shared/utils/http'
import type {
  ObservationPage,
  TrajectoryContent,
  TrajectoryDetail,
  TrajectoryListPage,
  TrajectoryListQuery
} from '@skillforge/shared/observation/types'

export interface TrajectoryResource {
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

/** 机构轨迹资源只携带业务定位键；租户身份由已认证的服务端作用域确定。 */
export function createTrajectoryApi(get: <T>(path: string, opts?: RequestOptions) => Promise<T>) {
  return {
    list: (input: TrajectoryListQuery, opts?: RequestOptions) =>
      get<TrajectoryListPage>(`/chat/trajectories?${query(input)}`, opts),
    detail: (input: TrajectoryResource, opts?: RequestOptions) =>
      get<TrajectoryDetail>(`/chat/trajectories/detail?${query(input)}`, opts),
    nodes: (input: TrajectoryNodesQuery, opts?: RequestOptions) =>
      get<ObservationPage>(`/chat/trajectories/nodes?${query(input)}`, opts),
    content: (input: TrajectoryResource & { content_ref: string }, opts?: RequestOptions) =>
      get<TrajectoryContent>(`/chat/trajectories/content?${query(input)}`, opts)
  }
}
