import { describe, expect, it } from 'vitest'
import { memoryContentText, parseMemoryContent, type MemoryContent } from './contracts'

const content: MemoryContent = {
  value: { text: '晚上通过企业微信联系', channels: ['workchat'] },
  conditions: ['工作日'],
  temporal: { kind: 'stable', from: null, to: null },
  revisionProposal: { factId: 'fact/original', expectedRevision: 7 }
}

describe('记忆 v2 展示边界', () => {
  it('将开放正文与固定治理结构解析为类型化正文，展示只排除修订控制字段', () => {
    const original = JSON.stringify(content)
    const parsed = parseMemoryContent(original)
    expect(parsed).toEqual(content)
    if (!parsed) throw Error('有效正文解析失败')
    expect(JSON.parse(memoryContentText(parsed))).toEqual({
      value: content.value,
      conditions: content.conditions,
      temporal: content.temporal
    })
    expect(original).toContain('revisionProposal')
  })

  it('没有提案的合法正文不补造修订目标', () => {
    const parsed = parseMemoryContent(
      JSON.stringify({ value: '晚上联系', conditions: [], temporal: content.temporal })
    )
    expect(parsed?.value).toBe('晚上联系')
    expect(parsed?.revisionProposal).toBeUndefined()
  })

  it.each([
    '{',
    'null',
    '{"value":"部分正文"}',
    JSON.stringify({ ...content, conditions: [7] }),
    JSON.stringify({ ...content, temporal: { kind: 'other', from: null, to: null } }),
    JSON.stringify({
      ...content,
      revisionProposal: { factId: 'fact/original', expectedRevision: 0 }
    })
  ])('未知边界输入不伪装成有效 v2 正文：%s', (raw) => {
    expect(parseMemoryContent(raw)).toBeUndefined()
  })
})
