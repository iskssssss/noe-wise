import type { CapabilityRequirementOption } from '@skillforge/shared/capabilities/types'
import type { CapabilityDeployment, CapabilityVersion } from '../../api'

export type WorkflowInstallationVersion = Pick<
  CapabilityVersion,
  'id' | 'definitionId' | 'capabilityType' | 'versionKey' | 'name' | 'descriptor'
>

export function toolInstallationVersions(
  options: CapabilityRequirementOption[],
  capabilityKey: string,
  constraint: string,
  matches: (constraint: string, version: string) => boolean
): WorkflowInstallationVersion[] {
  return options
    .filter((item) => item.capabilityKey === capabilityKey && matches(constraint, item.versionKey))
    .map((item) => ({
      id: item.versionId,
      definitionId: item.id,
      capabilityType: 'TOOL',
      versionKey: item.versionKey,
      name: item.versionName,
      descriptor: {}
    }))
}

export function uniqueToolDeployment(
  versionId: string | undefined,
  deployments: CapabilityDeployment[]
) {
  const matches = deployments.filter(
    (item) => item.toolVersionId === versionId && ['MCP', 'PLATFORM'].includes(item.providerType)
  )
  return matches.length === 1 ? matches[0]!.deploymentKey : undefined
}

export function defaultInstallationSlots<
  T extends { required?: boolean; type: string; versionId?: string; deploymentKey?: string }
>(
  slots: T[],
  candidates: (slot: T) => WorkflowInstallationVersion[],
  deployments: CapabilityDeployment[]
): T[] {
  return slots.map((slot) => {
    const versions = candidates(slot)
    if (
      slot.versionId &&
      slot.type === 'tool' &&
      !slot.deploymentKey &&
      versions.some((version) => version.id === slot.versionId)
    ) {
      const deploymentKey = uniqueToolDeployment(slot.versionId, deployments)
      return deploymentKey ? { ...slot, deploymentKey } : slot
    }
    if (slot.required === false || slot.versionId || versions.length !== 1) return slot
    const versionId = versions[0]!.id
    return {
      ...slot,
      versionId,
      deploymentKey: slot.type === 'tool' ? uniqueToolDeployment(versionId, deployments) : undefined
    }
  })
}
