import { describe, expect, it } from 'vitest'
import { formatJsonText } from './jsonFormatting'

describe('无损 JSON 格式化', () => {
  it('保留超大整数、小数精度、指数、负零、重复键与字符串转义词法', () => {
    const source = String.raw` {"id":900719925474099312345,"decimal":0.10000000000000001,"exponent":1e+400,"zero":-0,"same":1,"same":2,"text":"a\\b\n\u0061 [x], :"} `
    expect(formatJsonText(source)).toBe(String.raw`{
  "id": 900719925474099312345,
  "decimal": 0.10000000000000001,
  "exponent": 1e+400,
  "zero": -0,
  "same": 1,
  "same": 2,
  "text": "a\\b\n\u0061 [x], :"
}`)
    expect(source.startsWith(' ')).toBe(true)
    expect(source.endsWith(' ')).toBe(true)
  })

  it('格式化嵌套对象与数组，空容器保持紧凑', () => {
    expect(formatJsonText('{"items":[{"name":"一"},{}],"empty":[]}')).toBe(`{
  "items": [
    {
      "name": "一"
    },
    {}
  ],
  "empty": []
}`)
  })

  it.each(['null', 'true', 'false', '1.2300e+4', '"字面量  \\u0061"'])(
    '识别有效 JSON 原始值 %s',
    (source) => {
      expect(formatJsonText(' \r\n' + source + '\t')).toBe(source)
    }
  )

  it.each([
    '',
    '普通正文\n保留下一行',
    '{"id":01}',
    '{"id":NaN}',
    '{"id":1,}',
    '[1,]',
    '{"a":1}{"b":2}',
    '{"a":"未结束}',
    '{/*说明*/"id":1}'
  ])('无效 JSON 或普通文本交由原文展示 %s', (source) => {
    expect(formatJsonText(source)).toBeNull()
  })

  it('不按长度或嵌套深度截断内容', () => {
    const source = '['.repeat(300) + '900719925474099312345' + ']'.repeat(300)
    const formatted = formatJsonText(source)!
    expect(formatted.replace(/\s/g, '')).toBe(source)
    expect(formatted.split('\n')).toHaveLength(601)
  })
})
