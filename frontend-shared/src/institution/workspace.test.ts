import { describe, expect, it } from 'vitest'
import {
  buildInstitutionTree,
  filterInstitutionTree,
  institutionAncestors,
  type InstitutionNode
} from './workspace'

const nodes: InstitutionNode[] = [
  { id: '1', label: '总部' },
  { id: '2', label: '西区机构', parentId: '1' },
  { id: '3', label: '直属机构', parentId: '2' },
  { id: '4', label: '获准机构', parentId: 'hidden' }
]

describe('机构工作台可见目录', () => {
  it('只组装调用方给出的机构，缺少可见上级时呈现独立可见根', () => {
    const tree = buildInstitutionTree(nodes)
    expect(tree.map((node) => node.id)).toEqual(['1', '4'])
    expect(tree[0]?.children[0]?.children[0]?.id).toBe('3')
    expect(tree[1]?.parentId).toBe('hidden')
    expect(nodes[0]).not.toHaveProperty('children')
  })

  it('名称或精确标识搜索保留可见祖先，不补取不可见上级', () => {
    const filtered = filterInstitutionTree(buildInstitutionTree(nodes), '直属')
    expect(filtered[0]?.id).toBe('1')
    expect(filtered[0]?.children[0]?.children[0]?.id).toBe('3')
    expect(filterInstitutionTree(buildInstitutionTree(nodes), '4').map((node) => node.id)).toEqual([
      '4'
    ])
    expect(filterInstitutionTree(buildInstitutionTree(nodes), '不存在')).toEqual([])
  })

  it('当前机构路径只包含实际提供的可见祖先', () => {
    expect(institutionAncestors(nodes, '3')).toEqual(['1', '2'])
    expect(institutionAncestors(nodes, '4')).toEqual([])
    expect(institutionAncestors(nodes, 'not-loaded')).toEqual([])
  })

  it('千级目录不截断，后部机构和长ID保持字符串身份', () => {
    const large = Array.from({ length: 1200 }, (_, index) => ({
      id: `92233720368547${String(index).padStart(5, '0')}`,
      label: `机构 ${index}`
    }))
    const tree = buildInstitutionTree(large)
    expect(tree).toHaveLength(1200)
    expect(filterInstitutionTree(tree, '机构 1199')[0]?.id).toBe(large[1199]?.id)
  })

  it('重复身份或循环关系明确报错，不丢弃异常后伪造正常目录', () => {
    expect(() =>
      buildInstitutionTree([
        { id: '1', label: '甲' },
        { id: '1', label: '乙' }
      ])
    ).toThrow('重复')
    expect(() =>
      buildInstitutionTree([
        { id: '1', label: '甲', parentId: '2' },
        { id: '2', label: '乙', parentId: '1' }
      ])
    ).toThrow('循环')
  })
})
