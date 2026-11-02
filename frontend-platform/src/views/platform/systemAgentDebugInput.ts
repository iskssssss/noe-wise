import type { ExtractionInput } from '@skillforge/shared/memory/contracts'

/** 稳定的记忆用途输入示例；调试仍提交用户编辑后的原始JSON，不插入当前时间或自动改写内容。 */
export const memoryExtractionDebugExample = JSON.stringify(
  {
    schemaVersion: 2,
    userId: 7,
    sources: [
      { id: 's0', text: '请记住，我喜欢通过电子邮件联系。', occurredAt: '2026-01-01T00:00:00Z' }
    ],
    existingFacts: []
  } satisfies ExtractionInput,
  null,
  2
)
