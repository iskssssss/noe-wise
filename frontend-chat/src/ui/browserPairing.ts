/** 配对地址由用户粘贴到官方扩展，不作为网页链接打开。 */
export function browserPairingAddress(value: string) {
  const url = new URL(value)
  const loopback = ['127.0.0.1', 'localhost', '[::1]'].includes(url.hostname)
  if (
    !['wss:', 'ws:'].includes(url.protocol) ||
    (url.protocol === 'ws:' && !loopback) ||
    url.username ||
    url.password
  ) {
    throw new Error('服务返回的浏览器配对地址无效。')
  }
  return url.href
}
