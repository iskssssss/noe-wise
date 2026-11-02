/** 宿主按语言提供业务文案，后台和聊天端各自装配 i18n。 */
export type MessageNode = string | { [key: string]: MessageNode }
export type MessageRecord = Record<string, MessageNode>
export type HostMessages = Record<string, MessageRecord>

export { commonZhCN } from './common.zh-CN'
