<script setup lang="ts">
import { computed } from 'vue'
import MarkdownContent from '@skillforge/shared/components/MarkdownContent.vue'
import { historyContents } from '@skillforge/shared/components/chatMedia'
import type { ChatHistoryMessage } from '../api'
import SessionMediaImage from './SessionMediaImage.vue'

const props = defineProps<{ session: string; message: ChatHistoryMessage }>()
const contents = computed(() => historyContents(props.message.metadata))
</script>

<template>
  <template v-if="message.role === 'user' && contents.length">
    <template v-for="(part, index) in contents" :key="index">
      <MarkdownContent v-if="part.type === 'text'" :content="part.text" />
      <SessionMediaImage v-else :session="session" :media="part.media_id" />
    </template>
  </template>
  <MarkdownContent v-else :content="message.content" />
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
</template>
