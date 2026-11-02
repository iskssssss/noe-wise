<template>
  <AdminPage title="工作流市场" description="查找可用工作流，查看详情并完成资源绑定后安装。"
    ><FeedbackNotice v-if="error" :title="error" :closable="false" type="error" /><ElForm
      class="admin-card admin-filters"
      inline
      @submit.prevent="search"
      scroll-to-error
      @keydown.capture="guardFormEnter"
      ><ElFormItem label="工作流名称"
        ><ElInput
          v-described-by="formTipId + '-field-0'"
          :aria-describedby="formTipId + '-field-0'"
          clearable
          v-model="query"
          placeholder="搜索工作流"
        />
        <FieldTip :id="formTipId + '-field-0'"
          >填写名称关键词，点击“搜索”后应用筛选。</FieldTip
        > </ElFormItem
      ><ElFormItem label="分类">
        <ElSelect
          v-model="category"
          @keydown.enter.capture="onCategoryEnter"
          filterable
          allow-create
          default-first-option
          clearable
          placeholder="全部分类，或输入分类名称"
          aria-label="工作流分类"
        >
          <ElOption v-for="item in categories" :key="item" :label="item" :value="item" />
        </ElSelect> </ElFormItem
      ><ElFormItem
        ><ElButton type="primary" native-type="submit" :loading="loading">搜索</ElButton
        ><ElButton
          :disabled="loading || (!query && !category && !applied.query && !applied.category)"
          @click="reset"
          >重置</ElButton
        ></ElFormItem
      ></ElForm
    >
    <p v-if="filtersChanged" class="admin-muted" role="status">
      筛选条件已修改，点击搜索后应用；翻页仍使用上次搜索条件。
    </p>
    <div class="cards" v-loading="loading">
      <ElCard
        class="admin-card marketplace-item"
        v-for="item in data?.items ?? []"
        :key="item.workflow_id"
        ><h3>{{ item.name }}</h3>
        <p class="admin-muted">{{ item.author }} · {{ item.version }}</p>
        <p>{{ item.description }}</p>
        <div class="admin-toolbar">
          <ElTag>{{ item.category }}</ElTag
          ><span>{{ item.install_count }} 次安装</span>
        </div>
        <ElButton @click="router.push('/admin/workflows/' + encodeURIComponent(item.workflow_id))"
          >查看与安装</ElButton
        ></ElCard
      >
    </div>
    <ElEmpty
      v-if="!loading && !error && !data?.items.length"
      :description="
        applied.query || applied.category
          ? '没有匹配的工作流，试试减少筛选条件'
          : '暂无可安装的工作流'
      "
      ><ElButton v-if="query || category || applied.query || applied.category" @click="reset"
        >清空筛选</ElButton
      ></ElEmpty
    ><ElPagination
      :disabled="loading"
      :current-page="page"
      :total="data?.total ?? 0"
      :page-size="20"
      layout="total,prev,pager,next"
      @current-change="changePage"
  /></AdminPage>
</template>
<script setup lang="ts">
import FeedbackNotice from '@skillforge/shared/feedback/FeedbackNotice.vue'
import { vDescribedBy } from '@skillforge/shared/directives/describedBy'

import FieldTip from '@skillforge/shared/components/FieldTip.vue'

import { guardFormEnter } from '@skillforge/shared/admin'
import { acceptUnhighlightedCreate } from '@skillforge/shared/admin/creatableOption'
import { computed, onMounted, ref, useId } from 'vue'
import { useRouter } from 'vue-router'
import { AdminPage, useResource } from '@skillforge/shared/admin'
import { api } from '../../api'
const router = useRouter(),
  query = ref(''),
  category = ref(''),
  page = ref(1),
  applied = ref({ query: '', category: '' }),
  { data, loading, error, reload } = useResource(() =>
    api.marketplace.search(
      applied.value.query,
      undefined,
      applied.value.category || undefined,
      page.value
    )
  )
const filtersChanged = computed(
  () => query.value !== applied.value.query || category.value !== applied.value.category
)
const categories = computed(() => [
  ...new Set((data.value?.items ?? []).map((item) => item.category).filter(Boolean))
])
function onCategoryEnter(event: KeyboardEvent) {
  acceptUnhighlightedCreate(event, true, categories.value, (value) => {
    category.value = value
  })
}
function reset() {
  query.value = ''
  category.value = ''
  search()
}
async function search() {
  if (loading.value) return
  const previous = { page: page.value, filters: applied.value }
  applied.value = { query: query.value, category: category.value }
  page.value = 1
  if ((await reload()) === undefined) {
    applied.value = previous.filters
    page.value = previous.page
  }
}
async function changePage(nextPage: number) {
  if (loading.value || nextPage === page.value) return
  const previous = page.value
  page.value = nextPage
  if ((await reload()) === undefined) page.value = previous
}
onMounted(reload)

const formTipId = useId()
</script>

<style scoped>
.marketplace-item :deep(.el-card__body) {
  display: flex;
  flex-direction: column;
  height: 100%;
  gap: 12px;
}
.marketplace-item h3,
.marketplace-item p {
  margin: 0;
}
.marketplace-item .el-button {
  align-self: flex-start;
  margin-top: auto;
}
</style>
