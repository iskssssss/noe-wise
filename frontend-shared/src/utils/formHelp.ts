/** 动态字段的帮助来自字段契约；这里只组织展示，不参与校验或额度派生。 */
export function schemaFieldHelp(
  schema: {
    description?: string
    type?: string
    enum?: unknown[]
    minimum?: number
    maximum?: number
    minLength?: number
    maxLength?: number
  },
  required: boolean
) {
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
  if (schema.minimum !== undefined) parts.push(`最小值 ${schema.minimum}。`)
  if (schema.maximum !== undefined) parts.push(`最大值 ${schema.maximum}。`)
  if (schema.minLength !== undefined) parts.push(`至少 ${schema.minLength} 个字符。`)
  if (schema.maxLength !== undefined) parts.push(`最多 ${schema.maxLength} 个字符。`)
  return parts.filter(Boolean).join(' ')
}

export function budgetOverrideHelp(key: string, owner: 'agent' | 'template' = 'agent') {
  const meanings: Record<string, string> = {
    max_business_model_calls: '业务模型调用次数；辅助模型独立计次。',
    max_tool_calls: '工具调用次数。',
    max_knowledge_calls: '知识检索次数。',
    max_total_tokens: '累计输入与输出的总 Tokens；属于资源额度，不等同于精确货币成本。',
    max_wall_time_seconds: '活跃执行时长，单位为秒；暂停期间不计时。'
  }
  const effective =
    owner === 'template'
      ? '模板发布后用于后续安装；已安装 Agent 的有效额度以其配置和 Run 冻结结果为准。'
      : '发布后对新 Run 生效，进行中的 Run 保持原冻结额度。'
  return `${meanings[key] ?? ''} 留空继承场景和角色默认额度；填写正整数可提高或降低本 Agent 额度，但不能超过任务根额度。${effective}`
}

export function knowledgeLimitHelp(key: string) {
  const help: Record<string, string> = {
    knowledgeMaxItems: '本节点知识查询最多返回的结果条数。',
    knowledgeMaxTokens: '本节点知识查询结果的 Token 资源上限。',
    knowledgeMaxCharacters: '本节点知识查询结果的字符资源上限。',
    knowledgeTimeoutMs: '本节点知识查询的超时时间，单位为毫秒。',
    knowledgeMaxSources: '本节点知识查询最多涉及的来源数量。'
  }
  return help[key]
}
