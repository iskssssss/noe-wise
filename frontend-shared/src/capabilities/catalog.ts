import type { CapabilityType } from './types'

export interface CapabilityDefinition {
  id: string
  tenantId: string | null
  capabilityType: CapabilityType
  capabilityKey: string
  name: string
  description: string
  revision: number
  status: 'DRAFT' | 'PUBLISHED' | 'ARCHIVED' | 'DEPRECATED'
  defaultVersionId?: string | null
}
export interface CapabilityVersionSummary {
  id: string
  versionKey: string
  name: string
  description: string
  status: 'DRAFT' | 'PUBLISHED' | 'ARCHIVED' | 'DEPRECATED'
}
export interface CapabilityVersion extends CapabilityVersionSummary {
  definitionId: string
  capabilityType: CapabilityType
  descriptor: Record<string, unknown>
}
export interface CapabilityCatalogError {
  code: string
  message: string
}
export interface CapabilityVersionInput {
  versionKey: string
  name: string
  description?: string
  descriptor: Record<string, unknown>
  contentHash: string
}
export interface CapabilityCatalogApi {
  listDefinitions(): Promise<CapabilityDefinition[]>
  getDefinition(id: string): Promise<CapabilityDefinition>
  createDefinition(input: {
    capabilityType: CapabilityType
    capabilityKey: string
    name: string
    description?: string
  }): Promise<CapabilityDefinition>
  listVersionSummaries(id: string): Promise<CapabilityVersionSummary[]>
  getVersion(id: string): Promise<CapabilityVersion>
  createDraftVersion(id: string, input: CapabilityVersionInput): Promise<CapabilityVersion>
  updateDraftVersion(
    id: string,
    version: string,
    revision: number,
    input: Omit<CapabilityVersionInput, 'versionKey'>
  ): Promise<CapabilityVersion>
  validateVersion(
    id: string,
    version: string
  ): Promise<{ valid: boolean; errors: CapabilityCatalogError[]; definitionRevision: number }>
  publishVersion(id: string, version: string, revision: number): Promise<unknown>
  setSkillDefault(id: string, version: string, revision: number): Promise<CapabilityDefinition>
  downloadSkill(version: string): Promise<Blob>
}
