import { describe, expect, it } from 'vitest'
import { budgetOverrideHelp, schemaFieldHelp } from './formHelp'

describe('schema 字段说明', () => {
  it('业务调用覆盖使用明确名称，并保留继承和冻结来源说明', () => {
    const help = budgetOverrideHelp('max_business_model_calls')
    expect(help).toContain('业务模型调用次数')
    expect(help).toContain('留空继承场景和角色默认额度')
    expect(help).toContain('进行中的 Run 保持原冻结额度')
    expect(budgetOverrideHelp('max_business_model_calls', 'template')).toContain('后续安装')
  })
  it('没有 description 的动态字段仍显示类型、必填及零值边界', () => {
    const help = schemaFieldHelp({ type: 'integer', minimum: 0, maximum: 10 }, true)
    expect(help).toContain('必填')
    expect(help).toContain('整数')
    expect(help).toContain('最小值 0')
    expect(help).toContain('最大值 10')
  })
  it('保留提供方说明并显示文本长度及可选语义', () => {
    const help = schemaFieldHelp(
      { description: '联系邮箱', type: 'string', minLength: 1, maxLength: 80 },
      false
    )
    expect(help).toContain('联系邮箱')
    expect(help).toContain('选填，可留空')
    expect(help).toContain('至少 1 个字符')
    expect(help).toContain('最多 80 个字符')
  })
  it('布尔输入和枚举不承诺不具备的清空操作', () => {
    expect(schemaFieldHelp({ type: 'boolean' }, false)).not.toContain('留空')
    expect(schemaFieldHelp({ type: 'string', enum: ['a', 'b'] }, true)).toContain(
      '从提供的选项中选择'
    )
  })
})
