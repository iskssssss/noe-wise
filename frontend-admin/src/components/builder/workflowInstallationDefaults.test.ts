import { describe, expect, it } from 'vitest'
import type { CapabilityDeployment, CapabilityVersion } from '../../api'
import {
  defaultInstallationSlots,
  toolInstallationVersions,
  uniqueToolDeployment
} from './workflowInstallationDefaults'
import { matchesWorkflowVersion } from '../../utils/workflow'

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

it('工具摘要按能力键和版本约束筛选，部署只匹配精确版本', () => {
  const options = [
    {
      id: '10',
      capabilityKey: 'lookup',
      versionId: '101',
      versionKey: '1.2.0',
      versionName: '发布版本名称',
      name: '能力名称',
      description: '',
      skillEstimatedTokens: null
    },
    {
      id: '11',
      capabilityKey: 'lookup',
      versionId: '102',
      versionKey: '2.0.0',
      versionName: '其他版本',
      name: '能力名称',
      description: '',
      skillEstimatedTokens: null
    },
    {
      id: '12',
      capabilityKey: 'other',
      versionId: '103',
      versionKey: '1.2.0',
      versionName: '无关版本',
      name: '其他',
      description: '',
      skillEstimatedTokens: null
    }
  ]
  const versions = toolInstallationVersions(options, 'lookup', '1.x', matchesWorkflowVersion)
  expect(versions).toEqual([
    {
      id: '101',
      definitionId: '10',
      capabilityType: 'TOOL',
      versionKey: '1.2.0',
      name: '发布版本名称',
      descriptor: {}
    }
  ])
  const deployments = [
    { deploymentKey: 'wrong-version', toolVersionId: '102', providerType: 'MCP' },
    { deploymentKey: 'right-version', toolVersionId: '101', providerType: 'PLATFORM' }
  ] as CapabilityDeployment[]
  expect(uniqueToolDeployment(versions[0]?.id, deployments)).toBe('right-version')
})
