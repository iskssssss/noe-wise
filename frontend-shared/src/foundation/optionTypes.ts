/** 管理动作专属的最小目录投影，身份始终保持字符串。 */
export interface FoundationOption {
  id: string
  name: string
  code: string
  status: string
}
export interface FoundationOptionPage {
  items: FoundationOption[]
  has_more: boolean
}
export type FoundationOptionRequest = <T>(method: 'GET', path: string) => Promise<T>
