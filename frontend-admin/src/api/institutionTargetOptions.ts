import type { EntityOption } from '@skillforge/shared/admin'
import { api } from './index'
import type { InstitutionTargetOperation } from './institutionManagement'

/** 搜索整个动作目标闭包，已选项按精确标识单独解析，不依赖目录读取权。 */
export function createInstitutionTargetOptions(
  operation: InstitutionTargetOperation,
  requireCurrent: () => void
) {
  async function request(query: string, page: number, signal?: AbortSignal, id?: string) {
    requireCurrent()
    const result = await api.institutionManagement.targetOptions(
      operation,
      { q: query, page, page_size: 200, ...(id ? { id } : {}) },
      { signal, silent: true }
    )
    requireCurrent()
    return result
  }
  return {
    async load(query: string, signal?: AbortSignal): Promise<EntityOption[]> {
      const options: EntityOption[] = []
      for (let page = 1; ; page++) {
        const result = await request(query, page, signal)
        const next = result.items.filter((item) => !options.some((option) => option.id === item.id))
        if (!next.length && options.length < result.total) throw Error('机构候选已变化，请重新搜索')
        options.push(...next)
        if (options.length >= result.total) return options
      }
    },
    async resolve(id: string): Promise<EntityOption | undefined> {
      return (await request('', 1, undefined, id)).items.find((item) => item.id === id)
    }
  }
}
