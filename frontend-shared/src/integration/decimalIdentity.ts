/** 目录边界只接受精确十进制字符串，任何 JSON number 都违反当前身份契约。 */
export function catalogDecimalIdentity(value: unknown, field: string): string {
  if (typeof value === 'string' && /^[1-9]\d*$/.test(value)) return value
  throw new Error('能力标识无效，请刷新目录后重试', {
    cause: new TypeError(`${field} must be a decimal string`)
  })
}
