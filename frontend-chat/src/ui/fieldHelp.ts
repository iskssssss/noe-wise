import type { Schema } from '../protocol'

/** 会话端独立呈现服务端请求的字段说明和约束，不依赖后台实现。 */
export function schemaFieldHelp(schema: Schema, required: boolean) {
  const parts = [schema.description?.trim()]
  const types: Record<string, string> = {
    integer: '整数',
    number: '数值',
    boolean: '是或否',
    string: '文本',
    object: 'JSON 对象',
    array: 'JSON 数组'
  }
  parts.push(required ? '必填。' : schema.type === 'boolean' ? '选填。' : '选填，可留空。')
  if (schema.type) parts.push(`填写${types[schema.type] ?? schema.type}。`)
  if (schema.enum) parts.push('从提供的选项中选择。')
  if (schema.default !== undefined && schema.default !== null && schema.format !== 'password')
    parts.push(`默认值：${String(schema.default)}。`)
  else if (!required) parts.push('未填写的字段不提交。')
  if (schema.minimum !== undefined) parts.push(`最小值 ${schema.minimum}。`)
  if (schema.maximum !== undefined) parts.push(`最大值 ${schema.maximum}。`)
  if (schema.minLength !== undefined) parts.push(`至少 ${schema.minLength} 个字符。`)
  if (schema.maxLength !== undefined) parts.push(`最多 ${schema.maxLength} 个字符。`)
  return parts.filter(Boolean).join(' ')
}
