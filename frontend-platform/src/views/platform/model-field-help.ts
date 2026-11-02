import type { ModelReasoningPolicy } from '../../api'

export const modelReasoningLabels: Record<ModelReasoningPolicy, string> = {
  DEFAULT: '提供方默认',
  DISABLED: '关闭推理',
  ENABLED: '开启推理',
  MINIMAL: '最小',
  LOW: '低',
  MEDIUM: '中',
  HIGH: '高',
  XHIGH: '超高',
  MAX: '最高'
}

export const modelKindLabels: Record<string, string> = {
  LLM: '对话模型（LLM）',
  EMBEDDING: '向量模型（Embedding）',
  RERANK: '重排模型（Rerank）',
  IMAGE_GENERATION: '图像生成与编辑',
  VIDEO_GENERATION: '视频生成',
  SPEECH_RECOGNITION: '语音识别',
  SPEECH_SYNTHESIS: '语音合成'
}

export function modelCapacityHelp(key: string) {
  const help: Record<string, string> = {
    supported_max_output_tokens: '模型实际支持的最大输出 Tokens，按供应商能力填写。',
    context_window_tokens:
      '模型实际上下文窗口，单位为 Tokens；单次输入容量扣除输出预留和窗口的 5% 安全余量。',
    max_output_tokens:
      '每次请求的输出 Token 上限，不得超过模型支持值；此额度也作为输入容量的输出预留。'
  }
  return help[key]
}

export function imagePolicyHelp(key: string) {
  const help: Record<string, string> = {
    max_images: '单次请求允许的图片数量上限，单位为张。',
    max_image_bytes: '单张图片的大小上限，单位为字节。',
    max_request_bytes: '图片请求的总载荷大小上限，单位为字节。',
    max_dimension: '图片尺寸保护上限，单位为像素。',
    tile_size: '图片 Token 估算使用的切片尺寸，单位为像素。',
    base_tokens: '每张图片计量时使用的基础 Tokens。',
    tile_tokens: '每个图片切片计量时使用的 Tokens；采用明确的单图固定上界时可为 0。'
  }
  return help[key]
}
