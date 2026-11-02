import { describe, expect, it } from 'vitest'
import { buildCurl } from './examples'
import { serviceEndpoints } from './services'

describe('知识文档列表接口说明', () => {
  const documents = serviceEndpoints.find(
    (endpoint) => endpoint.id === 'service-knowledge-documents'
  )!

  it('参数表使用实际分页与标题字段，说明当前版本的字面匹配范围', () => {
    expect(documents.method).toBe('GET')
    expect(documents.path).toBe('/api/v1/knowledge/bases/documents')
    expect(documents.parameters?.map((parameter) => parameter.name)).toEqual([
      'kbId',
      'page',
      'page_size',
      'status',
      'titleQuery'
    ])
    const title = documents.parameters!.find((parameter) => parameter.name === 'titleQuery')!
    expect(title.required).not.toBe(true)
    expect(title.description).toContain('当前文档标题')
    expect(title.description).toContain('128 个 Unicode 码点')
    expect(title.description).toContain('字面包含')
    expect(title.description).toContain('与状态组合筛选后计算 total 并分页')
  })

  it('实际 cURL 示例使用 page_size 与 titleQuery，不生成 query 对象或旧分页名', () => {
    const curl = buildCurl(documents, 'https://example.invalid')
    const firstLine = curl.split('\n')[0]!
    const url = new URL(firstLine.match(/^curl -X GET '([^']+)'/)![1]!)
    expect(url.pathname).toBe('/api/v1/knowledge/bases/documents')
    expect(url.searchParams.get('kbId')).toBe('YOUR_KB_ID')
    expect(url.searchParams.get('page')).toBe('1')
    expect(url.searchParams.get('page_size')).toBe('20')
    expect(url.searchParams.get('status')).toBe('ready')
    expect(url.searchParams.get('titleQuery')).toBe('报告')
    expect(url.searchParams.has('pageSize')).toBe(false)
    expect(url.searchParams.has('query')).toBe(false)
  })

  it('cURL 以 URL 编码保留标题中的字面符号，不将其改写为通配查询', () => {
    const literal = '预算%_\\文档'
    const curl = buildCurl(
      {
        ...documents,
        queryExample: { ...documents.queryExample, page_size: 50, titleQuery: literal }
      },
      'https://example.invalid'
    )
    const url = new URL(curl.split('\n')[0]!.match(/^curl -X GET '([^']+)'/)![1]!)
    expect(url.searchParams.get('page_size')).toBe('50')
    expect(url.searchParams.get('titleQuery')).toBe(literal)
    expect(curl).toContain('%25_%5C')
  })
})
