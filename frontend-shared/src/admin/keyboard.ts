/** 输入法确认候选词不触发查询或提交，多行输入沿用原生换行。 */
export function guardFormEnter(event: KeyboardEvent) {
  if (
    (event.key === 'Enter' || event.code === 'Enter' || event.code === 'NumpadEnter') &&
    (event.isComposing || event.keyCode === 229)
  ) {
    event.preventDefault()
    event.stopPropagation()
  }
}
