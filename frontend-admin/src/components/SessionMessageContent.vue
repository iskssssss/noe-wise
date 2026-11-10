<script setup lang="ts">
import { computed } from 'vue'
import MarkdownContent from '@skillforge/shared/components/MarkdownContent.vue'
import { historyContents } from '@skillforge/shared/components/chatMedia'
import { chatCitations } from '@skillforge/shared/components/chatCitations'
import type { ChatHistoryMessage } from '../api'
import SessionMediaImage from './SessionMediaImage.vue'

const props = defineProps<{ session: string; message: ChatHistoryMessage }>()
const emit = defineEmits<{
  citation: [selection: { session: string; message: string; citation: string }]
}>()
const contents = computed(() => historyContents(props.message.metadata))
const citations = computed(() => chatCitations(props.message.metadata?.citations))

function selectCitation(event: MouseEvent) {
  const anchor = event.target instanceof Element ? event.target.closest('a[href]') : null
  const citation = citations.value.find(
    (item) => anchor?.getAttribute('href') === `#citation-${item.citation_id}`
  )
  if (!citation) return
  event.preventDefault()
  emit('citation', {
    session: props.session,
    message: props.message.message_id,
    citation: citation.citation_id
  })
}
</script>

<template>
  <template v-if="message.role === 'user' && contents.length">
    <template v-for="(part, index) in contents" :key="index">
      <MarkdownContent v-if="part.type === 'text'" :content="part.text" @click="selectCitation" />
      <SessionMediaImage v-else :session="session" :media="part.media_id" />
    </template>
  </template>
  <MarkdownContent v-else :content="message.content" @click="selectCitation" />
  <div v-if="message.role === 'user'" class="toolbar" aria-label="已发送附件">
    <template v-if="message.metadata?.attachments?.length">
      <ElTag v-for="file in message.metadata.attachments" :key="file.id" size="small">{{
        file.name
      }}</ElTag>
    </template>
    <template v-else-if="Array.isArray(message.metadata?.attachment_ids)">
      <ElTag v-for="(_, index) in message.metadata.attachment_ids" :key="index" size="small"
        >附件 {{ index + 1 }}</ElTag
      >
    </template>
  </div>
  <ol v-if="citations.length" aria-label="知识依据">
    <li
      v-for="citation in citations"
      :id="`citation-${citation.citation_id}`"
      :key="citation.item_id"
      tabindex="-1"
    >
      <ElButton
        link
        type="primary"
        @click="
          emit('citation', {
            session,
            message: message.message_id,
            citation: citation.citation_id
          })
        "
        >{{ citation.label }}</ElButton
      >
    </li>
  </ol>
</template>
