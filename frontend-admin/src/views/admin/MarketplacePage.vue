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
    <template v-if="!data">
      <p role="status">
        {{ loading ? '正在读取工作流市场…' : '尚未取得市场内容，请重新读取后查看。' }}
      </p>
      <ElSkeleton v-if="loading" :rows="5" animated />
      <ElButton v-else @click="reload">重新读取市场</ElButton>
    </template>
    <template v-else>
      <p v-if="error" class="admin-muted" role="status">
        当前查询未能刷新，以下保留上次成功读取的结果。
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
        v-if="!loading && !data.items.length"
        :description="
          applied.query || applied.category
            ? '没有匹配的工作流，试试减少筛选条件'
            : '市场尚无已发布的平台工作流，可先管理本机构工作流'
        "
        ><ElButton v-if="query || category || applied.query || applied.category" @click="reset"
          >清空筛选</ElButton
        ><ElButton v-else @click="router.push('/admin/workflows')"
          >管理本机构工作流</ElButton
        ></ElEmpty
      ><ElPagination
        :disabled="loading"
        :current-page="data.location.page"
        :total="data.total"
        :page-size="20"
        layout="total,prev,pager,next"
        @current-change="changePage" /></template
  ></AdminPage>
</template>
<script setup lang="ts">
import FeedbackNotice from '@skillforge/shared/feedback/FeedbackNotice.vue'
import { vDescribedBy } from '@skillforge/shared/directives/describedBy'

import FieldTip from '@skillforge/shared/components/FieldTip.vue'

import { guardFormEnter } from '@skillforge/shared/admin'
import { acceptUnhighlightedCreate } from '@skillforge/shared/admin/creatableOption'
import { computed, onMounted, ref, useId, watch } from 'vue'
import { useRoute, useRouter, type LocationQuery } from 'vue-router'
import { AdminPage, useResource } from '@skillforge/shared/admin'
import { api } from '../../api'
const router = useRouter(),
  route = useRoute()
function readLocation(value: LocationQuery) {
  const page = Number(value.page)
  return {
    query: typeof value.q === 'string' ? value.q : '',
    category: typeof value.category === 'string' ? value.category : '',
    page: Number.isSafeInteger(page) && page > 0 ? page : 1
  }
}
const requested = ref(readLocation(route.query)),
  query = ref(requested.value.query),
  category = ref(requested.value.category)
const { data, loading, error, reload, invalidate } = useResource(async (signal) => {
  const location = { ...requested.value }
  const result = await api.marketplace.search(
    location.query,
    undefined,
    location.category || undefined,
    location.page,
    { signal }
  )
  return { ...result, location }
})
// 页码和已应用条件属于成功结果；失败或迟到响应不能替换它们。
const applied = computed(() => data.value?.location ?? requested.value)
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
function search() {
  if (loading.value) return
  requested.value = { query: query.value, category: category.value, page: 1 }
  void reload()
}
function changePage(nextPage: number) {
  if (loading.value || nextPage === applied.value.page) return
  requested.value = { ...applied.value, page: nextPage }
  void reload()
}
watch(data, (result) => {
  if (!result) return
  void router.replace({
    query: {
      ...route.query,
      q: result.location.query || undefined,
      category: result.location.category || undefined,
      page: result.location.page === 1 ? undefined : String(result.location.page)
    }
  })
})
watch(
  () => route.query,
  (value) => {
    const location = readLocation(value)
    if (JSON.stringify(location) === JSON.stringify(applied.value)) return
    invalidate()
    data.value = undefined
    requested.value = location
    query.value = location.query
    category.value = location.category
    void reload()
  }
)
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
