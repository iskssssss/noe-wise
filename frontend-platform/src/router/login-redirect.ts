import type { LocationQueryValue, Router } from 'vue-router'

/** 登录提交与已认证的登录页重入使用相同的站内目标规则。 */
export function loginRedirectTarget(
  router: Pick<Router, 'resolve'>,
  redirect: LocationQueryValue | LocationQueryValue[] | undefined
): string {
  const home = '/platform/home'
  if (typeof redirect !== 'string' || !redirect.startsWith('/platform/')) return home
  const target = router.resolve(redirect)
  return target.meta.requiresAuth ? target.fullPath : home
}
