import { watch, type Directive, type Ref, type WatchStopHandle } from 'vue'

/** 宿主提供当前动作权限；未认证或尚未载入时为空，不从角色名称推断权限。 */
export type PermissionGetter = () => Ref<readonly string[] | undefined>

/** 脱离DOM的受控节点仍保留Vue事件和表单状态，权限恢复后放回原有锚点。 */
interface PermissionNode {
  marker: Comment
  permissions: Ref<readonly string[] | undefined>
  required: readonly string[]
  all: boolean
  stop: WatchStopHandle
}

/**
 * 按当前动作权限控制按钮。绑定数组默认表示任一动作可见；.all要求全部动作，空数组或旧角色编码一律拒绝。
 * 撤权时移除DOM并保留锚点，恢复权限及动态绑定变更可以重新显示；卸载释放watch和锚点。
 */
export function createPermissionDirective(
  getPermissions: PermissionGetter
): Directive<HTMLElement, string[]> {
  const nodes = new WeakMap<HTMLElement, PermissionNode>()

  /** 可见性仅取决于显式注册动作，不使用admin等角色万能放行。 */
  function apply(el: HTMLElement, node: PermissionNode) {
    const visible =
      node.required.length > 0 &&
      node.required.every((code) => /^(foundation|platform|tenant)\./.test(code)) &&
      (node.all
        ? node.required.every((code) => node.permissions.value?.includes(code))
        : node.required.some((code) => node.permissions.value?.includes(code)))
    if (visible) {
      if (!el.parentNode && node.marker.parentNode)
        node.marker.parentNode.insertBefore(el, node.marker.nextSibling)
    } else if (el.parentNode) {
      if (!node.marker.parentNode) el.parentNode.insertBefore(node.marker, el)
      el.remove()
    }
  }

  return {
    mounted(el, binding) {
      const permissions = getPermissions()
      const node: PermissionNode = {
        marker: document.createComment('permission'),
        permissions,
        required: binding.value ?? [],
        all: Boolean(binding.modifiers.all),
        stop: () => undefined
      }
      nodes.set(el, node)
      node.stop = watch(permissions, () => apply(el, node), { deep: true })
      apply(el, node)
    },
    updated(el, binding) {
      const node = nodes.get(el)
      if (!node) return
      node.required = binding.value ?? []
      node.all = Boolean(binding.modifiers.all)
      apply(el, node)
    },
    unmounted(el) {
      const node = nodes.get(el)
      node?.stop()
      node?.marker.remove()
      nodes.delete(el)
    }
  }
}
