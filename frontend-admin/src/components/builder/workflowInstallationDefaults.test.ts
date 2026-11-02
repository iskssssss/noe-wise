import { describe, expect, it } from 'vitest'
import type { CapabilityDeployment, CapabilityVersion } from '../../api'
import { defaultInstallationSlots } from './workflowInstallationDefaults'

describe('工作流安装的默认实例', () => {
  const version = { id: 'v1' } as CapabilityVersion
  const deployment = (key: string) =>
    ({ deploymentKey: key, toolVersionId: 'v1', providerType: 'MCP' }) as CapabilityDeployment
  it('只为已选择的有效版本补全唯一实例，保留明确选择', () => {
    const candidates = () => [version]
    type Slot = { type: string; versionId?: string; deploymentKey?: string; required?: boolean }
    expect(
      defaultInstallationSlots<Slot>([{ type: 'tool', versionId: 'v1' }], candidates, [
        deployment('a')
      ])[0]?.deploymentKey
    ).toBe('a')
    expect(
      defaultInstallationSlots<Slot>([{ type: 'tool', versionId: 'v1' }], candidates, [
        deployment('a'),
        deployment('b')
      ])[0]?.deploymentKey
    ).toBeUndefined()
    expect(
      defaultInstallationSlots(
        [{ type: 'tool', versionId: 'v1', deploymentKey: 'old' }],
        candidates,
        [deployment('a')]
      )[0]?.deploymentKey
    ).toBe('old')
    expect(
      defaultInstallationSlots<Slot>([{ type: 'tool', versionId: 'old' }], candidates, [
        deployment('a')
      ])[0]?.deploymentKey
    ).toBeUndefined()
    expect(
      defaultInstallationSlots<Slot>([{ type: 'tool', required: false }], candidates, [
        deployment('a')
      ])[0]?.versionId
    ).toBeUndefined()
  })
})
