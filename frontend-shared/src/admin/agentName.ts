import type { FormItemRule } from 'element-plus'

export const AGENT_NAME_MAX_LENGTH = 128

export const agentNameLengthRule: FormItemRule = {
  trigger: ['blur', 'change'],
  validator: (_rule, value: string, callback) => {
    callback(
      Array.from(value).length > AGENT_NAME_MAX_LENGTH
        ? new Error(`名称最多 ${AGENT_NAME_MAX_LENGTH} 个字符`)
        : undefined
    )
  }
}
