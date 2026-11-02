import type { ObjectDirective } from 'vue'

const targets =
  'input:not([type="hidden"]), textarea, select, button, [role="combobox"], [role="switch"], [role="checkbox"], [role="radio"], [role="slider"], [tabindex]:not([tabindex="-1"])'
const additions = new WeakMap<HTMLElement, Map<Element, string[]>>()

function clear(root: HTMLElement) {
  for (const [element, ids] of additions.get(root) ?? []) {
    const remaining = (element.getAttribute('aria-describedby') ?? '')
      .split(/\s+/)
      .filter((id) => id && !ids.includes(id))
    if (remaining.length) element.setAttribute('aria-describedby', remaining.join(' '))
    else element.removeAttribute('aria-describedby')
  }
  additions.delete(root)
}

function describe(root: HTMLElement, value: string | undefined) {
  clear(root)
  const ids = (value ?? '').split(/\s+/).filter(Boolean)
  const added = new Map<Element, string[]>()
  const elements = [...root.querySelectorAll(targets)]
  if (root.matches(targets)) elements.push(root)
  for (const element of elements) {
    const existing = (element.getAttribute('aria-describedby') ?? '').split(/\s+/).filter(Boolean)
    const missing = ids.filter((id) => !existing.includes(id))
    if (!missing.length) continue
    element.setAttribute('aria-describedby', [...existing, ...missing].join(' '))
    added.set(element, missing)
  }
  additions.set(root, added)
}

/** 关联真实可聚焦控件；保留原有帮助，不包装或改变 Element Plus 的交互。 */
export const vDescribedBy: ObjectDirective<HTMLElement, string | undefined> = {
  mounted: (root, binding) => describe(root, binding.value),
  updated: (root, binding) => describe(root, binding.value),
  unmounted: clear
}
