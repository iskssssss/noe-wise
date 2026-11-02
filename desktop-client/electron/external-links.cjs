/** 仅允许默认浏览器可处理的显式 HTTP(S) 地址，不接受文件、命令协议或地址凭据。 */
function externalUrl(value) {
  if (typeof value !== 'string' || value.length > 2048 || /[\u0000-\u001f\u007f]/.test(value))
    throw new Error('外部链接格式无效')
  let url
  try {
    url = new URL(value)
  } catch {
    throw new Error('外部链接必须是完整的 HTTP(S) 地址')
  }
  if (!['https:', 'http:'].includes(url.protocol) || url.username || url.password)
    throw new Error('仅允许在默认浏览器打开不含凭据的 HTTP(S) 链接')
  return url.href
}
module.exports = { externalUrl }
