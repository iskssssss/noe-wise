import type { ModelKind, ModelMediaPriceUnit } from '../../api'

export const mediaPriceUnits: Partial<Record<ModelKind, ModelMediaPriceUnit[]>> = {
  IMAGE_GENERATION: ['OUTPUT_IMAGE', 'TOKENS'],
  VIDEO_GENERATION: ['OUTPUT_VIDEO', 'VIDEO_SECOND', 'TOKENS'],
  SPEECH_RECOGNITION: ['INPUT_AUDIO_SECOND', 'TOKENS'],
  SPEECH_SYNTHESIS: ['BILLING_CHARACTER', 'OUTPUT_AUDIO_SECOND', 'TOKENS']
}

export const mediaPriceUnitLabels: Record<ModelMediaPriceUnit, string> = {
  OUTPUT_IMAGE: '每张输出图片',
  OUTPUT_VIDEO: '每个输出视频',
  VIDEO_SECOND: '每秒视频',
  INPUT_AUDIO_SECOND: '每秒输入音频',
  OUTPUT_AUDIO_SECOND: '每秒输出音频',
  BILLING_CHARACTER: '每个计费字符',
  TOKENS: '输入与输出 Token'
}

const MAX_PRICE_CENTS = 9223372036854775807n

export function validModelPrice(value: string): boolean {
  if (!/^(0|[1-9][0-9]*)(\.\d{1,6})?$/.test(value)) return false
  const [integer = '', fraction = ''] = value.split('.')
  const whole = BigInt(integer)
  return whole < MAX_PRICE_CENTS || (whole === MAX_PRICE_CENTS && !/[1-9]/.test(fraction))
}
