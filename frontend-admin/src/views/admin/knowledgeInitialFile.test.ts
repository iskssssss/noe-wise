import { expect, it } from 'vitest'
import { peekInitialFile, prepareInitialFile, takeInitialFile } from './knowledgeInitialFile'

it('首个文件只交给原身份的新知识库一次', () => {
  const file = new File(['内容'], '文档.txt')
  prepareInitialFile('机构与用户', 1, '新知识库', file)
  expect(peekInitialFile('机构与用户', 1, '新知识库')).toBe(file)
  expect(peekInitialFile('其他身份', 1, '新知识库')).toBeUndefined()
  expect(takeInitialFile('机构与用户', 1, '其他知识库')).toBeUndefined()
  expect(takeInitialFile('机构与用户', 1, '新知识库')).toBe(file)
  expect(takeInitialFile('机构与用户', 1, '新知识库')).toBeUndefined()
  prepareInitialFile('机构与用户', 1, '新知识库', file)
  expect(takeInitialFile('其他身份', 2, '新知识库')).toBeUndefined()
  expect(takeInitialFile('机构与用户', 1, '新知识库')).toBeUndefined()
})
