import type { ChatMedia } from './chatMedia'
export type BrowserSelector =
  { client_ref: string; session_id?: never } | { session_id: string; client_ref?: never }
export interface BrowserDevice {
  device_id: string
  browser_id: string
  label: string
  expires_at: string
  connected: boolean
}
export interface BrowserDevices {
  items: BrowserDevice[]
  installation_url: string
  selected_browser_id?: string | null
}
export interface BrowserTask {
  receipt: {
    task: string
    invocation: string
    operation: string
    status: string
    session: string
    run: string
    created_at: string
    result?: unknown
    binding?: unknown
  }
  content: Array<ChatMedia & { session_id: string }>
}
export interface ChatBrowserClient {
  browserDevices(selector: BrowserSelector, signal?: AbortSignal): Promise<BrowserDevices>
  browserTasks(selector: BrowserSelector, signal?: AbortSignal): Promise<BrowserTask[]>
  browserPair(selector: BrowserSelector): Promise<{ pairing_url: string }>
  browserRename(selector: BrowserSelector, device: string, label: string): Promise<BrowserDevices>
  browserSelect(selector: BrowserSelector, device: string): Promise<BrowserDevices>
  browserCancel(selector: BrowserSelector, invocation: string): Promise<BrowserTask['receipt']>
  browserRevoke(selector: BrowserSelector, device: string): Promise<unknown>
  mediaContent(session: string, media: string, signal?: AbortSignal): Promise<Blob>
}
