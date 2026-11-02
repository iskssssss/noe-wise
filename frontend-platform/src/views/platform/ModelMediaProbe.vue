<script setup lang="ts">
import { computed, onScopeDispose, reactive, ref, shallowRef, watch } from 'vue'
import FeedbackNotice from '@skillforge/shared/feedback/FeedbackNotice.vue'
import { useTask } from '@skillforge/shared/admin'
import { message } from '@skillforge/shared/feedback'
import { HttpClientError } from '@skillforge/shared/utils/http'
import { useActionPermissions } from '../../composables/actionPermissions'
import {
  api,
  type ModelKind,
  type ModelMediaCommand,
  type ModelMediaInput,
  type ModelMediaJob,
  type ModelMediaReference,
  type ModelMediaStatus
} from '../../api'

const props = defineProps<{
  kind: ModelKind
  serviceId?: string | null
  protocol?: string | null
  disabled: boolean
  prepare: () => Pick<ModelMediaCommand, 'id' | 'payload'>
}>()
const emit = defineEmits<{
  busy: [value: boolean]
  pendingOperation: [value: string | undefined]
}>()
const { allowed, checked } = useActionPermissions()
const task = useTask()
watch(task.busy, (value) => emit('busy', value), { flush: 'sync' })
onScopeDispose(() => {
  emit('busy', false)
  emit('pendingOperation', undefined)
})
const isMedia = computed(() =>
  ['IMAGE_GENERATION', 'VIDEO_GENERATION', 'SPEECH_RECOGNITION', 'SPEECH_SYNTHESIS'].includes(
    props.kind
  )
)
const isQwenTranscription = computed(
  () => props.serviceId === 'bailian-cn-beijing-qwen-asr' && props.protocol === 'qwen-asr-native'
)
const maximumQwenAudioLength = 10_000_000
const job = ref<ModelMediaJob>()
const unconfirmed = shallowRef<ModelMediaCommand>()
const existingId = ref('')
const errors = ref<Record<string, string>>({})
const image = reactive({
  prompt: '',
  references: '',
  size: '',
  count: undefined as number | undefined,
  negativePrompt: '',
  seed: undefined as number | undefined,
  promptExtend: 'DEFAULT',
  watermark: 'DEFAULT'
})
const video = reactive({
  prompt: '',
  media: [] as ModelMediaReference[],
  resolution: '',
  ratio: '',
  duration: undefined as number | undefined,
  audio: 'DEFAULT',
  seed: undefined as number | undefined,
  promptExtend: 'DEFAULT',
  watermark: 'DEFAULT'
})
const transcription = reactive({
  urls: '',
  channels: '',
  languages: '',
  diarization: 'DEFAULT',
  speakers: undefined as number | undefined
})
const qwenTranscription = reactive({
  audio: '',
  language: '',
  enableItn: 'DEFAULT',
  assetJobId: '',
  assetId: ''
})
const speech = reactive({ text: '', voice: '', language: '' })
const statusLabels: Record<ModelMediaStatus, string> = {
  NOT_SUBMITTED: '未提交',
  PENDING: '已受理',
  RUNNING: '处理中',
  SUCCEEDED: '已完成',
  FAILED: '失败',
  CANCELLED: '供应商已取消',
  UNKNOWN: '结果未知'
}
const referenceLabels: Record<ModelMediaReference['kind'], string> = {
  FIRST_FRAME: '首帧',
  LAST_FRAME: '尾帧',
  REFERENCE_IMAGE: '参考图片',
  REFERENCE_VIDEO: '参考视频',
  REFERENCE_AUDIO: '参考音频',
  FILE: '公开文件',
  LINK: '公开网页'
}
const terminal = computed(
  () =>
    !!job.value && ['NOT_SUBMITTED', 'SUCCEEDED', 'FAILED', 'CANCELLED'].includes(job.value.status)
)
const pendingOperation = computed(
  () =>
    unconfirmed.value?.operation_id ??
    (job.value && !terminal.value && (!job.value.waiting_stopped || job.value.status === 'UNKNOWN')
      ? job.value.operation_id
      : undefined)
)
watch(pendingOperation, (value) => emit('pendingOperation', value), { flush: 'sync' })
const canCreate = computed(
  () =>
    !unconfirmed.value &&
    job.value?.status !== 'UNKNOWN' &&
    (!job.value || terminal.value || job.value.waiting_stopped)
)
const sampleLocked = computed(() => props.disabled || task.busy.value || !!unconfirmed.value)
const booleanFields = [
  { value: 'DEFAULT', label: '供应商默认' },
  { value: 'YES', label: '开启' },
  { value: 'NO', label: '关闭' }
]
const usage = computed(() => job.value?.usage)

function lines(text: string) {
  return text
    .split(/\r?\n/)
    .map((value) => value.trim())
    .filter(Boolean)
}
function optionalBoolean(value: string) {
  return value === 'DEFAULT' ? undefined : value === 'YES'
}
function input(): ModelMediaInput {
  const invalid: Record<string, string> = {}
  let value: ModelMediaInput
  switch (props.kind) {
    case 'IMAGE_GENERATION':
      if (!image.prompt.trim()) invalid.prompt = '请填写图像生成或编辑提示词'
      value = {
        type: 'image',
        prompt: image.prompt.trim(),
        reference_images: lines(image.references),
        size: image.size.trim() || undefined,
        count: image.count,
        negative_prompt: image.negativePrompt.trim() || undefined,
        seed: image.seed,
        prompt_extend: optionalBoolean(image.promptExtend),
        watermark: optionalBoolean(image.watermark)
      }
      break
    case 'VIDEO_GENERATION':
      if (!video.prompt.trim() && !video.media.length)
        invalid.prompt = '请填写视频提示词或添加参考媒体'
      if (video.media.some((reference) => !reference.source.trim()))
        invalid.media = '请填写每项参考媒体的来源'
      value = {
        type: 'video',
        prompt: video.prompt.trim() || undefined,
        media: video.media.map((reference) => ({ ...reference, source: reference.source.trim() })),
        resolution: video.resolution.trim() || undefined,
        ratio: video.ratio.trim() || undefined,
        duration_seconds: video.duration,
        audio: optionalBoolean(video.audio),
        seed: video.seed,
        prompt_extend: optionalBoolean(video.promptExtend),
        watermark: optionalBoolean(video.watermark)
      }
      break
    case 'SPEECH_RECOGNITION': {
      if (isQwenTranscription.value) {
        const audio = qwenTranscription.audio.trim()
        if (!audio) invalid.audio = '请填写公网音频 URL 或读取受控音频产物'
        else if (audio.length > maximumQwenAudioLength)
          invalid.audio = '音频编码后的总长度不得超过 10,000,000 字节'
        else if (
          audio.startsWith('data:') &&
          !/^data:audio\/(?:mpeg|wav);base64,[A-Za-z0-9+/]+={0,2}$/.test(audio)
        )
          invalid.audio = '音频 Data URI 必须使用 MP3 或 WAV 的标准 Base64 编码'
        value = {
          type: 'qwen-transcription',
          audio,
          language: qwenTranscription.language.trim() || undefined,
          enable_itn: optionalBoolean(qwenTranscription.enableItn)
        }
        break
      }
      const urls = lines(transcription.urls)
      if (urls.length !== 1) invalid.urls = '当前服务测试每次接收一个可公开读取的音频 URL'
      const channels = transcription.channels.trim()
        ? transcription.channels.split(',').map((channel) => Number(channel.trim()))
        : undefined
      if (channels?.some((channel) => !Number.isInteger(channel) || channel < 0))
        invalid.channels = '声道编号须为以逗号分隔的非负整数'
      value = {
        type: 'transcription',
        file_urls: urls,
        channels,
        language_hints: transcription.languages
          .split(',')
          .map((language) => language.trim())
          .filter(Boolean),
        diarization: optionalBoolean(transcription.diarization),
        speaker_count: transcription.speakers
      }
      break
    }
    case 'SPEECH_SYNTHESIS':
      if (!speech.text.trim()) invalid.text = '请填写需要合成的文本'
      if (!speech.voice.trim()) invalid.voice = '请填写该模型实际支持的音色标识'
      value = {
        type: 'speech',
        text: speech.text.trim(),
        voice: speech.voice.trim(),
        language: speech.language.trim() || undefined
      }
      break
    default:
      throw new Error('请先选择媒体模型服务类型')
  }
  errors.value = invalid
  if (Object.keys(invalid).length) throw new Error(Object.values(invalid)[0])
  return value
}
function useAudioAsset() {
  if (!allowed('foundation.models.read')) return
  void task.run(async () => {
    const jobId = qwenTranscription.assetJobId.trim(),
      assetId = qwenTranscription.assetId.trim()
    if (!jobId || !assetId) throw new Error('请填写音频产物所属任务编号和产物编号')
    const blob = await checked('foundation.models.read', () =>
      api.admin.models.media.download(jobId, assetId)
    )
    if (!['audio/mpeg', 'audio/wav'].includes(blob.type))
      throw new Error('Qwen 同步识别当前仅接收 MP3 或 WAV 音频产物')
    const prefix = `data:${blob.type};base64,`
    if (prefix.length + Math.ceil(blob.size / 3) * 4 > maximumQwenAudioLength)
      throw new Error('该音频产物编码后超过 10,000,000 字节，请使用较短音频')
    const audio = await new Promise<string>((resolve, reject) => {
      const reader = new FileReader()
      reader.onload = () =>
        typeof reader.result === 'string'
          ? resolve(reader.result)
          : reject(new Error('未能读取音频产物'))
      reader.onerror = () => reject(new Error('未能读取音频产物'))
      reader.readAsDataURL(blob)
    })
    qwenTranscription.audio = audio
    delete errors.value.audio
  })
}
function create() {
  if (!allowed('foundation.models.run') || !canCreate.value) return
  void task.run(async () => {
    const command: ModelMediaCommand = {
      ...props.prepare(),
      operation_id: crypto.randomUUID(),
      input: input()
    }
    // 保存此次完整命令。返回丢失后只核对同键原请求，不能生成另一次供应商调用。
    unconfirmed.value = JSON.parse(JSON.stringify(command)) as ModelMediaCommand
    await submitOriginal()
  })
}
async function submitOriginal() {
  const command = unconfirmed.value
  if (!command) return
  try {
    job.value = await checked('foundation.models.run', () => api.admin.models.media.create(command))
    unconfirmed.value = undefined
  } catch (failure) {
    if (
      failure instanceof HttpClientError &&
      ['model_media_configuration_invalid', 'model_media_input_invalid'].includes(
        failure.code ?? ''
      )
    )
      unconfirmed.value = undefined
    throw failure
  }
}
function checkOriginal() {
  if (!allowed('foundation.models.run')) return
  void task.run(submitOriginal)
}
function query() {
  if (!job.value || !allowed('foundation.models.run')) return
  const id = job.value.id
  void task.run(async () => {
    job.value = await checked('foundation.models.run', () => api.admin.models.media.query(id))
  })
}
function readExisting() {
  if (!allowed('foundation.models.read')) return
  void task.run(async () => {
    const id = existingId.value.trim()
    if (!id) throw new Error('请填写已有媒体任务编号')
    job.value = await checked('foundation.models.read', () => api.admin.models.media.detail(id))
  })
}
function stopWaiting() {
  if (!job.value || !allowed('foundation.models.run')) return
  const id = job.value.id
  void task.run(async () => {
    job.value = await checked('foundation.models.run', () => api.admin.models.media.stopWaiting(id))
  })
}
function download(assetId: string) {
  const asset = job.value?.assets.find((entry) => entry.id === assetId)
  if (!job.value || !asset?.available || !allowed('foundation.models.read')) return
  const id = job.value.id
  void task.run(async () => {
    const blob = await checked('foundation.models.read', () =>
      api.admin.models.media.download(id, asset.id)
    )
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = asset.name
    link.click()
    URL.revokeObjectURL(url)
    message.success('已发起下载')
  })
}
</script>

<template>
  <section v-if="isMedia || job || unconfirmed" class="model-media-probe" aria-label="媒体模型测试">
    <h3>媒体模型测试</h3>
    <p class="admin-field-help">
      使用当前配置发送实际媒体请求，可能产生供应商费用；不会自动保存或发布模型配置。可查询原任务，页面不会自动重复创建。
    </p>
    <FeedbackNotice v-if="task.error.value" :title="task.error.value" type="error" />
    <ElForm v-if="isMedia" label-position="top" :disabled="sampleLocked" @submit.prevent="create">
      <template v-if="kind === 'IMAGE_GENERATION'">
        <ElFormItem label="图像提示词" :error="errors.prompt" required
          ><ElInput v-model="image.prompt" type="textarea" :rows="3"
        /></ElFormItem>
        <ElFormItem label="编辑或参考图片"
          ><ElInput v-model="image.references" type="textarea" :rows="2" />
          <p class="admin-field-help">
            每行一个公开图片 URL 或已准备的图片 data URI；留空为文生图。
          </p></ElFormItem
        >
        <div class="admin-form-grid">
          <ElFormItem label="输出尺寸"
            ><ElInput v-model="image.size" placeholder="按当前模型的实际格式填写"
          /></ElFormItem>
          <ElFormItem label="图片数量"
            ><ElInputNumber v-model="image.count" :min="1" :precision="0"
          /></ElFormItem>
          <ElFormItem label="负向提示词"><ElInput v-model="image.negativePrompt" /></ElFormItem>
          <ElFormItem label="随机种子"
            ><ElInputNumber v-model="image.seed" :min="0" :precision="0"
          /></ElFormItem>
          <ElFormItem label="提示词扩写"
            ><ElSelect v-model="image.promptExtend"
              ><ElOption
                v-for="option in booleanFields"
                :key="option.value"
                :value="option.value"
                :label="option.label" /></ElSelect
          ></ElFormItem>
          <ElFormItem label="图片水印"
            ><ElSelect v-model="image.watermark"
              ><ElOption
                v-for="option in booleanFields"
                :key="option.value"
                :value="option.value"
                :label="option.label" /></ElSelect
          ></ElFormItem>
        </div>
      </template>
      <template v-else-if="kind === 'VIDEO_GENERATION'">
        <ElFormItem label="视频提示词" :error="errors.prompt"
          ><ElInput v-model="video.prompt" type="textarea" :rows="3"
        /></ElFormItem>
        <ElFormItem label="视频参考媒体" :error="errors.media">
          <div class="media-reference-list">
            <div v-for="(reference, index) in video.media" :key="index" class="media-reference-row">
              <ElSelect v-model="reference.kind" :aria-label="`参考媒体 ${index + 1} 类型`"
                ><ElOption
                  v-for="(label, value) in referenceLabels"
                  :key="value"
                  :value="value"
                  :label="label"
              /></ElSelect>
              <ElInput
                v-model="reference.source"
                :aria-label="`参考媒体 ${index + 1} 来源`"
                placeholder="公开媒体 URL"
              />
              <ElButton @click="video.media.splice(index, 1)">移除</ElButton>
            </div>
            <ElButton @click="video.media.push({ kind: 'FIRST_FRAME', source: '' })"
              >添加参考媒体</ElButton
            >
          </div>
        </ElFormItem>
        <div class="admin-form-grid">
          <ElFormItem label="视频分辨率"><ElInput v-model="video.resolution" /></ElFormItem>
          <ElFormItem label="画面比例"><ElInput v-model="video.ratio" /></ElFormItem>
          <ElFormItem label="时长（秒）"
            ><ElInputNumber v-model="video.duration" :min="1" :precision="0"
          /></ElFormItem>
          <ElFormItem label="视频随机种子"
            ><ElInputNumber v-model="video.seed" :min="0" :precision="0"
          /></ElFormItem>
          <ElFormItem label="生成音频"
            ><ElSelect v-model="video.audio"
              ><ElOption
                v-for="option in booleanFields"
                :key="option.value"
                :value="option.value"
                :label="option.label" /></ElSelect
          ></ElFormItem>
          <ElFormItem label="视频提示词扩写"
            ><ElSelect v-model="video.promptExtend"
              ><ElOption
                v-for="option in booleanFields"
                :key="option.value"
                :value="option.value"
                :label="option.label" /></ElSelect
          ></ElFormItem>
          <ElFormItem label="视频水印"
            ><ElSelect v-model="video.watermark"
              ><ElOption
                v-for="option in booleanFields"
                :key="option.value"
                :value="option.value"
                :label="option.label" /></ElSelect
          ></ElFormItem>
        </div>
      </template>
      <template v-else-if="kind === 'SPEECH_RECOGNITION'">
        <template v-if="isQwenTranscription">
          <ElFormItem label="Qwen 待识别音频" :error="errors.audio" required>
            <ElInput v-model="qwenTranscription.audio" type="textarea" :rows="3" />
            <p class="admin-field-help">
              填写公开 HTTPS 音频 URL，或通过下方入口读取已有 MP3／WAV 音频产物。Data URI
              编码后含前缀最多 10,000,000 字节。
            </p>
          </ElFormItem>
          <div class="admin-form-grid">
            <ElFormItem label="音频所属任务编号"
              ><ElInput v-model="qwenTranscription.assetJobId"
            /></ElFormItem>
            <ElFormItem label="音频产物编号"
              ><ElInput v-model="qwenTranscription.assetId"
            /></ElFormItem>
            <ElFormItem label="识别语言"
              ><ElInput v-model="qwenTranscription.language"
            /></ElFormItem>
            <ElFormItem label="数字等文本归一化"
              ><ElSelect v-model="qwenTranscription.enableItn"
                ><ElOption
                  v-for="option in booleanFields"
                  :key="option.value"
                  :value="option.value"
                  :label="option.label" /></ElSelect
            ></ElFormItem>
          </div>
          <ElButton
            v-permission="['foundation.models.read']"
            :loading="task.busy.value"
            @click="useAudioAsset"
            >读取已有音频产物</ElButton
          >
          <p class="admin-field-help">
            使用当前账号授权下载产物后转换音频编码，不读取宿主文件路径，也不暴露供应商下载地址。
          </p>
        </template>
        <template v-else>
          <ElFormItem label="待识别音频 URL" :error="errors.urls" required
            ><ElInput v-model="transcription.urls" type="textarea" :rows="2" />
            <p class="admin-field-help">
              填写一个可公开读取的稳定音频 URL。识别结果由原任务查询取得，不重复上传或创建任务。
            </p></ElFormItem
          >
          <div class="admin-form-grid">
            <ElFormItem label="声道编号" :error="errors.channels"
              ><ElInput
                v-model="transcription.channels"
                placeholder="按音频实际声道填写，以逗号分隔"
            /></ElFormItem>
            <ElFormItem label="语言提示"
              ><ElInput
                v-model="transcription.languages"
                placeholder="使用供应商支持的语言代码，以逗号分隔"
            /></ElFormItem>
            <ElFormItem label="说话人分离"
              ><ElSelect v-model="transcription.diarization"
                ><ElOption
                  v-for="option in booleanFields"
                  :key="option.value"
                  :value="option.value"
                  :label="option.label" /></ElSelect
            ></ElFormItem>
            <ElFormItem label="说话人数"
              ><ElInputNumber v-model="transcription.speakers" :min="1" :precision="0"
            /></ElFormItem>
          </div>
        </template>
      </template>
      <template v-else-if="kind === 'SPEECH_SYNTHESIS'">
        <ElFormItem label="合成文本" :error="errors.text" required
          ><ElInput v-model="speech.text" type="textarea" :rows="3"
        /></ElFormItem>
        <div class="admin-form-grid">
          <ElFormItem label="音色标识" :error="errors.voice" required
            ><ElInput v-model="speech.voice" />
            <p class="admin-field-help">
              填写当前模型实际支持的音色，不会根据名称猜测音色。
            </p></ElFormItem
          >
          <ElFormItem label="合成语言"><ElInput v-model="speech.language" /></ElFormItem>
        </div>
      </template>
      <ElButton
        v-permission="['foundation.models.run']"
        :disabled="!canCreate"
        :loading="task.busy.value"
        @click="create"
        >开始媒体测试</ElButton
      >
      <p v-if="!canCreate && !unconfirmed" class="admin-field-help">
        原任务尚未结束或结果未知，请先核对原任务；结果未知时不能重新创建测试。
      </p>
    </ElForm>
    <div v-if="unconfirmed" role="status" class="media-job-status">
      <p>
        创建结果尚未确认。原操作编号
        {{ unconfirmed.operation_id }} 已保留；核对只使用原请求，不创建新的操作。
      </p>
      <ElButton
        v-permission="['foundation.models.run']"
        :disabled="task.busy.value || disabled"
        @click="checkOriginal"
        >核对原操作</ElButton
      >
    </div>
    <div class="media-existing-job">
      <ElInput
        v-model="existingId"
        aria-label="已有媒体任务编号"
        placeholder="已有媒体任务编号"
        :disabled="task.busy.value || !!unconfirmed"
      />
      <ElButton
        v-permission="['foundation.models.read']"
        :disabled="task.busy.value || !!unconfirmed || disabled"
        @click="readExisting"
        >读取已有任务</ElButton
      >
    </div>
    <section v-if="job" aria-label="媒体测试任务结果" class="media-job-status">
      <h4>任务状态：{{ statusLabels[job.status] }}</h4>
      <p role="status">
        任务编号 {{ job.id }} · 模型 {{ job.model_id }} ·
        {{ job.model_revision === 0 ? '未保存配置' : `修订 ${job.model_revision}` }}
      </p>
      <p v-if="job.waiting_stopped" class="admin-field-help">
        已停止本地等待；供应商任务可能仍在执行，可继续查询原任务。
      </p>
      <p v-if="job.status === 'UNKNOWN'" class="admin-field-help">
        原请求结果未知。请核对原任务，不要通过重新创建任务重试。
      </p>
      <p v-if="job.reason_code" class="admin-field-help">诊断代码：{{ job.reason_code }}</p>
      <div class="admin-actions">
        <ElButton
          v-permission="['foundation.models.run']"
          :disabled="task.busy.value || disabled"
          @click="query"
          >查询原任务</ElButton
        >
        <ElButton
          v-if="!terminal && !job.waiting_stopped"
          v-permission="['foundation.models.run']"
          :disabled="task.busy.value || disabled"
          @click="stopWaiting"
          >停止本地等待</ElButton
        >
      </div>
      <p v-if="usage" class="admin-field-help">
        实际用量：输入 {{ usage.input_tokens ?? '未知' }} Tokens；输出
        {{ usage.output_tokens ?? '未知' }} Tokens；图片 {{ usage.image_count ?? '未知' }} 张；视频
        {{ usage.video_count ?? '未知' }} 个；字符 {{ usage.characters ?? '未知' }}；时长
        {{ usage.duration_seconds ?? '未知' }} 秒。
      </p>
      <p v-else class="admin-field-help">供应商尚未返回实际用量。</p>
      <ElTable :data="job.assets" aria-label="媒体测试产物">
        <ElTableColumn prop="id" label="产物编号" min-width="180" show-overflow-tooltip />
        <ElTableColumn prop="name" label="产物名称" min-width="180" />
        <ElTableColumn prop="media_type" label="类型" min-width="120" />
        <ElTableColumn prop="size_bytes" label="字节数" min-width="100" />
        <ElTableColumn label="操作" min-width="140"
          ><template #default="{ row }"
            ><ElButton
              v-permission="['foundation.models.read']"
              :disabled="task.busy.value || !row.available || disabled"
              @click="download(row.id)"
              >{{ row.available ? '下载结果' : '尚未就绪' }}</ElButton
            ></template
          ></ElTableColumn
        >
      </ElTable>
    </section>
  </section>
</template>

<style scoped>
.model-media-probe {
  margin-top: 24px;
}
.media-reference-list {
  width: 100%;
  display: grid;
  gap: 12px;
}
.media-reference-row {
  display: flex;
  gap: 12px;
  width: 100%;
}
.media-reference-row > .el-select {
  flex: 0 0 150px;
}
.media-reference-row > .el-input {
  flex: 1;
  min-width: 0;
}
.media-existing-job {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-top: 16px;
}
.media-existing-job > .el-input {
  max-width: 420px;
}
.media-job-status {
  margin-top: 16px;
}
@media (max-width: 640px) {
  .media-reference-row,
  .media-existing-job {
    flex-wrap: wrap;
  }
  .media-reference-row > .el-select {
    flex: 1 1 100%;
  }
  .media-reference-row > .el-input {
    flex: 1 1 100%;
  }
}
</style>
