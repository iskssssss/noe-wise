import { ref } from 'vue'
import { confirm } from '../feedback'
import { useUnsavedChanges } from './leave'

interface DrawerProtection {
  open: () => boolean
  busy: () => boolean
  dirty: () => boolean
  close: () => void
}

/** 直接绑定 ElDrawer 的 before-close；业务页面拥有显示状态与表单。 */
export function useDrawerClose(options: DrawerProtection) {
  const closing = ref(false)
  useUnsavedChanges({
    dirty: () => options.open() && options.dirty(),
    busy: () => options.open() && options.busy(),
    discard: options.close
  })
  async function requestClose() {
    if (!options.open() || options.busy() || closing.value) return
    closing.value = true
    try {
      if (
        options.dirty() &&
        !(await confirm({
          title: '关闭表单',
          content: '关闭后将丢弃未保存的内容。',
          confirmText: '丢弃并关闭',
          cancelText: '继续编辑'
        }))
      )
        return
      if (!options.busy()) options.close()
    } catch (reason) {
      if (reason !== 'cancel' && reason !== 'close') throw reason
    } finally {
      closing.value = false
    }
  }
  return { requestClose, closing }
}
