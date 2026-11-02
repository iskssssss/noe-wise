import type { TeamTaskKind } from './teamWork'
export const CHAT_REFERENCE_KINDS = {
  ARTIFACT_FILES: 'artifact_files',
  DELIVERABLE_REVISION: 'deliverable_revision',
  MESSAGE: 'message',
  PROCESS: 'process'
} as const
export const CHAT_REFERENCE_SOURCES = { SESSION: 'session', WORK_TASK: 'work_task' } as const
export const CHAT_REVISION_PURPOSES = { INPUT: 'input', REVISION_BASE: 'revision_base' } as const
export const PROCESS_RESULT_KINDS = { TEXT: 'text', TABLE: 'table' } as const
export const PROCESS_TEXT_FORMATS = { PLAIN: 'plain', MARKDOWN: 'markdown' } as const
/** 服务端授权材料的结构化选择器；身份只用于请求，不进入用户正文。 */
export type ChatReferenceSource =
  | { kind: 'session' }
  | {
      kind: typeof CHAT_REFERENCE_SOURCES.WORK_TASK
      work_id: string
      task_kind: TeamTaskKind
      task_id: string
    }
export type ChatInputReference =
  | { kind: 'artifact_files'; files: Array<{ artifact_id: string; path: string }> }
  | { kind: 'deliverable_revision'; revision_id: string; purpose: 'input' | 'revision_base' }
  | { kind: 'message'; source: ChatReferenceSource; message_id: string; message_version: number }
  | { kind: 'process'; source: ChatReferenceSource; process_id: string; updated_sequence: number }
export interface ChatReferenceView {
  label: string
  source: ChatInputReference
}

export type ProcessResult =
  | { kind: 'text'; format: 'plain' | 'markdown'; text: string }
  | {
      kind: 'table'
      columns: Array<{ key: string; label: string }>
      rows: Array<Array<string | number | boolean | null>>
    }
export interface ProcessPresentation {
  title: string
  summary?: string | null
  results: ProcessResult[]
  references: ChatReferenceView[]
}
