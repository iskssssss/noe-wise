/** 能力目录与挂载使用同一组封闭类型。 */
export type CapabilityType = 'SKILL' | 'TOOL' | 'WORKFLOW' | 'KNOWLEDGE_SOURCE'
export type SkillActivationMode = 'ALWAYS' | 'ON_DEMAND'

/** 依赖目录绑定精确当前发布，和原SkillRequirement写入身份分开。 */
export interface CapabilityRequirementOption {
  id: string
  name: string
  versionId: string
  versionKey: string
}
