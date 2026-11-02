import type { WorkflowReplyIdentity } from '../../api/workflowTests'

export interface Recovery {
  requestId: string
  testSessionId?: string
  initial: 'not_sent' | 'sending' | 'submitted' | 'unknown'
  closing: boolean
  replyAttempts?: string[]
  replyOperations?: ReplyOperation[]
}
export interface ReplyOperation extends WorkflowReplyIdentity {
  waitKey: string
  runRevision: string
  accepted?: boolean
}
export function parseWorkflowTestRecovery(text: string): Recovery {
  const value: unknown = JSON.parse(text)
  if (!(
    value &&
    typeof value === 'object' &&
    'requestId' in value &&
    typeof value.requestId === 'string' &&
    'initial' in value &&
    ['not_sent', 'sending', 'submitted', 'unknown'].includes(String(value.initial))
  ))
    throw Error('invalid')
  const candidate = value as Recovery
  if (
    candidate.replyAttempts !== undefined &&
    (!Array.isArray(candidate.replyAttempts) ||
      candidate.replyAttempts.some((item) => typeof item !== 'string'))
  )
    throw Error('invalid')
  if (
    candidate.replyOperations !== undefined &&
    (!Array.isArray(candidate.replyOperations) ||
      candidate.replyOperations.some(
        (item) =>
          !item ||
          typeof item.waitKey !== 'string' ||
          typeof item.inputId !== 'string' ||
          typeof item.runId !== 'string' ||
          typeof item.testSessionId !== 'string' ||
          typeof item.sessionId !== 'string' ||
          typeof item.actionId !== 'string' ||
          !/^[1-9]\d*$/.test(item.generation) ||
          !['human', 'workflow'].includes(item.kind) ||
          typeof item.runRevision !== 'string' ||
          (item.accepted !== undefined && typeof item.accepted !== 'boolean') ||
          (item.kind === 'workflow' &&
            (!item.executionId ||
              !item.suspendRef ||
              !item.actionKey ||
              !/^[1-9]\d*$/.test(item.executionRevision ?? '')))
      ))
  )
    throw Error('invalid')
  return candidate
}

export function workflowTestRecoveryKey(session: string | null, workflow: string) {
  return 'noewise:workflow-test:' + JSON.stringify([session, workflow])
}
