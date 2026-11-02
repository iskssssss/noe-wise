/** Element Plus 在新增候选未高亮时不会响应 Enter；只接纳明确允许创建的精确新值。 */
export function acceptUnhighlightedCreate(
  event: KeyboardEvent,
  allowCreate: boolean,
  existingLabels: string[],
  accept: (value: string) => void
) {
  const input = event.target
  if (
    event.isComposing ||
    event.keyCode === 229 ||
    !allowCreate ||
    !(input instanceof HTMLInputElement) ||
    input.getAttribute('role') !== 'combobox' ||
    input.getAttribute('aria-expanded') !== 'true' ||
    input.getAttribute('aria-activedescendant') ||
    !input.value.trim() ||
    existingLabels.includes(input.value)
  )
    return
  event.preventDefault()
  event.stopPropagation()
  accept(input.value)
  // 关闭旧候选后将焦点还给选择框，便于键盘继续操作。
  input.blur()
  input.focus()
}
