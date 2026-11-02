import './style.css'
export { default as AdminLayout } from './AdminLayout.vue'
export { default as AdminPage } from './AdminPage.vue'
export { default as AgentConfigFields } from './AgentConfigFields.vue'
export { useTask, useResource } from './tasks'
export { useUnsavedChanges, confirmPageLeave } from './leave'
export { useDrawerClose } from './drawer'
export type { AdminMenu } from './types'

export function initializeAdminTheme() {
  document.documentElement.classList.toggle(
    'dark',
    window.matchMedia('(prefers-color-scheme: dark)').matches
  )
}

export { default as EntitySelect } from './EntitySelect.vue'
export { default as CapabilityRequirementFields } from './CapabilityRequirementFields.vue'
export { statusLabel } from './presentation'
export type { EntityOption } from './presentation'

export { guardFormEnter } from './keyboard'

export type { CapabilityRequirementOption } from '../capabilities/types'
