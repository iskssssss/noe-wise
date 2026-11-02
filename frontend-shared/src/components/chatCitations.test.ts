import { describe, expect, it } from 'vitest'
import { chatCitations } from './chatCitations'

describe('公开知识依据标记', () => {
  it('保留实际依据身份但不把私有材料附加为展示字段', () => {
    expect(
      chatCitations([
        {
          ordinal: 1,
          item_id: 'actual-item',
          source_id: 'actual-source',
          source_version: '7',
          label: '知识依据 1',
          citation_id: 'k0123456789abcdef',
          result_ref: 'private-material',
          provenance_refs: ['https://private.example']
        }
      ])
    ).toEqual([
      {
        ordinal: 1,
        item_id: 'actual-item',
        source_id: 'actual-source',
        source_version: '7',
        label: '知识依据 1',
        citation_id: 'k0123456789abcdef'
      }
    ])
  })
  it('缺失或错误身份不成为有效可展示依据', () => {
    expect(chatCitations(null)).toEqual([])
    expect(
      chatCitations([
        { ordinal: 0, label: '伪造依据' },
        { ordinal: 1, item_id: 'item', source_version: 7 },
        {
          ordinal: 1,
          item_id: 'item',
          source_id: 'source',
          source_version: '7',
          label: '恶意锚点',
          citation_id: 'https://private.example/source'
        }
      ])
    ).toEqual([])
  })
})
