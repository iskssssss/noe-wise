<template>
  <AdminPage
    :title="base?.name || '知识库文档'"
    description="上传、编辑文档，检查分块并验证检索结果。"
    ><template #extra
      ><ElUpload
        v-permission="['foundation.knowledge.create']"
        v-described-by="formTipId + '-upload'"
        :aria-describedby="formTipId + '-upload'"
        ref="fileUpload"
        :auto-upload="false"
        :show-file-list="false"
        :disabled="
          busy ||
          uploadRefresh ||
          rebuildRefresh ||
          uploadBlocked ||
          !!initialPending ||
          !!rebuild?.write_locked
        "
        :on-change="upload"
        ><ElButton
          type="primary"
          :disabled="
            busy ||
            uploadRefresh ||
            rebuildRefresh ||
            uploadBlocked ||
            !!initialPending ||
            !!rebuild?.write_locked
          "
          >上传文件</ElButton
        ></ElUpload
      >
      <FieldTip :id="formTipId + '-upload'"
        >选择文件后会立即上传到当前知识库并提交处理；文件大小和分块数量受平台资源限制，重建锁定时不可上传。</FieldTip
      >
      <ElButton @click="load" :disabled="busy">刷新</ElButton></template
    >
    <section v-if="initialPending" class="admin-card" role="status">
      已选择“{{ initialPending.name }}”，尚未上传。
      {{
        can('foundation.knowledge.create')
          ? '读取知识库后将继续上传；如读取失败请重试。'
          : '当前账号没有上传权限。'
      }}
      <ElButton
        v-if="can('foundation.knowledge.create')"
        :disabled="busy"
        @click="retryInitialUpload"
        >重新读取并继续</ElButton
      >
      <ElButton :disabled="busy" @click="discardInitialFile">移除待上传文件</ElButton>
    </section>
    <section v-if="documentMutationRefresh" class="admin-card" role="status">
      文档操作已完成，但列表尚未更新。<ElButton
        :disabled="busy || loading"
        @click="refreshDocumentList"
        >读取最新文档</ElButton
      >
    </section>
    <section v-if="uploadRefresh" class="admin-card" role="status">
      文件已上传，最新文档列表尚未读取。<ElButton :disabled="busy" @click="retryUploadRefresh"
        >读取上传后的文档</ElButton
      >
    </section>
    <section v-if="uploadUnknown" class="admin-card" role="status">
      文件“{{ uploadUnknown.name }}”（{{ uploadUnknown.size }} 字节）的上传结果尚未确认。
      请核对原请求；找到或未找到同名文档均不能证明原请求结束，请勿重复上传。
      <span v-if="uploadUnknown.requestId"
        >原请求 ID：<code>{{ uploadUnknown.requestId }}</code
        >。</span
      >
      <span v-if="!uploadUnknown.requestId">此记录没有请求标识，请联系管理员核对。</span>
      <ElButton
        v-if="uploadUnknown.requestId && can('foundation.knowledge.read')"
        :disabled="busy"
        @click="checkUpload()"
        >核对上传回执</ElButton
      >
      <ElButton :disabled="busy" @click="load">刷新文档列表</ElButton>
    </section>
    <p v-if="uploadStorageError" role="status">{{ uploadStorageError }}</p>
    <details v-if="!uploadUnknown && can('foundation.knowledge.read')" class="knowledge-receipt">
      <summary>按请求 ID 查询历史上传回执</summary>
      <div class="admin-toolbar">
        <ElInput
          v-model="uploadRequestId"
          aria-label="上传请求 ID"
          placeholder="输入原上传请求 ID"
        />
        <ElButton :loading="busy" @click="checkHistoricalUpload">查询上传回执</ElButton>
      </div>
      <p v-if="historicalUpload" role="status">
        {{
          historicalUpload.status === 'SUCCEEDED'
            ? '上传已完成，文档标识：' + historicalUpload.document?.document_id
            : historicalUpload.status === 'REJECTED'
              ? '上传已拒绝：' + historicalUpload.error_message
              : '原上传仍在处理。'
        }}
      </p>
    </details>
    <section v-if="publicationPending && !preparationOpen" class="admin-card" role="status">
      保存范围并发布的结果未知，原请求 ID：<code>{{ publicationPending.requestId }}</code
      >。
      <ElButton @click="preparationOpen = true">核对原操作</ElButton>
    </section>
    <details
      v-if="!publicationPending && can('foundation.knowledge.read')"
      class="knowledge-receipt"
      aria-label="查询知识发布回执"
    >
      <summary>按请求 ID 查询历史发布回执</summary>
      <div class="admin-toolbar">
        <ElInput
          v-model="publicationRequestId"
          aria-label="原请求 ID"
          placeholder="输入原请求 ID"
        />
        <ElButton :loading="busy" @click="checkPublication">查询发布回执</ElButton>
      </div>
    </details>
    <section v-if="publicationReceipt?.revision" class="admin-card" role="status">
      知识发布已确认：修订 {{ publicationReceipt.revision.revision }}，版本
      {{ publicationReceipt.revision.id }}。
    </section>
    <section v-if="prepareUnknown || prepareRefresh" class="admin-card" role="status">
      {{
        prepareUnknown
          ? '准备结果尚未确认，原文档范围已保留；请只读核对修订列表，不会再次提交。'
          : '修订已准备就绪，列表尚未刷新；重新读取不会再次准备。'
      }}
      <ElButton :disabled="busy" @click="refreshPreparation">核对修订列表</ElButton>
      <ElButton v-if="prepareUnknown" :disabled="busy" @click="preparationOpen = true"
        >查看原文档范围</ElButton
      >
    </section>
    <div v-if="base" class="admin-toolbar knowledge-overview" role="status">
      <ElTag :type="base.active_revision_id ? 'success' : 'info'">{{
        currentRevision
          ? '当前发布 · 修订 ' + currentRevision.revision
          : base.active_revision_id
            ? '已有当前发布'
            : '尚未发布'
      }}</ElTag>
      <span
        >{{ documents?.total ?? 0 }}
        {{
          appliedDocumentFilters.titleQuery || appliedDocumentFilters.status
            ? '篇匹配文档'
            : '篇文档'
        }}</span
      >
      <ElButton link @click="activeTab = 'search'">验证检索效果</ElButton>
      <ElButton
        v-permission.all="['foundation.knowledge.create', 'foundation.knowledge.update']"
        link
        :disabled="busy || prepareRefresh || (publicationLocked && !publicationPending)"
        @click="openPreparation"
        >{{ base.active_revision_id ? '更新知识发布' : '准备知识发布' }}</ElButton
      >
      <ElButton
        v-if="rebuild?.job_id && rebuild.status !== 'PUBLISHED' && rebuild.status !== 'CANCELLED'"
        link
        type="warning"
        @click="activeTab = 'rebuild'"
        >{{ '索引重建 · ' + statusLabel(rebuild.status) }}</ElButton
      >
    </div>
    <p v-if="rebuild?.write_locked" class="admin-field-help" role="status">
      知识库重建已锁定上传、编辑和删除。请在“索引与模型”查看进展；重建就绪后仍需发布结果或取消任务。
    </p>
    <FeedbackNotice
      v-if="
        publicationStorageError || error || loadError || (activeTab === 'rebuild' && modelError)
      "
      :title="publicationStorageError || error || loadError || modelError"
      :closable="false"
      type="error"
    /><ElTabs v-model="activeTab"
      ><ElTabPane label="文档维护" name="documents"
        ><p class="admin-muted">
          上传或编辑后检查文档状态与分块；“重新索引”会重新生成向量，可能产生模型调用费用。
        </p>
        <ElForm
          class="admin-toolbar knowledge-document-filters"
          label-position="top"
          :disabled="loading || busy || uploadRefresh || documentMutationRefresh"
          @submit.prevent="findDocuments"
        >
          <ElFormItem label="文档标题" class="knowledge-title-filter"
            ><ElInput
              v-model="documentTitleQuery"
              aria-label="查找文档标题"
              placeholder="按标题查找当前知识库全部文档"
              clearable
          /></ElFormItem>
          <ElFormItem label="处理状态" class="knowledge-status-filter"
            ><ElSelect v-model="documentStatus" aria-label="文档处理状态" placeholder="全部状态"
              ><ElOption value="" label="全部状态" /><ElOption
                value="ready"
                label="就绪" /><ElOption value="processing" label="处理中" /><ElOption
                value="failed"
                label="失败" /></ElSelect
          ></ElFormItem>
          <ElButton native-type="submit" type="primary" :loading="loading">查找文档</ElButton>
          <ElButton @click="clearDocumentFilters">清空筛选</ElButton>
        </ElForm>
        <p class="admin-field-help" role="status">
          当前结果：{{
            appliedDocumentFilters.titleQuery
              ? '标题包含“' + appliedDocumentFilters.titleQuery + '”'
              : '全部标题'
          }}
          ·
          {{
            appliedDocumentFilters.status ? statusLabel(appliedDocumentFilters.status) : '全部状态'
          }}（全库查询）。
        </p>
        <p v-if="documentFilterFailed" class="admin-field-help" role="status">
          本次筛选尚未更新，仍显示上次成功读取的文档和页码。已保留筛选输入，可点击“查找文档”重试。
        </p>
        <ElTable
          table-layout="auto"
          :data="documents?.items ?? []"
          v-loading="loading"
          row-key="id"
          stripe
          :empty-text="
            !documents && (loading || busy)
              ? '正在读取文档…'
              : !documents && (loadError || error)
                ? '文档列表尚未读取成功，请点击刷新重试'
                : appliedDocumentFilters.titleQuery || appliedDocumentFilters.status
                  ? '没有符合当前筛选的文档，请调整条件或清空筛选'
                  : '尚无文档，点击“上传文件”添加知识内容'
          "
          ><ElTableColumn
            min-width="200"
            show-overflow-tooltip
            prop="title"
            label="文档"
          ></ElTableColumn
          ><ElTableColumn
            min-width="140"
            show-overflow-tooltip
            prop="source_type"
            label="来源"
          ></ElTableColumn
          ><ElTableColumn width="128" show-overflow-tooltip label="状态"
            ><template #default="{ row }">{{ statusLabel(row.status) }}</template></ElTableColumn
          ><ElTableColumn width="96" prop="chunk_count" label="分块"></ElTableColumn
          ><ElTableColumn width="240" class-name="admin-table-actions" prop="actions" label="操作"
            ><template #default="{ row }"
              ><ElButton
                link
                @click="editDocument((row as KnowledgeDocumentItem).id)"
                :disabled="busy || documentMutationRefresh"
                >编辑 / 分块</ElButton
              ><ElButton
                v-permission="['foundation.knowledge.run']"
                link
                :disabled="
                  busy || documentMutationRefresh || rebuildRefresh || rebuild?.write_locked
                "
                @click="reindex(row as KnowledgeDocumentItem)"
                >{{ row.status === 'failed' ? '重试索引' : '重新索引' }}</ElButton
              ><ElButton
                v-permission="['foundation.knowledge.delete']"
                link
                type="danger"
                :disabled="
                  busy || documentMutationRefresh || rebuildRefresh || rebuild?.write_locked
                "
                @click="removeDocument(row as KnowledgeDocumentItem)"
                >删除</ElButton
              ></template
            ></ElTableColumn
          ></ElTable
        ><ElPagination
          v-described-by="formTipId + '-documents-page'"
          :aria-describedby="formTipId + '-documents-page'"
          :disabled="loading || busy || documentMutationRefresh"
          layout="total,sizes,prev,pager,next"
          :page-sizes="[20, 50, 100]"
          :current-page="page"
          :page-size="pageSize"
          :total="documents?.total ?? 0"
          @current-change="changeDocumentPage($event)"
          @size-change="changeDocumentPage(1, $event)"
        /><FieldTip :id="formTipId + '-documents-page'"
          >每页显示当前知识库的文档条数，可选 20、50 或 100
          条；更改条数会返回第一页并重新读取文档列表。</FieldTip
        ></ElTabPane
      >
      <ElTabPane label="检索验证" name="search"
        ><ElForm
          label-position="top"
          @submit.prevent="search"
          :disabled="busy"
          scroll-to-error
          @keydown.capture="guardFormEnter"
          ><ElFormItem label="检索问题"
            ><ElInput
              v-described-by="formTipId + '-field-0'"
              :aria-describedby="formTipId + '-field-0'"
              v-model="query"
              type="textarea"
              :rows="3"
            />
            <FieldTip :id="formTipId + '-field-0'"
              >填写真实检索问题，用于观察当前知识库的召回效果。</FieldTip
            > </ElFormItem
          ><ElFormItem label="仅本次检索覆盖参数">
            <ElSwitch v-model="overrideSearch" aria-label="仅本次检索覆盖参数" />
            <p class="admin-field-help">
              默认沿用知识库与服务端设置。打开后仅覆盖已填写项目，不保存正式配置；语义、混合检索和重排可能产生模型调用费用。
            </p>
          </ElFormItem>
          <div v-if="overrideSearch" class="admin-form-grid">
            <ElFormItem label="本次检索方式">
              <ElSelect v-model="searchMode" :disabled="economicalIndex" aria-label="本次检索方式">
                <ElOption value="" label="沿用知识库设置" /><ElOption
                  value="semantic"
                  label="语义检索"
                /><ElOption value="full_text" label="全文检索" /><ElOption
                  value="hybrid"
                  label="混合检索"
                />
              </ElSelect>
              <p class="admin-field-help">
                {{
                  economicalIndex
                    ? '经济索引仅支持全文检索。'
                    : '当前库设置：' + (base?.retrieval_mode || '使用平台默认方式')
                }}
              </p>
            </ElFormItem>
            <ElFormItem label="本次返回数量">
              <ElInputNumber
                v-model="topK"
                :min="1"
                :max="100"
                :precision="0"
                aria-label="本次返回数量"
              />
              <p class="admin-field-help">范围 1–100；留空由服务端决定。</p>
            </ElFormItem>
            <ElFormItem label="本次相关度阈值">
              <ElSelect
                v-model="thresholdMode"
                :disabled="!supportsThreshold"
                aria-label="本次相关度阈值"
              >
                <ElOption value="" label="沿用知识库设置" /><ElOption
                  :value="true"
                  label="开启"
                /><ElOption :value="false" label="关闭" />
              </ElSelect>
              <ElInputNumber
                v-if="supportsThreshold && thresholdActive"
                v-model="scoreThreshold"
                :min="0"
                :max="1"
                :step="0.1"
                aria-label="本次阈值数值"
              />
              <p class="admin-field-help">仅语义和混合检索生效，范围 0–1；数值留空沿用设置。</p>
            </ElFormItem>
            <ElFormItem label="本次向量权重">
              <ElInputNumber
                v-model="vectorWeight"
                :disabled="effectiveSearchMode !== 'hybrid'"
                :min="0"
                :max="1"
                :step="0.1"
                aria-label="本次向量权重"
              />
              <p class="admin-field-help">
                仅混合检索生效，全文权重为 1 减向量权重；留空沿用设置。
              </p>
            </ElFormItem>
            <ElFormItem label="本次重排">
              <ElSelect v-model="rerankMode" aria-label="本次重排"
                ><ElOption value="" label="沿用知识库设置" /><ElOption
                  :value="true"
                  label="开启" /><ElOption :value="false" label="关闭"
              /></ElSelect>
              <p class="admin-field-help">开启需要已配置可用的重排模型。</p>
            </ElFormItem>
            <ElFormItem label="本次来源版本">
              <ElInput v-model="sourceVersion" aria-label="本次来源版本" clearable />
              <p class="admin-field-help">填写精确 source_version；留空检索当前已发布修订范围。</p>
            </ElFormItem>
          </div>
          <ElButton type="primary" native-type="submit" :loading="busy">检索</ElButton></ElForm
        >
        <p v-if="lastSearchRequest" class="admin-muted" role="status">
          上次成功检索的问题：{{ lastSearchRequest.query }}
        </p>
        <details v-if="lastSearchRequest" class="admin-field-help">
          <summary>本次已提交参数 · {{ lastSearchMode }}</summary>
          <p>
            以下参数对应当前结果；未提交的覆盖项由服务端决定。修改表单后需重新检索才会更新结果。
          </p>
          <pre class="search-request">{{ JSON.stringify(lastSearchRequest, null, 2) }}</pre>
        </details>
        <ElCard v-for="(item, index) in results" :key="item.chunk_id" style="margin-top: 16px"
          ><div class="muted">
            结果 {{ index + 1 }} · 得分 {{ item.score }} · 文档 {{ item.document_id }}
          </div>
          <p class="readable">{{ item.content }}</p>
          <ElButton
            v-if="can('foundation.knowledge.read')"
            link
            :disabled="busy || documentMutationRefresh"
            @click="editDocument(item.document_id)"
            >查看当前来源文档</ElButton
          >
          <span class="admin-field-help">文档可能已更新，检索结果对应本次查询范围。</span></ElCard
        ><ElEmpty
          v-if="searched && !results.length && !busy"
          description="本次没有召回结果，请检查文档状态或调整问题"
        />
        <p v-if="searched && results.length" class="admin-muted" role="status">
          本次召回 {{ results.length }} 条结果，请核对内容和来源。
        </p></ElTabPane
      >
      <ElTabPane label="知识发布" name="versions"
        ><ElAlert
          title="选择当前就绪文档并确认本次发布范围，准备成功后再独立发布；智能体每轮聊天读取当前发布修订。"
          type="info"
          :closable="false"
        />
        <div class="admin-toolbar">
          <ElButton
            v-permission.all="['foundation.knowledge.create', 'foundation.knowledge.update']"
            :disabled="busy || prepareRefresh || publicationLocked"
            @click="openPreparation"
            >准备知识发布</ElButton
          >
        </div>
        <ElTable
          table-layout="auto"
          :data="revisions"
          v-loading="false"
          row-key="id"
          stripe
          empty-text="尚无发布修订，点击“准备知识发布”选择文档范围"
          ><ElTableColumn width="96" prop="revision" label="修订"></ElTableColumn
          ><ElTableColumn width="128" show-overflow-tooltip label="状态"
            ><template #default="{ row }">{{ statusLabel(row.status) }}</template></ElTableColumn
          ><ElTableColumn width="112" label="文档版本"
            ><template #default="{ row }"
              >{{ row.source_versions.length }} 项</template
            ></ElTableColumn
          ><ElTableColumn
            min-width="200"
            show-overflow-tooltip
            prop="capability_version_id"
            label="能力版本"
          ></ElTableColumn
          ><ElTableColumn width="112" class-name="admin-table-actions" prop="actions" label="操作"
            ><template #default="{ row }"
              ><ElButton
                v-permission.all="['foundation.knowledge.create', 'foundation.knowledge.update']"
                v-if="row.status === 'draft'"
                link
                @click="openPreparation"
                :disabled="busy"
                >检查并准备发布</ElButton
              ><ElButton
                v-permission="['foundation.knowledge.publish']"
                v-if="row.status === 'ready'"
                link
                @click="publishRevision(row as KnowledgeBaseRevision)"
                :disabled="busy || publicationLocked"
                >发布</ElButton
              ></template
            ></ElTableColumn
          ></ElTable
        ></ElTabPane
      >
      <ElTabPane label="索引与模型" name="rebuild"
        ><p class="admin-muted">
          更换向量模型时重建全库索引，期间文档写入锁定；重建完成后需发布结果。
        </p>
        <p v-if="effectiveIndexingMode === 'economical'" role="status" class="admin-field-help">
          当前为经济索引，仅支持全文检索，不支持更换向量模型或发起向量重建。
        </p>
        <p
          v-else-if="base && effectiveIndexingMode !== 'high_quality'"
          role="status"
          class="admin-field-help"
        >
          当前索引方式无法确认，请重新读取知识库信息或联系管理员。
        </p>
        <p
          v-if="
            rebuildModeConflict ||
            (effectiveIndexingMode === 'economical' && rebuild?.status === 'READY')
          "
          role="status"
          class="admin-field-help"
        >
          当前索引方式与向量重建不兼容，原任务已保留。请联系管理员恢复高质量索引后刷新重建进展，或取消重建。
        </p>
        <p
          v-if="rebuild?.error_code === 'KNOWLEDGE_REBUILD_MODE_CHANGED'"
          role="status"
          class="admin-field-help"
        >
          任务因索引方式变化而停止；恢复高质量索引后可刷新进展并选择是否重试。
        </p>
        <ElAlert
          v-if="rebuild?.write_locked"
          title="重建期间知识库写入已锁定。"
          type="warning"
        /><ElAlert
          v-if="rebuild?.status === 'UNKNOWN'"
          title="部分批次结果未知。请先刷新核对任务状态，再决定是否承担重复计费风险后重试。"
          type="warning"
          :closable="false"
        /><ElDescriptions v-if="rebuild" :column="1" border
          ><ElDescriptionsItem label="状态">{{ statusLabel(rebuild.status) }}</ElDescriptionsItem
          ><ElDescriptionsItem label="文档进度"
            >{{ rebuild.ready_documents }} / {{ rebuild.document_count
            }}<ElProgress
              v-if="rebuild.document_count > 0"
              :percentage="
                Math.max(
                  0,
                  Math.min(
                    100,
                    Math.round((rebuild.ready_documents / rebuild.document_count) * 100)
                  )
                )
              "
              :status="rebuild.status === 'FAILED' ? 'exception' : undefined" /></ElDescriptionsItem
          ><ElDescriptionsItem label="错误码">{{
            rebuild.error_code || '—'
          }}</ElDescriptionsItem></ElDescriptions
        ><ElForm
          label-position="top"
          @submit.prevent
          :disabled="busy"
          scroll-to-error
          @keydown.capture="guardFormEnter"
          ><ElFormItem label="目标向量模型"
            ><ElSelect
              v-described-by="formTipId + '-field-2'"
              :aria-describedby="formTipId + '-field-2'"
              v-model="modelId"
              :loading="modelsLoading"
              :disabled="modelsLoading"
              ><ElOption
                v-for="model in models"
                :key="model.id"
                :value="model.id"
                :label="model.name"
            /></ElSelect>
            <ElButton :loading="modelsLoading" :disabled="busy" @click="loadModels">
              刷新模型目录
            </ElButton>
            <FieldTip :id="formTipId + '-field-2'"
              >选择重建索引使用的向量模型，模型变化需要重新生成文档向量。</FieldTip
            >
          </ElFormItem></ElForm
        >
        <p v-if="rebuildRefresh" class="admin-field-help" role="status">
          重建结果已发布，知识库与文档信息尚未刷新。重新读取只查询发布结果，不会再次发布或重建。
          <ElButton link :disabled="busy" @click="retryRebuildRefresh">重新读取发布信息</ElButton>
        </p>
        <div class="actions">
          <ElButton :disabled="busy" @click="refreshRebuild">刷新重建进展</ElButton>
          <ElButton
            v-permission="['foundation.knowledge.create']"
            :disabled="
              busy ||
              rebuildRefresh ||
              rebuild?.write_locked ||
              !modelId ||
              effectiveIndexingMode !== 'high_quality'
            "
            @click="startRebuild"
            >开始重建</ElButton
          ><ElButton
            v-permission="['foundation.knowledge.cancel']"
            v-if="
              rebuild?.job_id &&
              ['QUEUED', 'RUNNING', 'READY', 'FAILED', 'UNKNOWN'].includes(rebuild.status)
            "
            :disabled="busy"
            @click="cancelRebuild"
            >取消重建</ElButton
          ><ElButton
            v-permission="['foundation.knowledge.run']"
            v-if="
              rebuild?.status === 'FAILED' ||
              rebuild?.status === 'UNKNOWN' ||
              rebuild?.status === 'CANCELLED'
            "
            :disabled="
              busy ||
              (rebuild?.error_code === 'KNOWLEDGE_REBUILD_MODE_CHANGED' &&
                effectiveIndexingMode !== 'high_quality')
            "
            @click="retryRebuild"
            >{{ rebuild?.status === 'CANCELLED' ? '继续重建' : '重试失败批次' }}</ElButton
          ><ElButton
            v-permission="['foundation.knowledge.publish']"
            v-if="rebuild?.status === 'READY'"
            type="primary"
            :disabled="
              busy ||
              publicationLocked ||
              rebuildModeConflict ||
              effectiveIndexingMode !== 'high_quality'
            "
            @click="publishRebuild"
            >发布重建结果</ElButton
          >
        </div></ElTabPane
      ></ElTabs
    >
    <ElDrawer
      v-model="preparationOpen"
      title="准备知识发布"
      size="760px"
      append-to-body
      class="admin-drawer"
      :before-close="closePreparation"
      :close-on-click-modal="false"
      :close-on-press-escape="!busy"
      :show-close="!busy"
    >
      <p class="admin-field-help">
        核对本次包含的就绪文档及来源版本，每次最多 2000 项。准备和发布仍可独立执行。
      </p>
      <section v-if="publicationPending" class="admin-card" aria-label="知识发布原操作">
        <p role="status">
          发布结果未知，原请求 ID：<code>{{ publicationPending.requestId }}</code
          >。
        </p>
        <p>
          原来源版本：{{ publicationPending.sources.join('、') }}；检索方式：{{
            publicationPending.policy.allowed_operators.join('、')
          }}。未查到回执不代表失败。
        </p>
        <ElButton v-if="can('foundation.knowledge.read')" :loading="busy" @click="checkPublication"
          >核对原操作</ElButton
        >
      </section>
      <p v-if="publicationStorageError" role="status">{{ publicationStorageError }}</p>
      <p v-if="scopePaged" class="admin-field-help">
        当前就绪文档较多，可选择最新创建的 2000 个就绪文档，或按页勾选；切换页面保留已有选择。
      </p>
      <p v-if="scopeSelection.length > 2000" class="admin-field-help" role="alert">
        已选超过 2000 项，请取消部分选择后再准备。
      </p>
      <p v-if="prepareUnknown" role="status">准备结果尚未确认。原范围已冻结，仅可核对修订列表。</p>
      <p v-if="!publicationPending && !scopeDocuments.length" role="status">
        暂无就绪文档，请先上传或完成文档索引。
      </p>
      <ElButton
        v-if="scopeDocuments.length"
        :disabled="busy || prepareUnknown || publicationLocked"
        @click="toggleVisibleScope"
        >{{ allVisibleSelected ? '取消选择当前显示文档' : '选择当前显示文档' }}</ElButton
      >
      <ElButton
        v-if="scopePaged"
        :disabled="busy || prepareUnknown || publicationLocked"
        @click="selectLatestSources"
      >
        选择最新 2000 项
      </ElButton>
      <ElCheckboxGroup
        v-model="scopeSelection"
        :disabled="busy || prepareUnknown || publicationLocked"
        aria-label="本次知识发布范围"
      >
        <ElTable :data="scopeDocuments" row-key="id">
          <ElTableColumn label="包含" width="72"
            ><template #default="{ row }"
              ><ElCheckbox
                :value="row.source_version"
                :aria-label="'包含文档 ' + row.title" /></template
          ></ElTableColumn>
          <ElTableColumn prop="title" label="文档" min-width="200" />
          <ElTableColumn
            prop="source_version"
            label="来源版本"
            min-width="240"
            show-overflow-tooltip
          />
        </ElTable>
      </ElCheckboxGroup>
      <ElPagination
        v-if="scopePaged"
        :current-page="scopePage"
        :page-size="100"
        :total="scopeTotal"
        layout="total,prev,pager,next"
        :disabled="busy || prepareUnknown || publicationLocked"
        @current-change="changeScopePage"
      />
      <template #footer
        ><span role="status">已选 {{ scopeSelection.length }} 项文档</span>
        <ElButton :disabled="busy" @click="closePreparation">关闭</ElButton>
        <ElButton
          v-permission.all="['foundation.knowledge.create', 'foundation.knowledge.update']"
          type="primary"
          :loading="busy"
          :disabled="
            !scopeSelection.length ||
            scopeSelection.length > 2000 ||
            prepareUnknown ||
            prepareRefresh ||
            publicationLocked
          "
          @click="prepareRevision"
          >确认范围并准备</ElButton
        >
        <ElButton
          v-if="canPrepareAndPublish"
          type="primary"
          :loading="busy"
          :disabled="
            !scopeSelection.length ||
            scopeSelection.length > 2000 ||
            prepareUnknown ||
            prepareRefresh ||
            publicationLocked ||
            !!rebuild?.write_locked
          "
          @click="prepareAndPublish"
          >确认范围并发布</ElButton
        >
      </template>
    </ElDrawer>
    <ElDrawer
      v-model="documentOpen"
      :before-close="closeDrawer0"
      :title="document?.title || '文档详情'"
      size="900px"
      append-to-body
      direction="rtl"
      :close-on-click-modal="false"
      :close-on-press-escape="!busy"
      :show-close="!busy"
      class="admin-drawer"
      ><FeedbackNotice v-if="error" :title="error" type="error" :closable="false" role="alert" />
      <ElAlert
        v-if="documentRefresh"
        title="修改已保存，最新文档与分块尚未读取完成。请重新读取后继续编辑；此操作不会再次提交修改。"
        type="warning"
        :closable="false"
      />
      <ElButton v-if="documentRefresh" :disabled="busy" @click="retryDocumentRefresh"
        >重新读取已保存内容</ElButton
      >
      <ElTabs v-model="documentTab"
        ><ElTabPane label="原文" name="source"
          ><ElInput
            v-described-by="formTipId + '-group-0'"
            :aria-describedby="formTipId + '-group-0'"
            v-model="title"
            :disabled="busy || !!documentRefresh || rebuildRefresh || rebuild?.write_locked"
            placeholder="文档标题"
          /><CodeEditor
            :described-by="formTipId + '-group-0'"
            v-model="content"
            language="markdown"
            height="440px"
            :readonly="busy || !!documentRefresh || rebuildRefresh || rebuild?.write_locked"
          />
          <p class="muted">来源版本：{{ document?.source_version }}</p>
          <FieldTip :id="formTipId + '-group-0'"
            >标题用于识别文档；正文使用 Markdown
            编辑。保存后按当前知识库配置更新内容，索引重建锁定时不可修改。</FieldTip
          > </ElTabPane
        ><ElTabPane label="分块" name="chunks"
          ><ElButton v-if="!chunksLoaded" :disabled="busy" @click="readChunks">读取分块</ElButton>
          <ElCard v-for="chunk in chunks" :key="chunk.id" style="margin-bottom: 12px"
            ><div class="toolbar">
              <span
                >分块 {{ chunk.chunk_index }} ·
                {{ chunk.has_embedding ? '已向量化' : '无向量' }}</span
              ><ElButton
                v-permission="['foundation.knowledge.update']"
                :disabled="busy || !!documentRefresh || rebuildRefresh || rebuild?.write_locked"
                @click="saveChunk(chunk)"
                >保存分块</ElButton
              ><ElButton
                v-permission="['foundation.knowledge.delete']"
                type="danger"
                :disabled="busy || !!documentRefresh || rebuildRefresh || rebuild?.write_locked"
                @click="removeChunk(chunk)"
                >删除分块</ElButton
              >
            </div>
            <ElInput
              v-described-by="formTipId + '-group-1' + '-' + encodeURIComponent(String(chunk.id))"
              :aria-describedby="
                formTipId + '-group-1' + '-' + encodeURIComponent(String(chunk.id))
              "
              v-model="chunk.content"
              :disabled="busy || !!documentRefresh || rebuildRefresh || rebuild?.write_locked"
              type="textarea"
              :rows="5"
            />
            <FieldTip :id="formTipId + '-group-1' + '-' + encodeURIComponent(String(chunk.id))"
              >编辑该分块的知识正文，保存后更新对应分块；重建锁定时不可修改。</FieldTip
            > </ElCard
          ><ElPagination
            v-if="chunkTotal > 20"
            :disabled="busy || !!documentRefresh"
            :current-page="chunkPage"
            :page-size="20"
            :total="chunkTotal"
            @current-change="changeChunkPage" /></ElTabPane></ElTabs
      ><template #footer
        ><span v-if="documentTab === 'chunks'" class="admin-muted"
          >分块逐项保存，关闭前请检查未保存修改。</span
        ><ElButton @click="closeDrawer0" :disabled="busy">关闭</ElButton
        ><ElButton
          v-if="documentTab === 'source'"
          v-permission="['foundation.knowledge.update']"
          type="primary"
          :loading="busy"
          :disabled="!!documentRefresh || rebuildRefresh || rebuild?.write_locked"
          @click="saveDocument"
          >保存原文</ElButton
        ></template
      ></ElDrawer
    >
  </AdminPage>
</template>
<script setup lang="ts">
import { HttpClientError } from '@skillforge/shared/utils/http'
import FeedbackNotice from '@skillforge/shared/feedback/FeedbackNotice.vue'
import { vDescribedBy } from '@skillforge/shared/directives/describedBy'

import FieldTip from '@skillforge/shared/components/FieldTip.vue'

import { guardFormEnter } from '@skillforge/shared/admin'
import { statusLabel } from '@skillforge/shared/admin'
import type { UploadFile, UploadInstance } from 'element-plus'
import { computed, onScopeDispose, ref, watch, useId } from 'vue'
import { useAuthStore } from '../../stores/auth'
import { useRoute } from 'vue-router'
import {
  AdminPage,
  useTask,
  useResource,
  useUnsavedChanges,
  useDrawerClose
} from '@skillforge/shared/admin'
import CodeEditor from '@skillforge/shared/components/CodeEditor.vue'
import { confirm, message } from '@skillforge/shared/feedback'
import {
  api,
  get,
  getSessionId,
  post,
  type KnowledgeBaseDetail,
  type KnowledgeDocumentItem,
  type KnowledgeDocumentDetail,
  type KnowledgeChunkItem,
  type KnowledgeBaseRevision,
  type KnowledgePrepareAndPublishReceipt,
  type KnowledgeUploadReceipt,
  type KnowledgeSearchParams,
  type ModelSummary
} from '../../api'
import { createKnowledgeRebuildApi, type KnowledgeRebuild } from '../../api/knowledgeRebuild'
import { peekInitialFile, takeInitialFile } from './knowledgeInitialFile'
import { notifyKnowledgePublished } from './knowledgePublicationEvents'
const route = useRoute(),
  id = computed(() => String(route.params.id)),
  base = ref<KnowledgeBaseDetail>(),
  page = ref(1),
  pageSize = ref(20)
const documentTitleQuery = ref(''),
  documentStatus = ref('')
const appliedDocumentFilters = ref({ titleQuery: '', status: '' })
const documentFilterFailed = ref(false)
const documentMutationRefresh = ref(false)
let documentReadGeneration = 0
const {
  data: documents,
  loading,
  error: loadError,
  reload: loadDocuments,
  invalidate: invalidateDocuments
} = useResource(async () => {
  const generation = ++documentReadGeneration
  const current = rebuildReadCurrent()
  try {
    const result = await api.knowledge.listDocuments(id.value, {
      page: page.value,
      page_size: pageSize.value,
      ...(appliedDocumentFilters.value.titleQuery
        ? { titleQuery: appliedDocumentFilters.value.titleQuery }
        : {}),
      ...(appliedDocumentFilters.value.status
        ? { status: appliedDocumentFilters.value.status }
        : {})
    })
    if (!current()) throw 'cancel'
    return result
  } catch (reason) {
    if (!current()) {
      if (reason instanceof HttpClientError) reason.claimFeedback()
      if (generation === documentReadGeneration) {
        documentReadGeneration++
        invalidateDocuments()
      }
      throw 'cancel'
    }
    throw reason
  }
})
const { busy, error, run } = useTask()
const auth = useAuthStore()
const can = (permission: string) => auth.user?.permissions.includes(permission) === true
const initialOwner = computed(() =>
  auth.user?.tenant_id && auth.user?.sub ? JSON.stringify([auth.user.tenant_id, auth.user.sub]) : ''
)
const initialPending = computed(() =>
  peekInitialFile(initialOwner.value, auth.identityVersion, id.value)
)
function discardInitialFile() {
  takeInitialFile(initialOwner.value, auth.identityVersion, id.value)
  if (uploadNotStarted.value) {
    uploadStorageError.value = ''
    uploadNotStarted.value = false
  }
}
const canPrepareAndPublish = computed(() =>
  [
    'foundation.knowledge.read',
    'foundation.knowledge.create',
    'foundation.knowledge.update',
    'foundation.knowledge.publish'
  ].every(can)
)
const publicationOwner = computed(() =>
  auth.user?.tenant_id && auth.user?.sub
    ? JSON.stringify([auth.user.tenant_id, auth.user.sub, id.value])
    : ''
)
const publicationKey = (owner: string) => `skillforge.knowledge-publication.${owner}`
const uploadKey = (owner: string) => `skillforge.knowledge-upload.${owner}`
const uploadUnknown = ref<{ name: string; size: number; requestId?: string }>()
const uploadStorageError = ref('')
const uploadNotStarted = ref(false)
const uploadRequestId = ref('')
const historicalUpload = ref<KnowledgeUploadReceipt>()
watch(uploadRequestId, () => {
  historicalUpload.value = undefined
})
const uploadBlocked = computed(() => !!uploadUnknown.value || !!uploadStorageError.value)
type PublicationPending = {
  requestId: string
  kbId: string
  sources: string[]
  policy: typeof policy
}
const publicationPending = ref<PublicationPending>()
const publicationStorageError = ref('')
const publicationRequestId = ref('')
const publicationReceipt = ref<KnowledgePrepareAndPublishReceipt>()
const publicationLocked = computed(
  () => !!publicationPending.value || !!publicationStorageError.value
)
let disposed = false
let autoCheckedUpload = ''
let autoCheckedPublication = ''
onScopeDispose(() => {
  disposed = true
})
watch(
  publicationOwner,
  (owner) => {
    uploadUnknown.value = undefined
    autoCheckedUpload = ''
    autoCheckedPublication = ''
    uploadStorageError.value = ''
    uploadNotStarted.value = false
    uploadRequestId.value = ''
    historicalUpload.value = undefined
    publicationPending.value = undefined
    publicationStorageError.value = ''
    publicationReceipt.value = undefined
    publicationRequestId.value = ''
    if (!owner) return
    try {
      const upload = sessionStorage.getItem(uploadKey(owner))
      if (upload) {
        const value = JSON.parse(upload) as { name: string; size: number; requestId?: string }
        if (
          typeof value.name !== 'string' ||
          !value.name ||
          !Number.isSafeInteger(value.size) ||
          value.size < 0 ||
          (value.requestId !== undefined && !/^[0-9a-f-]{36}$/i.test(value.requestId))
        )
          throw Error('invalid upload')
        uploadUnknown.value = value
      }
    } catch {
      uploadStorageError.value = '无法读取上传记录，请恢复浏览器存储后重新进入页面。'
    }
    try {
      const raw = sessionStorage.getItem(publicationKey(owner))
      if (!raw) return
      const value = JSON.parse(raw) as PublicationPending
      if (
        !value ||
        value.kbId !== id.value ||
        !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(value.requestId) ||
        !Array.isArray(value.sources) ||
        !value.sources.length ||
        !value.sources.every((item) => typeof item === 'string' && !!item) ||
        !value.policy ||
        !Array.isArray(value.policy.allowed_operators)
      )
        throw Error('invalid')
      publicationPending.value = value
    } catch {
      publicationStorageError.value = '无法读取原知识发布记录，请恢复浏览器存储后重新进入页面。'
    }
  },
  { immediate: true, flush: 'sync' }
)
async function findDocuments() {
  if (loading.value || busy.value || uploadRefresh.value || documentMutationRefresh.value) return
  const previous = { page: page.value, filters: appliedDocumentFilters.value }
  appliedDocumentFilters.value = {
    titleQuery: documentTitleQuery.value.trim(),
    status: documentStatus.value
  }
  page.value = 1
  const request = loadDocuments()
  const generation = documentReadGeneration
  const result = await request
  if (generation !== documentReadGeneration) return
  if (result === undefined) {
    page.value = previous.page
    appliedDocumentFilters.value = previous.filters
    documentFilterFailed.value = true
  } else documentFilterFailed.value = false
}
async function clearDocumentFilters() {
  documentTitleQuery.value = ''
  documentStatus.value = ''
  await findDocuments()
}
async function changeDocumentPage(nextPage: number, nextSize = pageSize.value) {
  if (
    loading.value ||
    busy.value ||
    documentMutationRefresh.value ||
    (nextPage === page.value && nextSize === pageSize.value)
  )
    return
  const previous = { page: page.value, size: pageSize.value }
  page.value = nextPage
  pageSize.value = nextSize
  const request = loadDocuments()
  const generation = documentReadGeneration
  const result = await request
  if (generation !== documentReadGeneration) return
  if (result === undefined) {
    page.value = previous.page
    pageSize.value = previous.size
  }
}
const activeTab = ref('documents'),
  searched = ref(false)
const {
  data: models,
  loading: modelsLoading,
  error: modelError,
  reload: loadModels
} = useResource<ModelSummary[]>(async () =>
  (await api.settings.availableModels()).filter((model) => model.kind === 'EMBEDDING')
)
watch(activeTab, (tab) => {
  if (tab === 'rebuild' && !models.value && !modelsLoading.value) void loadModels()
})
const revisions = ref<KnowledgeBaseRevision[]>([]),
  rebuild = ref<KnowledgeRebuild>(),
  rebuildRefresh = ref(false),
  rebuildModeConflict = ref(false),
  rebuildApi = createKnowledgeRebuildApi({ get, post }),
  modelId = ref('')
const currentRevision = computed(() =>
  revisions.value.find((revision) => String(revision.id) === String(base.value?.active_revision_id))
)
const policy = {
  model_invocable: true,
  user_invocable: true,
  allowed_operators: ['VECTOR', 'KEYWORD'],
  freshness_policy: 'PINNED',
  citation_required: true
}
async function load() {
  const target = id.value
  const identity = auth.identityVersion
  const current = rebuildReadCurrent()
  const guard = <T,>(promise: Promise<T>) =>
    promise.catch((reason: unknown) => {
      if (!current()) {
        if (reason instanceof HttpClientError) reason.claimFeedback()
        throw 'cancel'
      }
      throw reason
    })
  const loaded = await run(async () => {
    const [b, r, j] = await Promise.all([
      guard(api.knowledge.getBase(target)),
      guard(api.knowledge.listRevisions(target)),
      guard(rebuildApi.detail(target))
    ])
    if (!current()) return false
    base.value = b
    revisions.value = r.items
    rebuild.value = j
    if (uploadRefresh.value) {
      await readUploadedDocuments()
      if (!current()) return false
      if (!uploadRefresh.value) {
        rebuildRefresh.value = false
        documentMutationRefresh.value = false
      }
    } else {
      const result = await loadDocuments()
      if (!current()) return false
      if (result !== undefined) {
        rebuildRefresh.value = false
        documentMutationRefresh.value = false
      }
    }
    return true
  })
  if (
    loaded &&
    !disposed &&
    !busy.value &&
    id.value === target &&
    auth.identityVersion === identity &&
    can('foundation.knowledge.read') &&
    publicationPending.value?.requestId &&
    autoCheckedPublication !== publicationPending.value.requestId
  ) {
    autoCheckedPublication = publicationPending.value.requestId
    await checkPublication()
  }
  if (
    loaded &&
    !disposed &&
    !busy.value &&
    id.value === target &&
    auth.identityVersion === identity &&
    can('foundation.knowledge.read') &&
    uploadUnknown.value?.requestId &&
    autoCheckedUpload !== uploadUnknown.value.requestId
  ) {
    autoCheckedUpload = uploadUnknown.value.requestId
    await checkUpload(true)
    return
  }
  if (
    !loaded ||
    disposed ||
    busy.value ||
    id.value !== target ||
    auth.identityVersion !== identity ||
    rebuildRefresh.value ||
    rebuild.value?.write_locked ||
    uploadBlocked.value ||
    !can('foundation.knowledge.create')
  )
    return
  const file = peekInitialFile(initialOwner.value, identity, target)
  if (file) await uploadFile(file, () => takeInitialFile(initialOwner.value, identity, target))
}
function retryInitialUpload() {
  if (uploadNotStarted.value) {
    uploadStorageError.value = ''
    uploadNotStarted.value = false
  }
  void load()
}
const pendingTargetLoad = ref(false)
function loadCurrentTarget() {
  if (!pendingTargetLoad.value || busy.value || !can('foundation.knowledge.read')) return
  pendingTargetLoad.value = false
  void load()
}
watch(
  [
    id,
    () => auth.identityVersion,
    () => auth.sessionIdentity,
    initialOwner,
    () => can('foundation.knowledge.read')
  ],
  () => {
    documentReadGeneration++
    invalidateDocuments()
    documents.value = undefined
    loadError.value = ''
    base.value = undefined
    revisions.value = []
    rebuild.value = undefined
    page.value = 1
    rebuildModeConflict.value = false
    documentTitleQuery.value = ''
    documentStatus.value = ''
    appliedDocumentFilters.value = { titleQuery: '', status: '' }
    documentFilterFailed.value = false
    documentMutationRefresh.value = false
    pendingTargetLoad.value = true
    loadCurrentTarget()
  },
  { immediate: true, flush: 'sync' }
)
watch(busy, (working) => {
  if (!working) loadCurrentTarget()
})
const uploadRefresh = ref(false)
async function readUploadedDocuments() {
  const previous = { page: page.value, filters: appliedDocumentFilters.value }
  page.value = 1
  appliedDocumentFilters.value = { titleQuery: '', status: '' }
  const request = loadDocuments()
  const generation = documentReadGeneration
  const result = await request
  if (generation !== documentReadGeneration) return
  if (result === undefined) {
    page.value = previous.page
    appliedDocumentFilters.value = previous.filters
  } else {
    uploadRefresh.value = false
    documentTitleQuery.value = ''
    documentStatus.value = ''
    documentFilterFailed.value = false
  }
}
async function retryUploadRefresh() {
  await run(readUploadedDocuments)
}
const fileUpload = ref<UploadInstance>()
async function upload(selection: UploadFile) {
  const file = selection.raw
  fileUpload.value?.clearFiles()
  if (file && !initialPending.value) await uploadFile(file)
}
async function uploadFile(file: File, onDispatch?: () => void) {
  if (busy.value || uploadBlocked.value || rebuildRefresh.value || rebuild.value?.write_locked)
    return
  await run(async () => {
    const owner = publicationOwner.value
    const identity = auth.identityVersion
    if (!owner) throw Error('当前登录会话无法保存上传记录，请重新登录')
    const key = uploadKey(owner)
    const original = { name: file.name, size: file.size, requestId: crypto.randomUUID() }
    try {
      sessionStorage.setItem(key, JSON.stringify(original))
    } catch {
      uploadNotStarted.value = true
      uploadStorageError.value = '无法保存上传记录，未发送文件；请恢复浏览器存储。'
      throw Error(uploadStorageError.value)
    }
    const data = new FormData()
    data.append('file', file, file.name)
    onDispatch?.()
    try {
      const receipt = await api.knowledge.uploadFile(id.value, data, original.requestId)
      if (disposed || publicationOwner.value !== owner || auth.identityVersion !== identity) return
      if (!(await acceptUploadReceipt(receipt, original, owner, identity))) return
    } catch (cause) {
      if (disposed || publicationOwner.value !== owner || auth.identityVersion !== identity) return
      if (
        cause instanceof HttpClientError &&
        (cause.code === 'KNOWLEDGE_UPLOAD_REJECTED' ||
          (cause.status === 422 && cause.code === 'KNOWLEDGE_DOCUMENT_CHUNK_LIMIT'))
      ) {
        try {
          sessionStorage.removeItem(key)
        } catch {
          uploadStorageError.value = '无法清除上传记录，请恢复浏览器存储后重新进入页面。'
        }
      } else uploadUnknown.value = original
      throw cause
    }
  })
}
function validUploadReceipt(
  receipt: KnowledgeUploadReceipt | undefined,
  requestId: string
): receipt is KnowledgeUploadReceipt {
  return (
    !!receipt &&
    receipt.request_id === requestId &&
    ['PROCESSING', 'REJECTED', 'SUCCEEDED'].includes(receipt.status) &&
    (receipt.status !== 'SUCCEEDED' ||
      (typeof receipt.document?.document_id === 'string' &&
        !!receipt.document.document_id &&
        typeof receipt.document.source_version === 'string' &&
        !!receipt.document.source_version &&
        Number.isSafeInteger(receipt.document.chunks) &&
        receipt.document.chunks >= 0)) &&
    (receipt.status !== 'REJECTED' ||
      (typeof receipt.error_message === 'string' && !!receipt.error_message))
  )
}
async function acceptUploadReceipt(
  receipt: KnowledgeUploadReceipt | undefined,
  original: { name: string; size: number; requestId: string },
  owner: string,
  identity: number,
  automatic = false
) {
  if (!validUploadReceipt(receipt, original.requestId)) {
    uploadUnknown.value = original
    if (!automatic) message.warning('未取得完整的原上传回执，请稍后核对')
    return false
  }
  if (receipt.status === 'PROCESSING') {
    uploadUnknown.value = original
    if (!automatic) message.warning('原上传仍在处理，请稍后核对')
    return false
  }
  if (disposed || publicationOwner.value !== owner || auth.identityVersion !== identity)
    return false
  try {
    sessionStorage.removeItem(uploadKey(owner))
  } catch {
    uploadStorageError.value = `原上传已有最终回执（请求 ID：${original.requestId}，状态：${receipt.status === 'SUCCEEDED' ? '已完成' : '已拒绝'}），但记录无法清除；请恢复浏览器存储。`
    return false
  }
  uploadUnknown.value = undefined
  if (receipt.status === 'REJECTED') {
    message.error(`文件上传未完成：${receipt.error_message}`)
    return false
  }
  message.success('文件上传已提交')
  activeTab.value = 'documents'
  uploadRefresh.value = true
  await readUploadedDocuments()
  return true
}
async function checkUpload(automatic = false) {
  const original = uploadUnknown.value
  const owner = publicationOwner.value
  const identity = auth.identityVersion
  if (!original?.requestId || !owner) return
  const request = { ...original, requestId: original.requestId }
  await run(async () => {
    const receipt = await api.knowledge.uploadResult(id.value, request.requestId)
    if (
      disposed ||
      publicationOwner.value !== owner ||
      auth.identityVersion !== identity ||
      uploadUnknown.value?.requestId !== request.requestId
    )
      return
    await acceptUploadReceipt(receipt, request, owner, identity, automatic)
  })
}
async function checkHistoricalUpload() {
  const requestId = uploadRequestId.value.trim().toLowerCase()
  if (!/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(requestId)) {
    message.warning('请输入有效的上传请求 ID')
    return
  }
  const owner = publicationOwner.value,
    identity = auth.identityVersion
  await run(async () => {
    const receipt = await api.knowledge.uploadResult(id.value, requestId)
    if (
      disposed ||
      publicationOwner.value !== owner ||
      auth.identityVersion !== identity ||
      uploadRequestId.value.trim().toLowerCase() !== requestId
    )
      return
    if (!validUploadReceipt(receipt, requestId)) {
      historicalUpload.value = undefined
      message.warning('尚未查到完整的原上传回执，不能据此认定请求失败')
      return
    }
    historicalUpload.value = receipt
  })
}
const documentOpen = ref(false),
  documentTab = ref('source'),
  document = ref<KnowledgeDocumentDetail>(),
  content = ref(''),
  title = ref(''),
  saved = ref(''),
  chunks = ref<KnowledgeChunkItem[]>([]),
  chunksLoaded = ref(false),
  chunkPage = ref(1),
  chunkTotal = ref(0),
  savedChunks = ref(''),
  documentRefresh = ref<() => Promise<void>>()
async function finishDocumentRefresh() {
  await documentRefresh.value?.()
  documentRefresh.value = undefined
}
async function retryDocumentRefresh() {
  await run(finishDocumentRefresh)
}
const documentDirty = computed(
  () =>
    documentOpen.value &&
    (JSON.stringify([title.value, content.value]) !== saved.value ||
      JSON.stringify(chunks.value) !== savedChunks.value)
)
useUnsavedChanges({
  dirty: () =>
    documentDirty.value ||
    !!publicationPending.value ||
    !!uploadUnknown.value ||
    !!initialPending.value,
  busy: () => busy.value,
  leaveMessage: () =>
    uploadUnknown.value
      ? `文件“${uploadUnknown.value.name}”的上传结果未知；关闭标签页可能丢失原文件记录。`
      : initialPending.value
        ? `文件“${initialPending.value.name}”尚未上传；刷新或关闭页面后需要重新选择。`
        : publicationPending.value
          ? `知识发布结果未知，请记录原请求 ID：${publicationPending.value.requestId}。`
          : undefined
})
async function loadChunks() {
  if (!document.value) return
  const value = await api.knowledge.listChunks(document.value.id, {
    page: chunkPage.value,
    page_size: 20
  })
  chunks.value = value.items
  chunkTotal.value = value.total
  savedChunks.value = JSON.stringify(chunks.value)
  chunksLoaded.value = true
}
async function readChunks() {
  await run(loadChunks)
}
watch(documentTab, (tab) => {
  if (tab === 'chunks' && documentOpen.value && !chunksLoaded.value) void readChunks()
})
async function changeChunkPage(nextPage: number) {
  if (busy.value || documentRefresh.value || nextPage === chunkPage.value) return
  if (
    JSON.stringify(chunks.value) !== savedChunks.value &&
    !(await confirm({
      title: '切换分块页',
      content: '当前页分块有未保存修改，切换会丢弃这些修改。',
      confirmText: '丢弃并切换'
    }))
  )
    return
  await run(async () => {
    const previous = chunkPage.value
    chunkPage.value = nextPage
    try {
      await loadChunks()
    } catch (reason) {
      chunkPage.value = previous
      throw reason
    }
  })
}
async function editDocument(documentId: string) {
  await run(async () => {
    documentTab.value = 'source'
    document.value = await api.knowledge.getDocument(documentId)
    content.value = document.value.content
    title.value = document.value.title
    saved.value = JSON.stringify([title.value, content.value])
    chunkPage.value = 1
    chunks.value = []
    chunkTotal.value = 0
    savedChunks.value = JSON.stringify([])
    chunksLoaded.value = false
    documentRefresh.value = undefined
    documentOpen.value = true
  })
}
async function saveDocument() {
  if (documentRefresh.value) return
  if (
    JSON.stringify(chunks.value) !== savedChunks.value &&
    !(await confirm({
      title: '保存文档',
      content:
        '保存文档会重新生成分块，当前未保存的分块修改将丢弃。请先保存需要保留的分块修改，或确认继续。',
      confirmText: '丢弃分块修改并保存'
    }))
  )
    return
  await run(async () => {
    const doc = document.value
    if (!doc?.source_version)
      throw Error('尚未读到文档版本，请先保留编辑内容，再关闭并重新打开文档核对')
    const submitted = { title: title.value, content: content.value }
    document.value = await api.knowledge.updateDocument(doc.id, doc.source_version, submitted)
    saved.value = JSON.stringify([submitted.title, submitted.content])
    message.success('文档已保存')
    documentRefresh.value = async () => {
      if (chunksLoaded.value) await loadChunks()
      await loadDocuments()
    }
    await finishDocumentRefresh()
  })
}
async function refreshDocumentList() {
  if ((await loadDocuments()) !== undefined) documentMutationRefresh.value = false
}
async function reindex(row: KnowledgeDocumentItem) {
  if (documentMutationRefresh.value) return
  await run(async () => {
    if (!row.source_version)
      throw Error('尚未读到文档版本，请刷新文档列表后再选择需要重新索引的文档')
    await api.knowledge.reindexDocument(row.id, row.source_version)
    message.success('文档重建已提交')
    documentMutationRefresh.value = true
    await refreshDocumentList()
  })
}
async function removeDocument(row: KnowledgeDocumentItem) {
  if (documentMutationRefresh.value) return
  if (
    await confirm({
      title: '删除文档',
      confirmText: '删除文档',
      danger: true,
      content: '确定删除“' + row.title + '”及其分块？'
    })
  )
    await run(async () => {
      if (!row.source_version) throw Error('尚未读到文档版本，请刷新文档列表后再核对要删除的文档')
      await api.knowledge.removeDocument(row.id, row.source_version)
      message.success('文档已删除')
      documentMutationRefresh.value = true
      await refreshDocumentList()
    })
}
// 分块写入会重建整份文档版本及分块 ID，按服务端稳定顺序迁移本页未提交的内容。
async function refreshAfterChunkWrite(
  changed: KnowledgeChunkItem,
  deleted: boolean,
  submitted?: string
) {
  if (!document.value) return
  const baseline = JSON.parse(savedChunks.value) as KnowledgeChunkItem[]
  const drafts = new Map<number, string>()
  for (const item of chunks.value) {
    if (deleted && item.id === changed.id) continue
    const original = baseline.find((row) => row.id === item.id)
    const savedContent = item.id === changed.id ? submitted : original?.content
    if (item.content !== savedContent) {
      drafts.set(
        item.chunk_index - (deleted && item.chunk_index > changed.chunk_index ? 1 : 0),
        item.content
      )
    }
  }
  const nextPage =
    deleted && chunks.value.length === 1 ? Math.max(1, chunkPage.value - 1) : chunkPage.value
  const [nextDocument, nextChunks] = await Promise.all([
    api.knowledge.getDocument(document.value.id),
    api.knowledge.listChunks(document.value.id, { page: nextPage, page_size: 20 })
  ])
  if (nextChunks.items.some((item) => item.source_version !== nextDocument.source_version))
    throw Error('文档内容已更新，当前编辑仍保留。请先备份未保存内容，再重新打开文档核对最新版本。')
  const [savedTitle, savedContent] = JSON.parse(saved.value) as [string, string]
  if (title.value === savedTitle) title.value = nextDocument.title
  if (content.value === savedContent) content.value = nextDocument.content
  saved.value = JSON.stringify([nextDocument.title, nextDocument.content])
  document.value = nextDocument
  chunkPage.value = nextPage
  chunkTotal.value = nextChunks.total
  savedChunks.value = JSON.stringify(nextChunks.items)
  chunks.value = nextChunks.items.map((item) => ({
    ...item,
    content: drafts.get(item.chunk_index) ?? item.content
  }))
}
async function saveChunk(chunk: KnowledgeChunkItem) {
  if (documentRefresh.value) return
  await run(async () => {
    const submittedContent = chunk.content
    await api.knowledge.updateChunk(chunk.id, chunk.source_version, { content: submittedContent })
    message.success('分块已保存')
    documentRefresh.value = () => refreshAfterChunkWrite(chunk, false, submittedContent)
    await finishDocumentRefresh()
  })
}
async function removeChunk(chunk: KnowledgeChunkItem) {
  if (documentRefresh.value) return
  if (
    await confirm({
      title: '删除分块',
      confirmText: '删除分块',
      danger: true,
      content: '该分块将不再参与检索。'
    })
  )
    await run(async () => {
      await api.knowledge.removeChunk(chunk.id, chunk.source_version)
      message.success('分块已删除')
      documentRefresh.value = () => refreshAfterChunkWrite(chunk, true)
      await finishDocumentRefresh()
    })
}
const query = ref(''),
  topK = ref<number>(),
  overrideSearch = ref(false),
  searchMode = ref(''),
  thresholdMode = ref<boolean | ''>(''),
  scoreThreshold = ref<number>(),
  vectorWeight = ref<number>(),
  rerankMode = ref<boolean | ''>(''),
  sourceVersion = ref(''),
  lastSearchRequest = ref<KnowledgeSearchParams>(),
  lastSearchMode = ref(''),
  results = ref<Array<{ content: string; score: number; chunk_id: string; document_id: string }>>(
    []
  )
const effectiveIndexingMode = computed(
  () => base.value?.effective_indexing_mode ?? base.value?.indexing_mode
)
const economicalIndex = computed(() => effectiveIndexingMode.value === 'economical')
const effectiveSearchMode = computed(() =>
  economicalIndex.value ? 'full_text' : searchMode.value || base.value?.retrieval_mode
)
const supportsThreshold = computed(
  () => effectiveSearchMode.value === 'semantic' || effectiveSearchMode.value === 'hybrid'
)
const thresholdActive = computed(() =>
  thresholdMode.value === '' ? base.value?.score_threshold_enabled : thresholdMode.value
)
watch(id, () => {
  overrideSearch.value = false
  searchMode.value = ''
  thresholdMode.value = ''
  rerankMode.value = ''
  sourceVersion.value = ''
  topK.value = undefined
  scoreThreshold.value = undefined
  vectorWeight.value = undefined
  results.value = []
  searched.value = false
  lastSearchRequest.value = undefined
})
async function search() {
  await run(async () => {
    if (!query.value.trim()) throw Error('请输入检索问题')
    const request: KnowledgeSearchParams = { kb_id: id.value, query: query.value }
    if (overrideSearch.value) {
      if (topK.value !== undefined) request.top_k = topK.value
      if (searchMode.value && !economicalIndex.value) request.retrieval_mode = searchMode.value
      if (supportsThreshold.value) {
        if (thresholdMode.value !== '') request.score_threshold_enabled = thresholdMode.value
        if (thresholdActive.value && scoreThreshold.value !== undefined)
          request.score_threshold = scoreThreshold.value
      }
      if (effectiveSearchMode.value === 'hybrid' && vectorWeight.value !== undefined)
        request.vector_weight = vectorWeight.value
      if (rerankMode.value !== '') request.rerank_enabled = rerankMode.value
      if (sourceVersion.value.trim()) request.source_version = sourceVersion.value.trim()
    }
    const response = await api.knowledge.search(request)
    results.value = response.items
    lastSearchRequest.value = request
    lastSearchMode.value = economicalIndex.value
      ? '全文检索（经济索引）'
      : request.retrieval_mode || '沿用知识库设置'
    searched.value = true
  })
}
const preparationOpen = ref(false),
  scopeDocuments = ref<KnowledgeDocumentItem[]>([]),
  scopeSelection = ref<string[]>([]),
  scopeSaved = ref('')
const scopePage = ref(1),
  scopeTotal = ref(0),
  scopePaged = ref(false)
const allVisibleSelected = computed(
  () =>
    scopeDocuments.value.length > 0 &&
    scopeDocuments.value.every((item) => scopeSelection.value.includes(item.source_version!))
)
function toggleVisibleScope() {
  const visible = new Set(scopeDocuments.value.map((item) => item.source_version!))
  scopeSelection.value = allVisibleSelected.value
    ? scopeSelection.value.filter((source) => !visible.has(source))
    : [...new Set([...scopeSelection.value, ...visible])]
}
async function selectLatestSources() {
  if (busy.value || prepareUnknown.value || publicationLocked.value || !scopePaged.value) return
  const owner = publicationOwner.value,
    identity = auth.identityVersion,
    baseId = id.value
  const previous = JSON.stringify(scopeSelection.value)
  if (
    scopeSelection.value.length &&
    !(await confirm({
      title: '替换当前选择',
      confirmText: '选择最新 2000 项',
      content: '将用当前最新创建的最多 2000 个就绪文档替换已选范围；选择后仍可逐页调整。'
    }))
  )
    return
  if (
    disposed ||
    publicationOwner.value !== owner ||
    auth.identityVersion !== identity ||
    id.value !== baseId ||
    !preparationOpen.value ||
    previous !== JSON.stringify(scopeSelection.value)
  )
    return
  await run(async () => {
    const sources = await api.knowledge.latestReadySources(baseId)
    if (
      disposed ||
      publicationOwner.value !== owner ||
      auth.identityVersion !== identity ||
      id.value !== baseId ||
      !preparationOpen.value ||
      previous !== JSON.stringify(scopeSelection.value)
    )
      return
    scopeSelection.value = sources
  })
}
const prepareUnknown = ref(false),
  prepareRefresh = ref(false)
const preparations = new Map<
  string,
  {
    unknown: boolean
    refresh: boolean
    documents: KnowledgeDocumentItem[]
    selection: string[]
    saved: string
    page: number
    total: number
    paged: boolean
  }
>()
watch(id, (next, previous) => {
  if (prepareUnknown.value || prepareRefresh.value) {
    preparations.set(previous, {
      unknown: prepareUnknown.value,
      refresh: prepareRefresh.value,
      documents: scopeDocuments.value,
      selection: [...scopeSelection.value],
      saved: scopeSaved.value,
      page: scopePage.value,
      total: scopeTotal.value,
      paged: scopePaged.value
    })
  } else preparations.delete(previous)
  const stored = preparations.get(next)
  preparationOpen.value = false
  prepareUnknown.value = stored?.unknown ?? false
  prepareRefresh.value = stored?.refresh ?? false
  scopeDocuments.value = stored?.documents ?? []
  scopeSelection.value = stored?.selection ?? []
  scopeSaved.value = stored?.saved ?? ''
  scopePage.value = stored?.page ?? 1
  scopeTotal.value = stored?.total ?? 0
  scopePaged.value = stored?.paged ?? false
})
async function openPreparation() {
  if (publicationPending.value) {
    preparationOpen.value = true
    return
  }
  if (publicationStorageError.value) return
  if (prepareUnknown.value) {
    preparationOpen.value = true
    return
  }
  if (prepareRefresh.value) return
  await run(async () => {
    const items: KnowledgeDocumentItem[] = []
    let firstPage: KnowledgeDocumentItem[] = []
    let nextPage = 1
    let total: number
    do {
      const response = await api.knowledge.listDocuments(id.value, {
        status: 'ready',
        page: nextPage++,
        page_size: 100
      })
      total = response.total
      if (nextPage === 2) firstPage = response.items
      if (total > 2000) {
        items.splice(0, items.length, ...firstPage)
        break
      }
      items.push(...response.items)
      if (!response.items.length && items.length < total)
        throw Error('文档范围读取不完整，请重新读取后准备发布')
    } while (items.length < total)
    if (items.some((item) => !item.source_version)) throw Error('文档缺少来源版本，无法准备发布')
    scopeDocuments.value = items
    scopePage.value = 1
    scopeTotal.value = total
    scopePaged.value = total > 2000
    scopeSelection.value = scopePaged.value ? [] : items.map((item) => item.source_version!)
    scopeSaved.value = JSON.stringify(scopeSelection.value)
    preparationOpen.value = true
  })
}
async function changeScopePage(nextPage: number) {
  if (busy.value || prepareUnknown.value || nextPage === scopePage.value) return
  await run(async () => {
    const response = await api.knowledge.listDocuments(id.value, {
      status: 'ready',
      page: nextPage,
      page_size: 100
    })
    if (response.items.some((item) => !item.source_version))
      throw Error('文档缺少来源版本，无法准备发布')
    scopeDocuments.value = response.items
    scopePage.value = nextPage
    scopeTotal.value = response.total
  })
}
async function refreshPreparation() {
  await run(async () => {
    revisions.value = (await api.knowledge.listRevisions(id.value)).items
    prepareRefresh.value = false
  })
}
async function prepareRevision() {
  if (
    prepareUnknown.value ||
    prepareRefresh.value ||
    publicationLocked.value ||
    !scopeSelection.value.length ||
    scopeSelection.value.length > 2000
  )
    return
  await run(async () => {
    let prepared: KnowledgeBaseRevision
    try {
      prepared = await api.knowledge.prepareRevision(id.value, [...scopeSelection.value], {
        ...policy,
        allowed_operators: economicalIndex.value ? ['KEYWORD'] : policy.allowed_operators
      })
    } catch (caught) {
      if (
        !(caught instanceof HttpClientError) ||
        caught.status === 0 ||
        caught.status === 408 ||
        caught.status >= 500
      )
        prepareUnknown.value = true
      throw caught
    }
    revisions.value = [prepared, ...revisions.value.filter((item) => item.id !== prepared.id)]
    preparationOpen.value = false
    prepareRefresh.value = true
    message.success('修订已准备就绪，发布后才会成为当前知识版本')
    revisions.value = (await api.knowledge.listRevisions(id.value)).items
    prepareRefresh.value = false
  })
}
const { requestClose: closePreparation } = useDrawerClose({
  open: () => preparationOpen.value,
  busy: () => busy.value,
  dirty: () => !prepareUnknown.value && JSON.stringify(scopeSelection.value) !== scopeSaved.value,
  close: () => {
    preparationOpen.value = false
  }
})
async function acceptPublication(
  value: KnowledgePrepareAndPublishReceipt | undefined,
  requestId: string,
  owner: string,
  operation?: PublicationPending
) {
  const revision = value?.revision
  if (
    !value ||
    value.request_id !== requestId ||
    !['PROCESSING', 'SUCCEEDED', 'REJECTED'].includes(value.status) ||
    (value.status === 'REJECTED' && !value.error_message) ||
    (value.status === 'SUCCEEDED' &&
      (!revision?.id ||
        String(revision.kb_id) !== id.value ||
        revision.status !== 'published' ||
        !Number.isSafeInteger(revision.revision) ||
        !revision.capability_version_id ||
        !Array.isArray(revision.source_versions) ||
        (operation &&
          (revision.source_versions.length !== operation.sources.length ||
            [...revision.source_versions]
              .sort()
              .some((source, index) => source !== [...operation.sources].sort()[index])))))
  ) {
    if (!disposed && publicationOwner.value === owner)
      message.warning('尚未查到完整匹配的原请求回执，结果仍未确认')
    return
  }
  if (value.status === 'PROCESSING') {
    if (!disposed && publicationOwner.value === owner)
      message.warning('原请求尚无最终结果，请稍后核对')
    return
  }
  if (disposed || publicationOwner.value !== owner) return
  if (operation?.requestId === requestId) {
    try {
      sessionStorage.removeItem(publicationKey(owner))
    } catch {
      publicationStorageError.value = '无法清除原发布记录，请恢复浏览器存储后重新核对。'
      return
    }
    publicationPending.value = undefined
    publicationStorageError.value = ''
  }
  publicationRequestId.value = ''
  if (value.status === 'REJECTED') {
    message.error(`知识发布未完成：${value.error_message}`)
    return
  }
  publicationReceipt.value = value
  notifyKnowledgePublished(initialOwner.value)
  if (operation) preparationOpen.value = false
  message.success(`知识库修订 ${revision!.revision} 已发布`)
  revisions.value = (await api.knowledge.listRevisions(id.value)).items
  base.value = await api.knowledge.getBase(id.value)
}
async function prepareAndPublish() {
  if (
    !canPrepareAndPublish.value ||
    !publicationOwner.value ||
    publicationLocked.value ||
    prepareUnknown.value ||
    prepareRefresh.value ||
    rebuild.value?.write_locked ||
    !scopeSelection.value.length ||
    scopeSelection.value.length > 2000
  )
    return
  const owner = publicationOwner.value
  const identity = auth.identityVersion
  await run(async () => {
    if (
      !(await confirm({
        title: '确认范围并发布知识',
        confirmText: '确认发布',
        content: `发布已选择的 ${scopeSelection.value.length} 个就绪来源版本并设为当前版本，检索方式为 ${economicalIndex.value ? 'KEYWORD' : policy.allowed_operators.join('、')}，要求引用来源。智能体是否采用取决于挂载配置；正在执行的任务保留原快照。`
      }))
    )
      return
    if (
      disposed ||
      publicationOwner.value !== owner ||
      auth.identityVersion !== identity ||
      !canPrepareAndPublish.value ||
      publicationLocked.value
    )
      return
    const selectedPolicy = {
      ...policy,
      allowed_operators: economicalIndex.value ? ['KEYWORD'] : [...policy.allowed_operators]
    }
    const operation = {
      requestId: crypto.randomUUID(),
      kbId: id.value,
      sources: [...scopeSelection.value],
      policy: selectedPolicy
    }
    try {
      sessionStorage.setItem(publicationKey(owner), JSON.stringify(operation))
    } catch {
      publicationStorageError.value = '无法保存原请求记录，未发送发布请求；请恢复浏览器存储。'
      return
    }
    publicationPending.value = operation
    publicationReceipt.value = undefined
    try {
      const value = await api.knowledge.prepareAndPublish(
        operation.requestId,
        operation.kbId,
        operation.sources,
        operation.policy
      )
      await acceptPublication(value, operation.requestId, owner, operation)
    } catch (cause) {
      if (disposed || publicationOwner.value !== owner) {
        if (cause instanceof HttpClientError) cause.claimFeedback()
        throw 'cancel'
      }
      if (
        cause instanceof HttpClientError &&
        cause.code === 'KNOWLEDGE_PUBLISH_PRE_DISPATCH_REJECTED'
      ) {
        try {
          sessionStorage.removeItem(publicationKey(owner))
          publicationPending.value = undefined
        } catch {
          publicationStorageError.value = '无法清除原发布记录，请恢复浏览器存储后重新核对。'
        }
      }
      throw cause
    }
  })
}
async function checkPublication() {
  const owner = publicationOwner.value
  const operation = publicationPending.value
  const requestId = operation?.requestId ?? publicationRequestId.value.trim()
  if (!can('foundation.knowledge.read')) return
  if (
    !owner ||
    !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(requestId)
  ) {
    message.warning('请输入原请求的 UUID')
    return
  }
  await run(async () =>
    acceptPublication(
      await api.knowledge.prepareAndPublishResult(requestId),
      requestId,
      owner,
      operation
    )
  )
}
async function publishRevision(row: KnowledgeBaseRevision) {
  if (publicationLocked.value) return
  await run(async () => {
    if (
      !(await confirm({
        title: '发布知识修订',
        confirmText: '发布修订',
        content: `将修订 ${row.revision} 设为知识库当前发布？智能体是否采用此版本取决于其挂载配置；正在执行的任务保留原快照。`
      }))
    )
      return
    const owner = initialOwner.value
    Object.assign(row, await api.knowledge.publishRevision(id.value, row.revision))
    if (base.value) base.value.active_revision_id = row.id
    if (owner === initialOwner.value) notifyKnowledgePublished(owner)
    message.success('知识库修订已发布')
    revisions.value = (await api.knowledge.listRevisions(id.value)).items
    base.value = await api.knowledge.getBase(id.value)
  })
}
async function startRebuild() {
  if (effectiveIndexingMode.value !== 'high_quality') return
  await run(async () => {
    const model = models.value?.find((m) => m.id === modelId.value)
    if (!model || !rebuild.value) throw Error('请选择目标模型')
    rebuild.value = await rebuildApi.create(
      id.value,
      model.id,
      model.revision,
      rebuild.value.base_revision,
      crypto.randomUUID()
    )
    message.success('知识库重建已提交')
  })
}
async function cancelRebuild() {
  if (
    await confirm({
      title: '取消重建',
      confirmText: '取消重建',
      danger: true,
      content: '停止当前重建并解除文档写入锁定？已产生的模型调用和费用不会撤销。'
    })
  )
    await run(async () => {
      rebuild.value = await rebuildApi.cancel(rebuild.value!)
      message.success('已请求取消重建')
    })
}
async function retryRebuild() {
  if (
    rebuild.value?.error_code === 'KNOWLEDGE_REBUILD_MODE_CHANGED' &&
    effectiveIndexingMode.value !== 'high_quality'
  )
    return
  const costUnknown = (rebuild.value?.unknown_batches ?? 0) > 0
  if (
    costUnknown &&
    !(await confirm({
      title: '重试未知批次',
      confirmText: '接受重复费用风险并重试',
      content:
        '部分批次的调用和计费结果尚未确认。此次重试会重新计算未完成批次，可能重复计费；已完成批次不会重新计算。'
    }))
  )
    return
  if (
    rebuild.value?.error_code === 'KNOWLEDGE_REBUILD_MODE_CHANGED' &&
    effectiveIndexingMode.value !== 'high_quality'
  )
    return
  await run(async () => {
    rebuild.value = await rebuildApi.retry(rebuild.value!, costUnknown)
    message.success('未完成批次的重试已提交，请查看重建进展')
  })
}
function rebuildReadCurrent() {
  const target = id.value,
    owner = initialOwner.value,
    identity = auth.identityVersion,
    session = getSessionId(),
    authSession = auth.sessionIdentity
  return () =>
    !disposed &&
    id.value === target &&
    initialOwner.value === owner &&
    auth.identityVersion === identity &&
    getSessionId() === session &&
    auth.sessionIdentity === authSession &&
    can('foundation.knowledge.read')
}
async function readPublishedRebuild(current: () => boolean) {
  const target = id.value
  const guard = <T,>(promise: Promise<T>) =>
    promise.catch((reason: unknown) => {
      if (!current()) {
        if (reason instanceof HttpClientError) reason.claimFeedback()
        throw 'cancel'
      }
      throw reason
    })
  const [nextBase, nextRevisions] = await Promise.all([
    guard(api.knowledge.getBase(target)),
    guard(api.knowledge.listRevisions(target))
  ])
  if (!current()) return
  const selectedPage = page.value
  const selectedSize = pageSize.value
  const selectedFilters = { ...appliedDocumentFilters.value }
  const generation = ++documentReadGeneration
  invalidateDocuments()
  const currentDocuments = () =>
    current() &&
    generation === documentReadGeneration &&
    page.value === selectedPage &&
    pageSize.value === selectedSize &&
    appliedDocumentFilters.value.titleQuery === selectedFilters.titleQuery &&
    appliedDocumentFilters.value.status === selectedFilters.status
  let nextDocuments: Awaited<ReturnType<typeof api.knowledge.listDocuments>>
  try {
    loadError.value = ''
    nextDocuments = await api.knowledge.listDocuments(target, {
      page: selectedPage,
      page_size: selectedSize,
      ...(selectedFilters.titleQuery ? { titleQuery: selectedFilters.titleQuery } : {}),
      ...(selectedFilters.status ? { status: selectedFilters.status } : {})
    })
  } catch (reason) {
    if (reason instanceof HttpClientError) reason.claimFeedback()
    if (!currentDocuments()) return
    loadError.value =
      reason instanceof Error ? reason.message : '暂时无法确认操作结果，请先查看当前状态。'
    return
  }
  if (!currentDocuments()) return
  documents.value = nextDocuments
  base.value = nextBase
  revisions.value = nextRevisions.items
  rebuildRefresh.value = false
}
async function retryRebuildRefresh() {
  const current = rebuildReadCurrent()
  await run(() => readPublishedRebuild(current))
}
async function refreshRebuild() {
  await run(async () => {
    const previous = rebuild.value?.status
    const owner = initialOwner.value
    const target = id.value
    const identity = auth.identityVersion
    const session = getSessionId()
    const authSession = auth.sessionIdentity
    const current = () =>
      !disposed &&
      id.value === target &&
      initialOwner.value === owner &&
      auth.identityVersion === identity &&
      getSessionId() === session &&
      auth.sessionIdentity === authSession &&
      can('foundation.knowledge.read')
    const guard = <T,>(promise: Promise<T>) =>
      promise.catch((reason: unknown) => {
        if (!current()) {
          if (reason instanceof HttpClientError) reason.claimFeedback()
          throw 'cancel'
        }
        throw reason
      })
    const needsMode =
      rebuildModeConflict.value ||
      effectiveIndexingMode.value !== 'high_quality' ||
      rebuild.value?.error_code === 'KNOWLEDGE_REBUILD_MODE_CHANGED'
    const [job, freshBase] = await Promise.all([
      guard(rebuildApi.detail(target)),
      needsMode ? guard(api.knowledge.getBase(target)) : Promise.resolve(undefined)
    ])
    if (!current() || (needsMode && !can('foundation.knowledge.read'))) return
    rebuild.value = job
    if (freshBase) {
      base.value = freshBase
      if ((freshBase.effective_indexing_mode ?? freshBase.indexing_mode) === 'high_quality')
        rebuildModeConflict.value = false
    }
    if (previous !== 'PUBLISHED' && rebuild.value.status === 'PUBLISHED') {
      if (owner === initialOwner.value) notifyKnowledgePublished(owner)
      rebuildRefresh.value = true
      await readPublishedRebuild(current)
    }
  })
}
async function publishRebuild() {
  if (
    publicationLocked.value ||
    rebuildModeConflict.value ||
    effectiveIndexingMode.value !== 'high_quality'
  )
    return
  await run(async () => {
    const readCurrent = rebuildReadCurrent()
    const target = id.value
    const owner = initialOwner.value
    const identity = auth.identityVersion
    const session = getSessionId()
    const authSession = auth.sessionIdentity
    const job = rebuild.value
    const current = () =>
      !disposed &&
      id.value === target &&
      initialOwner.value === owner &&
      auth.identityVersion === identity &&
      getSessionId() === session &&
      auth.sessionIdentity === authSession &&
      can('foundation.knowledge.read') &&
      rebuild.value?.job_id === job?.job_id &&
      rebuild.value?.revision === job?.revision
    if (
      !(await confirm({
        title: '发布重建结果',
        confirmText: '发布重建结果',
        content:
          '将重建后的文档范围和模型索引设为当前知识发布，并解除文档写入锁定？正在执行的任务保留原快照。'
      }))
    )
      return
    if (!current()) return
    try {
      const published = await rebuildApi.publish(job!, policy)
      if (!current()) return
      rebuild.value = published
    } catch (reason) {
      if (!current()) {
        if (reason instanceof HttpClientError) reason.claimFeedback()
        throw 'cancel'
      }
      if (!(reason instanceof HttpClientError) || reason.code !== 'KNOWLEDGE_REBUILD_MODE_CHANGED')
        throw reason
      reason.claimFeedback()
      if (!current()) return
      rebuildModeConflict.value = true
      if (!can('foundation.knowledge.read')) return
      try {
        const freshBase = await api.knowledge.getBase(target)
        if (current() && can('foundation.knowledge.read')) base.value = freshBase
      } catch (readError) {
        if (readError instanceof HttpClientError) readError.claimFeedback()
      }
      return
    }
    if (owner === initialOwner.value && rebuild.value.status === 'PUBLISHED')
      notifyKnowledgePublished(owner)
    message.success('重建结果已发布')
    rebuildRefresh.value = true
    await readPublishedRebuild(readCurrent)
  })
}

const { requestClose: closeDrawer0 } = useDrawerClose({
  open: () => documentOpen.value,
  busy: () => busy.value,
  dirty: () => documentOpen.value && documentDirty.value,
  close: () => {
    documentOpen.value = false
  }
})

const formTipId = useId()
</script>

<style scoped>
.knowledge-receipt summary {
  cursor: pointer;
  padding-block: 8px;
}
.knowledge-receipt .admin-toolbar {
  margin-top: 8px;
}
.knowledge-document-filters {
  align-items: flex-end;
}
.knowledge-document-filters :deep(.el-form-item) {
  margin-bottom: 0;
  max-width: 100%;
}
.knowledge-title-filter {
  width: 320px;
}
.knowledge-status-filter {
  width: 220px;
}
.knowledge-status-filter :deep(.el-select) {
  width: 100%;
}
@media (max-width: 767px) {
  .knowledge-document-filters :deep(.el-form-item) {
    width: 100%;
  }
}

.search-request {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
</style>
