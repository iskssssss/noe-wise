<template>
  <AdminPage
    class="workflow-editor"
    :class="{ 'is-design': activeTab === 'design', 'is-focused': focusedCanvas }"
    :title="meta.displayName || '工作流设计'"
    ><template #extra
      ><ElButton
        v-if="activeTab === 'design' || focusedCanvas"
        :aria-pressed="focusedCanvas"
        @click="focusedCanvas = !focusedCanvas"
        >{{ focusedCanvas ? '退出专注' : '专注画布' }}</ElButton
      ><ElButton
        v-if="draft || loadError"
        :loading="loadingDraft"
        :disabled="busy || publicationBlocked"
        @click="refreshDraft"
        >重新读取</ElButton
      ><ElButton
        :disabled="busy || readonly || loadingDraft || draftConflict || creationBlocked"
        @click="validate"
        >校验</ElButton
      ><ElButton
        v-permission.all="
          dirty
            ? [
                'foundation.workflows.read',
                'foundation.workflows.update',
                'foundation.workflows.publish'
              ]
            : ['foundation.workflows.publish']
        "
        :disabled="
          busy ||
          !draft ||
          readonly ||
          draftConflict ||
          creationBlocked ||
          publishedSnapshot === snapshot()
        "
        @click="publish"
        >{{ dirty ? '保存并发布' : '发布' }}</ElButton
      ><ElButton
        :disabled="
          busy || publicationBlocked || !draft?.activeDefinitionVersionId || installStorageError
        "
        @click="openInstall"
        >安装</ElButton
      ><ElButton
        v-if="!route.params.workflowId"
        v-permission.all="['foundation.workflows.create', 'foundation.workflows.update']"
        :disabled="busy || readonly || loadingDraft || draftConflict || creationBlocked"
        @click="saveForTest"
        >保存并配置测试</ElButton
      ><ElButton
        v-else
        v-permission="['foundation.workflows.update']"
        :disabled="busy || readonly || loadingDraft || creationBlocked"
        @click="openTest"
        >测试</ElButton
      ><ElButton
        v-permission="draft ? ['foundation.workflows.update'] : ['foundation.workflows.create']"
        type="primary"
        :loading="busy"
        :disabled="readonly || busy || loadingDraft || draftConflict || creationBlocked"
        @click="save"
        >保存草稿</ElButton
      ></template
    >
    <div class="admin-toolbar workflow-status" role="status">
      <ElTag :type="dirty || creationBlocked ? 'warning' : 'success'">{{
        workflowStateText
      }}</ElTag>
      <span v-if="!unavailableDraft">{{ nodes.length }} 个节点 · {{ edges.length }} 条连接</span>
      <span v-if="!unavailableDraft">{{
        draft?.activeDefinitionVersionId ? '已有发布版本' : '尚未发布'
      }}</span>
      <ElButton v-if="visibleProblems.length" link type="warning" @click="activeTab = 'checks'"
        >{{ visibleProblems.length }} 项校验问题</ElButton
      >
    </div>
    <section
      v-if="!installOpen && (installUncertain || installStorageError)"
      class="admin-card"
      role="status"
    >
      <template v-if="installUncertain"
        >安装结果尚未确认，请求 ID：{{ installUncertain }}。未查到回执不代表安装失败。
        <ElButton
          v-if="auth.user?.permissions.includes('foundation.workflows.read')"
          :disabled="busy"
          @click="checkInstallation()"
          >核对原安装</ElButton
        >
        <span v-else>当前账号没有工作流读取权限，请联系管理员恢复权限后核对。</span>
      </template>
      <template v-else>无法读取或保存原安装请求记录，请恢复浏览器会话存储后重新进入。</template>
    </section>
    <section v-if="publicationPending || publicationStorageError" class="admin-card" role="status">
      <template v-if="publicationPending">
        保存并发布结果尚未确认，请求 ID：{{ publicationPending }}。未查到回执不代表操作失败。
        <ElButton :disabled="busy" @click="checkPublication()">核对原发布</ElButton>
      </template>
      <template v-else>无法读取或保存发布请求记录，请恢复浏览器会话存储后重新进入。</template>
    </section>
    <section v-if="creationBlocked" class="admin-card" role="status">
      <template v-if="creationUnknown">
        工作流“{{
          creationUnknown
        }}”的创建结果尚未确认。请核对原标识，不要重复创建；未找到或找到同标识草稿均不能证明原请求已经结束。刷新后只保留标识，不保留未保存的编辑内容。
        <ElButton :disabled="busy" @click="checkUnknownCreation">核对原工作流</ElButton>
        <p v-if="creationCheck" class="admin-field-help">{{ creationCheck }}</p>
      </template>
      <template v-else-if="draft"
        >工作流已创建，但创建记录无法清除，布局尚未保存。请恢复浏览器会话存储后再继续。</template
      >
      <template v-else>无法保存、读取或清除创建记录，请恢复浏览器会话存储后再创建工作流。</template>
    </section>
    <ElAlert
      v-if="draftConflict"
      title="工作流状态已变化，本地内容已保留"
      type="warning"
      :closable="false"
    >
      <p>
        工作流的版本或状态已变化。请先查看最新草稿的差异，也可导出当前内容留存。重新读取会替换本地修改。
      </p>
      <div class="admin-actions">
        <ElButton :disabled="busy" @click="compareRemoteDraft">查看最新差异</ElButton>
        <ElButton :disabled="busy" @click="exportDefinition">导出当前内容</ElButton>
      </div>
    </ElAlert>
    <ElAlert
      v-if="pendingDefinition"
      title="流程内容已保存，节点位置尚未保存。可再次保存布局。"
      type="warning"
      :closable="false"
    />
    <p v-else-if="unavailableDraft" class="admin-field-help">
      工作流草稿尚未读取成功，请重新读取后再编辑或测试。
    </p>
    <p v-else-if="readonly && !publicationBlocked" class="admin-field-help">
      当前工作流已归档，配置仅供查看。
    </p>
    <p v-if="!route.params.workflowId" class="admin-field-help">
      已提供“开始 → 结束”作为可修改的流程起点；添加业务节点后再校验和发布。
    </p>
    <FeedbackNotice
      v-if="error || loadError"
      :title="error || loadError"
      :closable="false"
      type="error"
    /><ElTabs v-model="activeTab" class="workflow-tabs" @tab-change="loadPublications"
      ><ElTabPane label="流程设计" name="design"
        ><div class="workflow-design-tools">
          <ElSelect
            v-described-by="formTipId + '-group-0'"
            :aria-describedby="formTipId + '-group-0'"
            v-model="nodeType"
            filterable
            aria-label="新增节点类型"
            class="workflow-node-type"
            ><ElOptionGroup v-for="group in nodeGroups" :key="group.label" :label="group.label"
              ><ElOption
                v-for="kind in group.nodes"
                :key="kind.type"
                :label="kind.label"
                :value="kind.type" /></ElOptionGroup></ElSelect
          ><ElButton :disabled="readonly || busy || loadingDraft" @click="addNode"
            >添加节点</ElButton
          ><ElButton
            :loading="schemaLoading"
            :disabled="busy || loadingDraft"
            @click="refreshToolContracts(true)"
            >刷新工具参数</ElButton
          ><ElButton class="workflow-layout-action" :disabled="!canUndo" @click="undo"
            >撤销</ElButton
          ><ElButton class="workflow-layout-action" :disabled="!canRedo" @click="redo"
            >重做</ElButton
          ><ElButton
            class="workflow-layout-action"
            @click="arrange"
            :disabled="readonly || busy || loadingDraft"
            >自动排列</ElButton
          >
          <ElSelect
            v-model="selectedId"
            filterable
            clearable
            aria-label="定位节点"
            class="workflow-node-search"
            placeholder="搜索并定位节点"
            @change="focusSelectedNode"
          >
            <ElOption
              v-for="node in nodes"
              :key="node.id"
              :value="node.id"
              :label="node.label + ' · ' + node.id"
            />
          </ElSelect>
          <ElPopover trigger="click" :width="360" placement="bottom-end">
            <template #reference><ElButton>更多与帮助</ElButton></template>
            <p class="admin-field-help">
              校验当前编辑内容不会保存；发布后，新任务才会使用已发布的配置。
            </p>
            <div class="workflow-mobile-actions admin-actions">
              <ElButton :disabled="!canUndo" @click="undo">撤销</ElButton>
              <ElButton :disabled="!canRedo" @click="redo">重做</ElButton>
              <ElButton :disabled="readonly || busy || loadingDraft" @click="arrange"
                >自动排列</ElButton
              >
            </div>
            <div class="admin-actions">
              <ElButton @click="exportDefinition" :disabled="busy">导出定义</ElButton>
              <ElUpload
                v-described-by="formTipId + '-upload'"
                :aria-describedby="formTipId + '-upload'"
                ref="definitionUpload"
                :auto-upload="false"
                :show-file-list="false"
                :disabled="readonly || busy || loadingDraft"
                :on-change="importDefinition"
                accept=".json"
                ><ElButton :disabled="readonly || busy || loadingDraft"
                  >导入定义</ElButton
                ></ElUpload
              >
            </div>
            <FieldTip :id="formTipId + '-upload'"
              >导入工作流 JSON 会替换当前流程节点和配置；导入后请检查内容并保存草稿。</FieldTip
            >

            <FieldTip :id="formTipId + '-group-0'"
              >{{
                nodeDescriptions[nodeType] || '选择节点类型后添加，再在右侧配置参数。'
              }}当前运行时尚未支持并行、子工作流和 Agentic 节点执行。</FieldTip
            >
          </ElPopover>
          <ElButton v-if="selected && !propertiesOpen" @click="openNodeProperties(selectedId)"
            >节点配置</ElButton
          >
        </div>
        <p v-if="schemaStale" class="admin-field-help" role="status">
          工具参数未刷新，当前仍显示上次读取的配置。
        </p>
        <div class="workflow-workspace" :class="{ 'with-properties': selected && propertiesOpen }">
          <WorkflowCanvas
            ref="canvas"
            v-model:nodes="nodes"
            v-model:edges="edges"
            v-model:selected-id="selectedId"
            v-model:selected-ids="selectedIds"
            v-model:viewport="viewport"
            :can-paste="!!nodeClipboard"
            :readonly="readonly"
            :busy="busy || loadingDraft"
            @edit-node="openNodeProperties"
            @edit-edge="editEdge"
            @remove-node="removeNode($event)"
            @remove-nodes="removeNodes"
            @copy-nodes="copyNodes"
            @paste-nodes="pasteNodes"
            @add-next="openNodePlacement({ kind: 'after', nodeId: $event })"
            @insert-node="openNodePlacement({ kind: 'edge', edgeId: $event })"
          />
          <aside
            v-show="selected && propertiesOpen"
            ref="propertiesPanel"
            class="workflow-properties"
            aria-label="节点配置"
            tabindex="-1"
            @keydown.esc="onPropertiesEscape"
          >
            <template v-if="selected"
              ><header class="workflow-properties-heading">
                <h3>节点属性</h3>
                <ElButton @click="closeNodeProperties">收起配置</ElButton>
              </header>
              <div class="workflow-properties-body">
                <NodeProperties
                  ref="nodeProperties"
                  v-model="selected"
                  :nodes="nodes"
                  :edges="edges"
                  :meta="meta"
                  :tool-contracts="toolContracts"
                  :script-resources="meta.resources?.map((resource) => resource.path)"
                  :readonly="readonly || busy || loadingDraft"
                  @edit-connections="editConnections"
                />
                <WorkflowCapabilityDeclarations
                  v-if="['tool', 'knowledge_query'].includes(selected.type)"
                  v-model:requirements="meta.capabilityRequirementsText"
                  v-model:tool-declarations="meta.toolsText"
                  :readonly="readonly || busy || loadingDraft"
                  :quick-type="selected.type === 'tool' ? 'TOOL' : 'KNOWLEDGE_SOURCE'"
                  @tool-added="selected.type === 'tool' && (selected.tool = $event)"
                  @knowledge-added="
                    selected.type === 'knowledge_query' && (selected.knowledgeRequirement = $event)
                  "
                />
                <ElButton
                  type="danger"
                  :disabled="readonly || busy || loadingDraft"
                  @click="removeNode()"
                  >删除节点</ElButton
                >
              </div></template
            >
          </aside>
        </div></ElTabPane
      >
      <ElTabPane :label="'连接与条件（' + edges.length + '）'" name="connections"
        ><ElButton :disabled="readonly || busy || loadingDraft" @click="addEdge">添加连接</ElButton
        ><ElTable
          ref="edgeTable"
          row-key="id"
          highlight-current-row
          table-layout="auto"
          :data="edges"
          empty-text="暂无记录"
          ><ElTableColumn min-width="200" label="起点"
            ><template #default="{ row }"
              ><ElSelect
                v-described-by="formTipId + '-group-1'"
                :aria-describedby="formTipId + '-group-1'"
                v-model="row.source"
                :disabled="readonly || busy || loadingDraft"
                ><ElOption
                  v-for="node in nodes"
                  :key="node.id"
                  :label="node.label"
                  :value="node.id" /></ElSelect></template></ElTableColumn
          ><ElTableColumn min-width="200" label="终点"
            ><template #default="{ row }"
              ><ElSelect
                v-described-by="formTipId + '-group-1'"
                :aria-describedby="formTipId + '-group-1'"
                v-model="row.target"
                :disabled="readonly || busy || loadingDraft"
                ><ElOption
                  v-for="node in nodes"
                  :key="node.id"
                  :label="node.label"
                  :value="node.id" /></ElSelect></template></ElTableColumn
          ><ElTableColumn min-width="280" label="条件表达式"
            ><template #default="{ row }"
              ><ConditionEditor
                v-described-by="formTipId + '-group-1'"
                :aria-describedby="formTipId + '-group-1'"
                v-model="row.condition"
                :readonly="readonly || busy || loadingDraft"
                :variables="
                  workflowVariables(nodes, edges, row.source, meta, toolContracts, {
                    includeCurrent: true
                  })
                "
                label="连接条件" /></template></ElTableColumn
          ><ElTableColumn width="180" label="优先级"
            ><template #default="{ row }"
              ><ElInputNumber
                v-described-by="formTipId + '-group-1'"
                :aria-describedby="formTipId + '-group-1'"
                v-model="row.priority"
                :disabled="readonly || busy || loadingDraft"
                :precision="0" /></template></ElTableColumn
          ><ElTableColumn width="80"
            ><template #default="{ $index }"
              ><ElButton
                link
                type="danger"
                :disabled="readonly || busy || loadingDraft"
                @click="edges.splice($index, 1)"
                >删除</ElButton
              ></template
            ></ElTableColumn
          ></ElTable
        >
        <FieldTip :id="formTipId + '-group-1'"
          >每条边指定起点、终点、条件和优先级；条件留空表示无条件跳转，节点引用须存在于当前工作流。</FieldTip
        >
      </ElTabPane>
      <ElTabPane label="工作流信息" name="info"
        ><ElForm
          label-position="top"
          :disabled="readonly || busy || loadingDraft"
          @submit.prevent
          scroll-to-error
          @keydown.capture="guardFormEnter"
          ><div class="admin-form-grid">
            <ElFormItem label="业务标识"
              ><ElInput
                v-described-by="formTipId + '-field-0'"
                :aria-describedby="formTipId + '-field-0'"
                v-model="workflowId"
                :disabled="!!draft || creationBlocked"
              />
              <FieldTip :id="formTipId + '-field-0'"
                >已自动生成稳定标识，创建前可修改；草稿建立后不可在此修改。</FieldTip
              > </ElFormItem
            ><ElFormItem label="名称"
              ><ElInput
                v-described-by="formTipId + '-field-1'"
                :aria-describedby="formTipId + '-field-1'"
                v-model="meta.displayName"
              />
              <FieldTip :id="formTipId + '-field-1'">填写工作流的展示名称。</FieldTip> </ElFormItem
            ><ElFormItem label="版本"
              ><ElInput
                v-described-by="formTipId + '-field-2'"
                :aria-describedby="formTipId + '-field-2'"
                v-model="meta.version"
              />
              <FieldTip :id="formTipId + '-field-2'">填写本次草稿的版本标识。</FieldTip>
            </ElFormItem>
          </div>
          <ElFormItem label="入口节点"
            ><ElSelect v-model="meta.entryNodeId" aria-label="工作流入口节点"
              ><ElOption
                v-for="node in nodes"
                :key="node.id"
                :label="node.label + ' · ' + node.id"
                :value="node.id" /></ElSelect
          ></ElFormItem>
          <ElFormItem label="描述"
            ><ElInput
              v-described-by="formTipId + '-field-3'"
              :aria-describedby="formTipId + '-field-3'"
              v-model="meta.description"
              type="textarea"
            />
            <FieldTip :id="formTipId + '-field-3'"
              >描述工作流目标和适用场景。</FieldTip
            > </ElFormItem
          ><ElFormItem label="分类"
            ><ElInput
              v-described-by="formTipId + '-field-4'"
              :aria-describedby="formTipId + '-field-4'"
              v-model="meta.category"
            />
            <FieldTip :id="formTipId + '-field-4'"
              >填写工作流分类，用于目录检索。</FieldTip
            > </ElFormItem
          ><ElFormItem label="标签（逗号分隔）"
            ><ElInput
              v-described-by="formTipId + '-field-5'"
              :aria-describedby="formTipId + '-field-5'"
              v-model="meta.tagsText"
            />
            <FieldTip :id="formTipId + '-field-5'"
              >填写分类标签，多个标签用英文逗号分隔。</FieldTip
            > </ElFormItem
          ><WorkflowGovernanceFields
            data-workflow-field="capability"
            :key="'governance-' + editorGeneration"
            v-model="meta.capability"
            :readonly="readonly || busy || loadingDraft"
            @validity-change="governanceValid = $event"
          />
          <WorkflowConfigFields
            data-workflow-field="config_schema"
            v-model="meta.configSchemaText"
            :readonly="readonly || busy || loadingDraft"
          />
          <WorkflowCapabilityDeclarations
            data-workflow-field="capability_requirements tools"
            v-model:requirements="meta.capabilityRequirementsText"
            v-model:tool-declarations="meta.toolsText"
            :readonly="readonly || busy || loadingDraft"
          />
          <WorkflowScriptResources
            data-workflow-field="resources"
            :key="'resources-' + editorGeneration"
            v-model="meta.resources"
            :readonly="readonly || busy || loadingDraft"
            @pending-change="resourcePending = $event"
          />
          <ElFormItem label="所需权限"
            ><ElInput v-model="meta.requiredPermissionsText" aria-label="工作流所需权限" />
            <p class="admin-field-help">
              填写执行所需权限，支持逗号分隔或 JSON 字符串数组。
            </p></ElFormItem
          >
          ></ElForm
        ></ElTabPane
      >
      <ElTabPane
        :label="
          visibleProblems.length ? '校验与变更（' + visibleProblems.length + '）' : '校验与变更'
        "
        name="checks"
        ><div class="admin-toolbar">
          <ElButton
            :disabled="busy || readonly || loadingDraft || draftConflict || creationBlocked"
            @click="validate"
            >重新校验</ElButton
          ><ElSelect v-model="diffSource" aria-label="比较对象"
            ><ElOption label="当前编辑内容（含未保存）" value="local" /><ElOption
              label="服务端已保存草稿"
              value="saved" /></ElSelect
          ><ElButton :disabled="busy || !draft" @click="loadDiff">比较已发布版本</ElButton>
        </div>
        <p v-if="diffCaption" role="status" class="admin-field-help">{{ diffCaption }}</p>
        <ElAlert
          v-if="validationCurrent && !visibleProblems.length"
          :title="
            dirty ? '当前未保存内容校验通过；保存后发布时会再次校验。' : '草稿校验通过，可以发布。'
          "
          type="success"
          :closable="false"
        />
        <ElAlert
          v-if="
            validationCurrent &&
            visibleProblems.length &&
            !visibleProblems.some((problem) => problem.severity === 'error')
          "
          :title="
            dirty
              ? '当前未保存内容可通过校验，请查看下方提醒；发布时会再次校验。'
              : '草稿可以发布，请先查看下方提醒。'
          "
          type="warning"
          :closable="false"
        />
        <ElAlert
          v-if="!validationCurrent && dirty && visibleProblems.length"
          title="流程已有新修改，请重新校验。下方保留上次校验结果，变量引用提醒会随编辑更新。"
          type="info"
          :closable="false"
        />
        <div
          v-for="problem in visibleProblems"
          :key="problem.objectRef + problem.fieldPath + problem.code"
          class="workflow-problem"
        >
          <ElTag :type="problem.severity === 'error' ? 'danger' : 'warning'">{{
            problem.severity === 'error' ? '错误' : '提醒'
          }}</ElTag>
          <div>
            <strong>{{ problem.message }}</strong>
            <p class="admin-muted">{{ problem.objectRef }} · {{ problem.fieldPath }}</p>
          </div>
          <ElButton link @click="locateProblem(problem)">定位问题</ElButton>
        </div>
        <ElTable
          v-if="changes.length"
          table-layout="auto"
          :data="changes"
          empty-text="与发布版本没有差异"
          ><ElTableColumn
            min-width="160"
            show-overflow-tooltip
            prop="operation"
            label="操作"
          /><ElTableColumn
            min-width="200"
            show-overflow-tooltip
            prop="path"
            label="路径"
          /><ElTableColumn min-width="320" label="修改前"
            ><template #default="{ row }">
              <pre class="readable">{{ JSON.stringify(row.beforeValue, null, 2) }}</pre>
            </template></ElTableColumn
          ><ElTableColumn min-width="320" label="修改后"
            ><template #default="{ row }">
              <pre class="readable">{{ JSON.stringify(row.afterValue, null, 2) }}</pre>
            </template></ElTableColumn
          ></ElTable
        ></ElTabPane
      ><ElTabPane label="发布历史" name="history">
        <ElButton :disabled="busy" @click="loadPublications('history')">刷新历史</ElButton>
        <ElTable :data="publications" row-key="definitionVersionId" empty-text="尚无发布记录">
          <ElTableColumn prop="definitionVersion" label="发布修订" width="110" />
          <ElTableColumn prop="createdAt" label="发布时间" min-width="190"
            ><template #default="{ row }">{{
              formatDateTime(row.createdAt)
            }}</template></ElTableColumn
          >
          <ElTableColumn label="生效状态" width="120"
            ><template #default="{ row }">{{
              row.current ? '当前发布' : '历史发布'
            }}</template></ElTableColumn
          >
          <ElTableColumn label="操作" width="200"
            ><template #default="{ row }">
              <ElButton link :disabled="busy" @click="viewPublication(row as WorkflowPublication)"
                >查看定义</ElButton
              >
              <ElButton
                v-permission="['foundation.workflows.update']"
                link
                :disabled="busy || dirty || readonly || draftConflict"
                @click="restorePublication(row as WorkflowPublication)"
                >恢复为草稿</ElButton
              >
            </template></ElTableColumn
          >
        </ElTable>
      </ElTabPane></ElTabs
    >
    <ElDrawer
      v-model="installOpen"
      title="安装工作流"
      size="680px"
      :before-close="closeInstall"
      append-to-body
      direction="rtl"
      :close-on-click-modal="false"
      :close-on-press-escape="!busy"
      :show-close="!busy"
      class="admin-drawer"
      ><FeedbackNotice
        v-if="error"
        :title="error"
        type="error"
        :closable="false"
        role="alert"
      /><ElAlert
        title="安装使用已发布版本。完成资源绑定后，可将工作流添加到智能体；正在执行的任务不受影响。"
        type="info"
      />
      <ElAlert
        v-if="installUncertain"
        :title="
          '安装结果尚未确认，已停止重复提交。工作流 ' + workflowId + '，请求 ID ' + installUncertain
        "
        type="warning"
        :closable="false"
      />
      <p v-if="installStorageError" role="status">
        无法读取或保存原安装请求记录，请恢复浏览器会话存储后重新进入。
      </p>
      <ElButton
        v-if="installUncertain && auth.user?.permissions.includes('foundation.workflows.read')"
        :disabled="busy"
        @click="checkInstallation()"
        >核对原安装</ElButton
      >
      <p v-else-if="installUncertain">当前账号没有工作流读取权限，请联系管理员恢复权限后核对。</p>
      <WorkflowInstallationFields
        v-model:slots="installSlots"
        v-model:config="installConfig"
        v-model:custom-prompt="customPrompt"
        :fields="installConfigFields"
        :deployments="deployments"
        :candidates="candidateVersions"
        :disabled="busy || !!installUncertain"
      />
      <ElButton
        v-if="installSlots.length && !installUncertain"
        :disabled="busy"
        @click="refreshInstallDependencies"
        >刷新依赖选项</ElButton
      >
      <template #footer
        ><ElButton @click="closeInstall" :disabled="busy">取消</ElButton
        ><ElButton
          v-permission="['foundation.workflows.create']"
          type="primary"
          :loading="busy"
          :disabled="!!installUncertain || installStorageError"
          @click="install"
          >安装</ElButton
        ></template
      ></ElDrawer
    >
    <p v-if="installReceipt" role="status" class="admin-field-help">
      已安装实例 {{ installReceipt.instanceId }} · 安装修订
      {{ installReceipt.installationRevision }}
    </p>
    <ElButton v-if="installReceipt" @click="router.push('/admin/agents')"
      >前往智能体挂载此工作流</ElButton
    >
    <ElDialog
      v-model="nodePlacementOpen"
      :title="nodePlacement?.kind === 'edge' ? '在连接中插入节点' : '添加后续节点'"
      width="560px"
      append-to-body
      :close-on-click-modal="false"
      @closed="restorePlacementFocus"
    >
      <ElForm label-position="top" @submit.prevent>
        <ElFormItem label="新节点类型"
          ><ElSelect v-model="placementNodeType" filterable aria-label="新节点类型"
            ><ElOptionGroup
              v-for="group in placementNodeGroups"
              :key="group.label"
              :label="group.label"
              ><ElOption
                v-for="kind in group.nodes"
                :key="kind.type"
                :label="kind.label"
                :value="kind.type" /></ElOptionGroup></ElSelect
        ></ElFormItem>
        <ElFormItem v-if="placementChoices.length" label="插入位置"
          ><ElSelect v-model="placementChoice" aria-label="插入位置"
            ><ElOption
              v-for="choice in placementChoices"
              :key="choice.value"
              :label="choice.label"
              :value="choice.value" /></ElSelect
        ></ElFormItem>
        <p class="admin-field-help">
          {{ nodeDescriptions[placementNodeType] }} 已有分支的条件与优先级保留在进入新节点的连接上。
        </p>
      </ElForm>
      <template #footer
        ><ElButton @click="nodePlacementOpen = false">取消</ElButton
        ><ElButton
          type="primary"
          :disabled="!placementNodeType || (placementChoices.length > 0 && !placementChoice)"
          @click="addPlacedNode"
          >添加并连接</ElButton
        ></template
      >
    </ElDialog>
  </AdminPage>
  <ElDrawer
    v-model="publicationOpen"
    class="admin-drawer"
    :title="'工作流发布修订 ' + publicationDetail?.definitionVersion"
    size="760px"
    append-to-body
  >
    <p class="admin-field-help">历史定义仅供查看，恢复为草稿并发布后才能生效。</p>
    <pre class="readable">{{ JSON.stringify(publicationDetail?.definition, null, 2) }}</pre>
  </ElDrawer>
  <WorkflowTestPanel
    v-model="testOpen"
    :workflow-id="workflowId"
    :draft-revision="draft?.revision"
    :dirty="dirty"
    :disabled="busy || readonly || loadingDraft || creationBlocked"
    :publications="testPublications"
    :identity-version="auth.identityVersion"
    :save-draft="saveForTest"
    @save="saveForTest"
  />
</template>
<script setup lang="ts">
import FeedbackNotice from '@skillforge/shared/feedback/FeedbackNotice.vue'
import { formatDateTime } from '@skillforge/shared/utils/datetime'
import { vDescribedBy } from '@skillforge/shared/directives/describedBy'

import FieldTip from '@skillforge/shared/components/FieldTip.vue'

import { confirm, message } from '@skillforge/shared/feedback'
import { HttpClientError } from '@skillforge/shared/utils/http'
import { guardFormEnter } from '@skillforge/shared/admin'
import type { UploadFile, UploadInstance } from 'element-plus'
import { computed, nextTick, onScopeDispose, reactive, ref, watch, useId } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  AdminPage,
  useTask,
  useResource,
  useUnsavedChanges,
  useDrawerClose
} from '@skillforge/shared/admin'
import NodeProperties from '../../components/builder/NodeProperties.vue'
import WorkflowCanvas from '../../components/builder/WorkflowCanvas.vue'
import WorkflowTestPanel from '../../components/builder/WorkflowTestPanel.vue'
import {
  captureWorkflowSelection,
  pasteWorkflowSelection,
  type WorkflowClipboard
} from '../../utils/workflowClipboard'
import { verifyWorkflowResourceIntegrity } from '../../utils/workflowResourceIntegrity'
import ConditionEditor from '../../components/builder/ConditionEditor.vue'
import {
  workflowVariables,
  customVariableError,
  type WorkflowToolContract
} from '../../components/builder/nodeEditing'
import WorkflowGovernanceFields from '../../components/builder/WorkflowGovernanceFields.vue'
import WorkflowConfigFields from '../../components/builder/WorkflowConfigFields.vue'
import WorkflowInstallationFields from '../../components/builder/WorkflowInstallationFields.vue'
import { defaultInstallationSlots } from '../../components/builder/workflowInstallationDefaults'
import WorkflowScriptResources from '../../components/builder/WorkflowScriptResources.vue'
import WorkflowCapabilityDeclarations from '../../components/builder/WorkflowCapabilityDeclarations.vue'
import {
  parseWorkflowTestRecovery,
  workflowTestRecoveryKey
} from '../../components/builder/workflowTestRecovery'
import type {
  WorkflowInstallReceipt,
  WorkflowPublicationDetail,
  WorkflowSaveAndPublishReceipt
} from '../../api/workflowDrafts'
import { useAuthStore } from '../../stores/auth'
import { notifyWorkflowPublished } from './workflowPublicationEvents'
import {
  api,
  getSessionId,
  type WorkflowDraft,
  type WorkflowPublication,
  type WorkflowValidationProblem,
  type WorkflowDefinitionDiffEntry,
  type CapabilityVersion,
  type CapabilityDefinition,
  type CapabilityDeployment
} from '../../api'
import {
  EXECUTABLE_WORKFLOW_NODE_CATALOG,
  workflowDefinition,
  parseWorkflowJson,
  defaultWorkflowMeta,
  detailToMeta,
  workflowProblems,
  workflowScriptResources,
  workflowConfigFields,
  workflowInstallationConfig,
  workflowDefinitionChanges,
  matchesWorkflowVersion,
  recommendedWorkflowCapability,
  recommendedWorkflowResultTo,
  connectWorkflowNode,
  type WorkflowNodePlacement,
  type WorkflowConfigField,
  type BuilderNode,
  type BuilderEdge
} from '../../utils/workflow'
const route = useRoute(),
  router = useRouter(),
  workflowId = ref(''),
  draft = ref<WorkflowDraft>(),
  nodes = ref<BuilderNode[]>([]),
  edges = ref<BuilderEdge[]>([]),
  meta = reactive(defaultWorkflowMeta()),
  selectedId = ref(''),
  selectedIds = ref<string[]>([]),
  nodeType = ref('llm'),
  canvas = ref<InstanceType<typeof WorkflowCanvas>>(),
  viewport = ref({ zoom: 1, panX: 24, panY: 24 })
const auth = useAuthStore()
let pageDisposed = false
onScopeDispose(() => {
  pageDisposed = true
})
const installOwner = computed(() =>
  auth.user?.tenant_id && auth.user?.sub ? JSON.stringify([auth.user.tenant_id, auth.user.sub]) : ''
)
const nodeKinds = EXECUTABLE_WORKFLOW_NODE_CATALOG
const nodeGroups = [
  { label: '流程控制', types: ['start', 'condition', 'assign', 'end'] },
  { label: '能力调用', types: ['tool', 'knowledge_query', 'llm', 'script'] },
  { label: '交互与输出', types: ['human_input', 'menu', 'async_task', 'output', 'card'] }
].map((group) => ({
  label: group.label,
  nodes: nodeKinds.filter((node) => group.types.includes(node.type))
}))
const nodeDescriptions: Record<string, string> = {
  start: '开始节点接收工作流输入，仅允许出边。',
  condition: '依据连接上的条件和优先级选择路径。',
  assign: '将字面量或变量表达式写入 state 字段。',
  end: '终止流程，不消费消息；需要发送最终内容时先连接输出节点。',
  tool: '调用已声明工具，安装时绑定机构部署。',
  knowledge_query: '通过已声明知识槽检索，必须配置五项预算。',
  llm: '使用运行环境继承的模型执行提示词。',
  script: '执行已冻结的 .js/.py 脚本资源，对象参数通过 input 输入。',
  human_input: '发送提示并挂起，等待用户提交或超时。',
  menu: '展示选项并按所选行动点跳转。',
  async_task: '挂起等待异步结果或超时。',
  output: '向最终用户发送可读消息。',
  card: '发送结构化卡片，实际展现由终端渲染器决定。'
}
const nodePlacementOpen = ref(false),
  placementTrigger = ref<HTMLElement>(),
  placementAdded = ref(false),
  nodePlacement = ref<WorkflowNodePlacement>(),
  placementNodeType = ref('tool'),
  placementChoice = ref(''),
  placementChoices = ref<Array<{ value: string; label: string; placement: WorkflowNodePlacement }>>(
    []
  )
const placementNodeGroups = nodeGroups.map((group) => ({
  ...group,
  nodes: group.nodes.filter((node) => !['start', 'end'].includes(node.type))
}))
const activeTab = ref('design'),
  editorGeneration = ref(0),
  governanceValid = ref(true),
  resourcePending = ref(false),
  nodeProperties = ref<InstanceType<typeof NodeProperties>>(),
  edgeTable = ref<{ setCurrentRow: (edge: BuilderEdge) => void }>(),
  diffSource = ref('local'),
  diffCaption = ref(''),
  validated = ref(false),
  validatedSnapshot = ref(''),
  pendingDefinition = ref(''),
  publishedSnapshot = ref('')
const focusedCanvas = ref(false)
const draftConflict = ref(false)
const nodeClipboard = ref<WorkflowClipboard>()
let pasteSequence = 0
const propertiesOpen = ref(false)
const propertiesPanel = ref<HTMLElement>()
watch(selectedIds, (ids) => {
  if (ids.length !== 1) propertiesOpen.value = false
})
async function openNodeProperties(id: string) {
  selectedId.value = id
  selectedIds.value = id ? [id] : []
  propertiesOpen.value = !!id
  await nextTick()
  if (propertiesOpen.value && window.matchMedia('(max-width: 900px)').matches) {
    propertiesPanel.value?.focus({ preventScroll: true })
  }
}
async function closeNodeProperties() {
  propertiesOpen.value = false
  await nextTick()
  await canvas.value?.focusNode(selectedId.value)
}
function onPropertiesEscape(event: KeyboardEvent) {
  if (event.defaultPrevented || event.isComposing) return
  event.stopPropagation()
  void closeNodeProperties()
}
const { busy, error, run } = useTask({
    handlesError: (reason) =>
      draftConflict.value && reason instanceof HttpClientError && reason.status === 409
  }),
  {
    error: loadError,
    loading: loadingDraft,
    reload
  } = useResource(async () => {
    const id = String(route.params.workflowId)
    const [value, layout] = await Promise.all([
      api.workflowDrafts.get(id),
      api.workflowDrafts.snapshot.get(id)
    ])
    return { value, layout }
  })
const saved = ref(''),
  problems = ref<WorkflowValidationProblem[]>([]),
  changes = ref<WorkflowDefinitionDiffEntry[]>([])
const creationUnknown = ref(''),
  creationCheck = ref(''),
  creationStorageError = ref(false)
const creationBlocked = computed(() => !!creationUnknown.value || creationStorageError.value)
const publicationPending = ref('')
const publicationStorageError = ref(false)
const publicationAutoCheck = ref<{ owner: string; id: string; requestId: string }>()
const publicationBlocked = computed(
  () => !!publicationPending.value || publicationStorageError.value
)
const publicationKey = (owner: string, id: string) =>
  `noewise:workflow-publish:${JSON.stringify([owner, id])}`
function publicationSession() {
  try {
    return getSessionId()
  } catch {
    return null
  }
}
watch(
  [
    () => route.params.workflowId,
    installOwner,
    () => auth.identityVersion,
    () => auth.sessionIdentity
  ],
  ([value, owner]) => {
    publicationPending.value = ''
    publicationStorageError.value = false
    publicationAutoCheck.value = undefined
    const id = typeof value === 'string' ? value : ''
    if (!id) return
    if (!owner) {
      publicationStorageError.value = true
      return
    }
    try {
      const pending = sessionStorage.getItem(publicationKey(owner, id))
      if (
        pending &&
        !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(pending)
      )
        throw Error('invalid')
      publicationPending.value = pending ?? ''
      if (pending) publicationAutoCheck.value = { owner, id, requestId: pending }
    } catch {
      publicationStorageError.value = true
    }
  },
  { immediate: true, flush: 'sync' }
)
const creationKey = () => {
  const identity = getSessionId()
  if (!identity) throw Error('当前登录会话无法保存创建记录，请重新登录')
  return `noewise:workflow-create:${identity}`
}
const unavailableDraft = computed(() => !!route.params.workflowId && !draft.value)
const readonly = computed(
    () => unavailableDraft.value || publicationBlocked.value || draft.value?.status === 'archived'
  ),
  snapshot = () =>
    JSON.stringify([nodes.value, edges.value, meta, workflowId.value, viewport.value]),
  dirty = computed(
    () =>
      !unavailableDraft.value &&
      (snapshot() !== saved.value || !governanceValid.value || resourcePending.value)
  )
const validationCurrent = computed(
  () =>
    validated.value &&
    validatedSnapshot.value === snapshot() &&
    governanceValid.value &&
    !resourcePending.value
)
const workflowStateText = computed(() => {
  if (publicationBlocked.value) return '发布结果待核对'
  if (creationUnknown.value) return '创建结果待核对'
  if (creationStorageError.value) return '创建暂不可用'
  if (unavailableDraft.value) return loadingDraft.value ? '正在读取草稿' : '草稿尚未读取'
  if (readonly.value) return '已归档 · 只读'
  if (!draft.value) return '新建草稿 · 尚未保存'
  return dirty.value ? '有未保存修改' : '草稿已保存'
})
useUnsavedChanges({
  dirty: () => dirty.value || !!creationUnknown.value || !!publicationPending.value,
  busy: () => busy.value,
  leaveMessage: () =>
    publicationPending.value
      ? `工作流发布结果尚未确认，请保留原请求号 ${publicationPending.value} 并核对回执。`
      : creationUnknown.value
        ? `工作流“${creationUnknown.value}”的创建结果尚未确认，当前标签页会保留原标识；关闭标签页可能丢失记录。`
        : undefined
})
const selected = computed({
  get: () => nodes.value.find((n) => n.id === selectedId.value),
  set: (node: BuilderNode | undefined) => {
    const i = nodes.value.findIndex((n) => n.id === selectedId.value)
    if (node && i >= 0) nodes.value[i] = node
  }
})
const history = ref<string[]>([]),
  historyIndex = ref(-1)
let replaying = false
function rememberEdit() {
  if (replaying) return
  history.value = history.value.slice(0, historyIndex.value + 1)
  history.value.push(snapshot())
  if (history.value.length > 60) history.value.shift()
  historyIndex.value = history.value.length - 1
}
watch([nodes, edges, meta, workflowId, viewport], rememberEdit, { deep: true, flush: 'sync' })
const canUndo = computed(
    () =>
      !readonly.value &&
      !busy.value &&
      !resourcePending.value &&
      governanceValid.value &&
      historyIndex.value > 0
  ),
  canRedo = computed(
    () =>
      !readonly.value &&
      !busy.value &&
      !resourcePending.value &&
      governanceValid.value &&
      historyIndex.value < history.value.length - 1
  )
function restore(index: number) {
  const value = history.value[index]
  if (!value) return
  replaying = true
  const [n, e, m, id, view] = JSON.parse(value)
  nodes.value = n
  edges.value = e
  Object.assign(meta, m)
  workflowId.value = id
  viewport.value = view
  selectedIds.value = selectedIds.value.filter((id) =>
    n.some((node: BuilderNode) => node.id === id)
  )
  if (!n.some((node: BuilderNode) => node.id === selectedId.value)) selectedId.value = ''
  historyIndex.value = index
  replaying = false
}
function undo() {
  if (canUndo.value) restore(historyIndex.value - 1)
}
function redo() {
  if (canRedo.value) restore(historyIndex.value + 1)
}
function accept(
  value: WorkflowDraft,
  layout: Record<string, unknown> = {},
  view: { zoom?: number; panX?: number; panY?: number } = {}
) {
  if (draft.value?.workflowId !== value.workflowId) nodeClipboard.value = undefined
  draftConflict.value = false
  resetEditors()
  viewport.value = { zoom: view.zoom ?? 1, panX: view.panX ?? 24, panY: view.panY ?? 24 }
  pendingDefinition.value = ''
  validated.value = false
  draft.value = value
  workflowId.value = value.workflowId
  const parsed = parseWorkflowJson(value.definition as Parameters<typeof parseWorkflowJson>[0])
  nodes.value = parsed.nodes.map((node) => {
    const position = layout[node.id] as { x?: unknown; y?: unknown } | undefined
    return position &&
      typeof position.x === 'number' &&
      Number.isFinite(position.x) &&
      typeof position.y === 'number' &&
      Number.isFinite(position.y)
      ? { ...node, x: position.x, y: position.y }
      : node
  })
  edges.value = parsed.edges
  selectedIds.value = selectedIds.value.filter((id) => nodes.value.some((node) => node.id === id))
  if (!nodes.value.some((node) => node.id === selectedId.value)) selectedId.value = ''
  const raw = value.definition.meta as Record<string, unknown> | undefined
  Object.assign(
    meta,
    defaultWorkflowMeta(),
    detailToMeta({
      name: String(raw?.name ?? value.workflowId),
      version: String(raw?.version ?? '1.0.0'),
      description: String(raw?.description ?? ''),
      ...(raw ?? {})
    }),
    {
      displayName: String(raw?.display_name ?? raw?.name ?? value.workflowId),
      originalDefinition: JSON.parse(JSON.stringify(value.definition)),
      resources: workflowScriptResources(value.definition.resources),
      entryNodeId: String(value.definition.entry ?? '')
    }
  )
  problems.value = value.problems
  const validationReceipt = window.history.state?.workflowValidation as
    { workflowId?: string; revision?: number } | undefined
  if (
    validationReceipt?.workflowId === value.workflowId &&
    validationReceipt.revision === value.revision
  ) {
    activeTab.value = 'checks'
    validated.value = true
  }
  saved.value = snapshot()
  validatedSnapshot.value = validated.value ? saved.value : ''
  publishedSnapshot.value = value.status === 'published' ? saved.value : ''
  history.value = [snapshot()]
  historyIndex.value = 0
}
watch(
  () => route.params.workflowId,
  async () => {
    if (route.params.workflowId) {
      const value = await reload()
      if (value)
        accept(
          value.value,
          value.layout.draftRevision === value.value.revision ? value.layout.layout : {},
          value.layout.draftRevision === value.value.revision ? value.layout.viewport : {}
        )
    } else {
      draftConflict.value = false
      nodeClipboard.value = undefined
      selectedId.value = ''
      selectedIds.value = []
      pendingDefinition.value = ''
      resetEditors()
      validated.value = false
      activeTab.value = 'info'
      draft.value = undefined
      creationUnknown.value = ''
      creationCheck.value = ''
      creationStorageError.value = false
      try {
        const pending = sessionStorage.getItem(creationKey())
        if (pending && !/^[a-z0-9][a-z0-9-]{0,127}$/.test(pending)) throw Error('invalid')
        creationUnknown.value = pending || ''
      } catch {
        creationStorageError.value = true
      }
      workflowId.value = creationUnknown.value || `workflow-${crypto.randomUUID()}`
      nodes.value = [createNode('start', 'start', 0), createNode('end', 'end', 1)]
      edges.value = [
        { id: crypto.randomUUID(), source: 'start', target: 'end', condition: '', priority: 0 }
      ]
      Object.assign(meta, defaultWorkflowMeta(), {
        capability: recommendedWorkflowCapability(),
        entryNodeId: 'start'
      })
      saved.value = snapshot()
      history.value = [saved.value]
      historyIndex.value = 0
    }
  },
  { immediate: true }
)
function addEdge() {
  edges.value.push({
    id: crypto.randomUUID(),
    source: selectedId.value,
    target: '',
    condition: '',
    priority: 0
  })
}
async function focusSelectedNode() {
  propertiesOpen.value = !!selectedId.value
  await nextTick()
  if (propertiesOpen.value && window.matchMedia('(max-width: 900px)').matches) {
    propertiesPanel.value?.focus({ preventScroll: true })
  } else {
    canvas.value?.focusNode(selectedId.value)
  }
}
async function locateProblem(problem: WorkflowValidationProblem) {
  const nodeIndex = /nodes(?:\[|\/)(\d+)/.exec(problem.objectRef + ' ' + problem.fieldPath)?.[1]
  const node =
    nodes.value.find(
      (item) =>
        item.id === problem.objectRef ||
        problem.objectRef === `node:${item.id}` ||
        problem.objectRef === `nodes.${item.id}`
    ) ?? (nodeIndex !== undefined ? nodes.value[Number(nodeIndex)] : undefined)
  if (node) {
    activeTab.value = 'design'
    selectedId.value = node.id
    await focusSelectedNode()
    nodeProperties.value?.focusField?.(problem.fieldPath)
  } else {
    if (/edge|connection/i.test(problem.objectRef + problem.fieldPath)) {
      const id = problem.objectRef.replace(/^edge:/, ''),
        index = /edges(?:\[|\/)(\d+)/.exec(problem.objectRef + ' ' + problem.fieldPath)?.[1]
      await editEdge(
        edges.value.find((edge) => edge.id === id)?.id ?? edges.value[Number(index)]?.id ?? ''
      )
      return
    }
    activeTab.value = 'info'
    await nextTick()
    const key = problem.fieldPath.replace(/^meta[./]/, '').split(/\.|\[|\//)[0] ?? ''
    const target = [...document.querySelectorAll<HTMLElement>('[data-workflow-field]')].find(
      (element) => element.dataset.workflowField?.split(' ').includes(key)
    )
    target?.scrollIntoView?.({ block: 'nearest' })
    target?.querySelector<HTMLElement>('input,textarea,button')?.focus()
  }
}
function editConnections(nodeId: string) {
  selectedId.value = nodeId
  activeTab.value = 'connections'
}
function resetEditors() {
  governanceValid.value = true
  resourcePending.value = false
  editorGeneration.value++
}
async function editEdge(id: string) {
  activeTab.value = 'connections'
  await nextTick()
  const edge = edges.value.find((edge) => edge.id === id)
  if (edge) edgeTable.value?.setCurrentRow(edge)
}
function addNode() {
  const id = nodeType.value + '_' + crypto.randomUUID().slice(0, 8)
  nodes.value.push(createNode(nodeType.value, id, nodes.value.length))
  selectedId.value = id
  void openNodeProperties(id)
}
function openNodePlacement(placement: WorkflowNodePlacement) {
  if (readonly.value || busy.value || loadingDraft.value) return
  placementTrigger.value =
    document.activeElement instanceof Element &&
    typeof (document.activeElement as HTMLElement).focus === 'function'
      ? (document.activeElement as HTMLElement)
      : undefined
  placementAdded.value = false
  const edge =
    placement.kind === 'edge' ? edges.value.find((edge) => edge.id === placement.edgeId) : undefined
  const source = nodes.value.find(
    (node) => node.id === (placement.kind === 'edge' ? edge?.source : placement.nodeId)
  )
  if (!source || source.type === 'end') return
  selectedId.value = source.id
  placementChoices.value = []
  if (placement.kind === 'after') {
    if (source.type === 'menu') {
      placementChoices.value = source.menuActions
        .filter((action) => action.id)
        .map((action) => ({
          value: 'action:' + action.id,
          label:
            action.label +
            ' → ' +
            (nodes.value.find((node) => node.id === action.to)?.label || action.to || '尚未连接'),
          placement: { kind: 'action', nodeId: source.id, actionId: action.id }
        }))
      if (!placementChoices.value.length) {
        error.value = '请先为菜单配置至少一个带标识的选项，再添加后续节点'
        void focusSelectedNode()
        return
      }
    } else
      placementChoices.value = edges.value
        .filter((edge) => edge.source === source.id)
        .map((edge) => ({
          value: 'edge:' + edge.id,
          label:
            (nodes.value.find((node) => node.id === edge.target)?.label || edge.target) +
            (edge.condition ? ' · ' + edge.condition : ' · 默认连接'),
          placement: { kind: 'edge', edgeId: edge.id }
        }))
  }
  nodePlacement.value = placement
  placementChoice.value =
    placementChoices.value.length === 1 ? placementChoices.value[0]!.value : ''
  placementNodeType.value = !['start', 'end'].includes(nodeType.value) ? nodeType.value : 'tool'
  nodePlacementOpen.value = true
}
function restorePlacementFocus() {
  if (!placementAdded.value && placementTrigger.value?.isConnected) {
    placementTrigger.value.focus({ preventScroll: true })
    return
  }
  const button = [...document.querySelectorAll<HTMLElement>('[data-node-id]')].find(
    (element) => element.dataset.nodeId === selectedId.value
  )
  button?.focus({ preventScroll: true })
}
async function addPlacedNode() {
  if (readonly.value || busy.value || !nodePlacement.value) return
  const placement = placementChoices.value.length
    ? placementChoices.value.find((choice) => choice.value === placementChoice.value)?.placement
    : nodePlacement.value
  if (!placement) return
  try {
    const id = placementNodeType.value + '_' + crypto.randomUUID().slice(0, 8),
      node = createNode(placementNodeType.value, id, nodes.value.length)
    const graph = connectWorkflowNode(nodes.value, edges.value, node, placement)
    replaying = true
    nodes.value = graph.nodes
    edges.value = graph.edges
    selectedId.value = id
    placementAdded.value = true
    nodePlacementOpen.value = false
    await nextTick()
    await canvas.value?.focusNode(id)
    replaying = false
    rememberEdit()
  } catch (caught) {
    replaying = false
    error.value = caught instanceof Error ? caught.message : '无法添加节点'
  }
}
/** 仅用于新建，读取与导入的草稿保留原字段。空参数和失败策略沿用执行契约缺省行为。 */
function createNode(type: string, id: string, index: number): BuilderNode {
  return {
    id,
    type,
    label: nodeKinds.find((n) => n.type === type)?.label ?? type,
    x: 50 + (index % 4) * 260,
    y: 40 + Math.floor(index / 4) * 130,
    resultTo: recommendedWorkflowResultTo(type, nodes.value),
    assignPairs: [],
    menuActions: [],
    ...(type === 'tool' ? { argsText: '{}', onError: 'fail' } : {}),
    ...(type === 'script' ? { scriptInputText: '{}' } : {})
  }
}
function removeNode(id: string = selectedId.value) {
  void removeNodes([id])
}
async function removeNodes(ids: string[]) {
  if (readonly.value || busy.value || loadingDraft.value || !ids.length) return
  const removed = new Set(ids)
  replaying = true
  try {
    nodes.value = nodes.value.filter((node) => !removed.has(node.id))
    edges.value = edges.value.filter(
      (edge) => !removed.has(edge.source) && !removed.has(edge.target)
    )
    selectedIds.value = []
    selectedId.value = ''
    propertiesOpen.value = false
  } finally {
    replaying = false
  }
  rememberEdit()
  await nextTick()
  canvas.value?.focusCanvas()
}
function copyNodes(ids: string[]) {
  if (busy.value || loadingDraft.value) return
  error.value = ''
  try {
    nodeClipboard.value = captureWorkflowSelection(nodes.value, edges.value, ids)
    pasteSequence = 0
    message.success(`已复制 ${nodeClipboard.value.nodes.length} 个节点`)
  } catch (caught) {
    error.value = caught instanceof Error ? caught.message : '无法复制所选节点'
  }
}
async function pasteNodes() {
  if (readonly.value || busy.value || loadingDraft.value || !nodeClipboard.value) return
  error.value = ''
  let pasted = false
  try {
    const offset = 40 * (pasteSequence + 1)
    const graph = pasteWorkflowSelection(nodes.value, edges.value, nodeClipboard.value, {
      x: offset,
      y: offset
    })
    replaying = true
    nodes.value = graph.nodes
    edges.value = graph.edges
    selectedIds.value = graph.selectedIds
    selectedId.value = graph.selectedIds[0] ?? ''
    propertiesOpen.value = false
    pasteSequence++
    pasted = true
    await nextTick()
    await canvas.value?.focusNode(selectedId.value, true)
  } catch (caught) {
    error.value = caught instanceof Error ? caught.message : '无法粘贴节点'
  } finally {
    replaying = false
  }
  if (pasted) rememberEdit()
}
function arrange() {
  if (readonly.value || busy.value) return
  nodes.value = nodes.value.map((node, i) => ({
    ...node,
    x: 50 + (i % 4) * 260,
    y: 40 + Math.floor(i / 4) * 130
  }))
}
async function revisionWrite<T>(operation: () => Promise<T>): Promise<T> {
  try {
    return await operation()
  } catch (caught) {
    if (draft.value && caught instanceof HttpClientError && caught.status === 409) {
      draftConflict.value = true
      validated.value = false
    }
    throw caught
  }
}
/** 创建没有原请求回执，派发前先保留标识；任何不确定结果只允许读取核对。 */
async function createDraft(id: string, definition: Record<string, unknown>) {
  let key: string
  try {
    key = creationKey()
    sessionStorage.setItem(key, id)
  } catch {
    creationStorageError.value = true
    throw Error('无法保存创建记录，未发送创建请求；请恢复浏览器会话存储')
  }
  let result: WorkflowDraft
  try {
    result = await api.workflowDrafts.create(id, definition)
    if (!result || result.workflowId !== id) throw Error('创建回执身份无法核实')
  } catch (reason) {
    const rejected =
      reason instanceof HttpClientError &&
      (([400, 422].includes(reason.status) && reason.code === 'VALIDATION_ERROR') ||
        (reason.status === 401 && reason.code === 'UNAUTHORIZED') ||
        (reason.status === 403 && reason.code === 'FORBIDDEN') ||
        (reason.status === 409 && reason.code === 'CONFLICT'))
    if (rejected) {
      try {
        sessionStorage.removeItem(key)
      } catch {
        creationStorageError.value = true
      }
    } else creationUnknown.value = id
    throw reason
  }
  try {
    sessionStorage.removeItem(key)
  } catch {
    draft.value = result
    pendingDefinition.value = JSON.stringify(definition)
    creationStorageError.value = true
    throw Error('工作流已创建，但创建记录无法清除，布局尚未保存；请恢复浏览器会话存储')
  }
  return result
}
async function checkUnknownCreation() {
  await run(async () => {
    try {
      const current = await api.workflowDrafts.get(creationUnknown.value)
      if (current.workflowId !== creationUnknown.value) throw Error('读取到的工作流身份不一致')
      creationCheck.value = '找到同标识草稿；请人工核对，读取结果不能证明原创建请求已经结束。'
    } catch (reason) {
      if (reason instanceof HttpClientError && reason.status === 404) {
        reason.claimFeedback()
        creationCheck.value = '当前未找到原标识；这不能证明原请求未受理，请稍后继续核对。'
      } else throw reason
    }
  })
}

/** 创建或保存与布局写入由当前操作持有；导航在全部请求完成后进行。 */
async function persistDraft() {
  if (creationBlocked.value) throw Error('创建结果尚未确认，请先核对原工作流')
  if (readonly.value) throw Error('此工作流已归档，不能保存修改')
  if (draftConflict.value) throw Error('请先查看最新差异，确认如何保留当前修改')
  if (!/^[a-z0-9][a-z0-9-]{0,127}$/.test(workflowId.value)) {
    activeTab.value = 'info'
    throw Error('工作流业务标识须为 1–128 位小写字母、数字或连字符，且不能以连字符开头')
  }
  if (!governanceValid.value || resourcePending.value) {
    activeTab.value = 'info'
    throw Error('请先修正输入与输出格式，或保存正在编辑的脚本文件')
  }
  const issues = workflowProblems(nodes.value, edges.value, meta, referenceContext)
  problems.value = issues
  if (issues.some((issue) => issue.severity === 'error')) {
    activeTab.value = 'checks'
    throw Error('请先处理校验面板中的问题')
  }
  meta.name = workflowId.value
  const definition = workflowDefinition(nodes.value, edges.value, meta)
  const positions = Object.fromEntries(nodes.value.map((n) => [n.id, { x: n.x, y: n.y }]))
  const submittedDefinition = JSON.stringify(definition)
  const owner = installOwner.value,
    identity = auth.identityVersion,
    originalRoute = route.params.workflowId
  const current = () =>
    !pageDisposed &&
    !!owner &&
    installOwner.value === owner &&
    auth.identityVersion === identity &&
    route.params.workflowId === originalRoute
  if (!current()) throw Error('当前身份不可用，请重新登录')
  const value =
    pendingDefinition.value === submittedDefinition && draft.value
      ? draft.value
      : draft.value
        ? await revisionWrite(() =>
            api.workflowDrafts.save(draft.value!.workflowId, draft.value!.revision, definition)
          )
        : await createDraft(workflowId.value, definition)
  if (!current()) throw Error('身份或页面已变更，草稿内容可能已保存，请重新读取后核对布局')
  // 内容已写入时保留身份；布局失败后只补写布局，不重复保存相同定义。
  draft.value = value
  pendingDefinition.value = submittedDefinition
  saved.value = ''
  await revisionWrite(() =>
    api.workflowDrafts.snapshot.save(value.workflowId, value.revision, positions, viewport.value)
  )
  if (!current()) throw Error('身份或页面已变更，请重新读取草稿和布局')
  accept(value, positions, viewport.value)
  message.success('工作流草稿已保存')
  return value
}
async function navigateSaved(value: WorkflowDraft, checked = false, resumeTest = false) {
  if (route.params.workflowId) return
  await router.replace({
    path: '/admin/workflows/' + encodeURIComponent(value.workflowId),
    state: checked
      ? { workflowValidation: { workflowId: value.workflowId, revision: value.revision } }
      : resumeTest
        ? { workflowTestResume: value.workflowId }
        : {}
  })
}
async function save() {
  const result = await run(persistDraft)
  if (result) await navigateSaved(result)
  return !!result
}
async function saveForTest() {
  const owner = installOwner.value,
    identity = auth.identityVersion,
    originalWorkflow = workflowId.value,
    isNew = !route.params.workflowId
  const result = await run(persistDraft)
  if (
    !result ||
    !owner ||
    owner !== installOwner.value ||
    identity !== auth.identityVersion ||
    originalWorkflow !== workflowId.value
  )
    return
  await navigateSaved(result, false, true)
  if (isNew && String(route.params.workflowId) === result.workflowId) testOpen.value = true
  return result.revision
}
async function validate() {
  if (readonly.value || busy.value || draftConflict.value || creationBlocked.value) return
  await run(async () => {
    if (!governanceValid.value || resourcePending.value)
      throw Error('请先修正输入与输出格式，或保存正在编辑的脚本文件')
    const captured = snapshot(),
      current = draft.value
    const definition = workflowDefinition(nodes.value, edges.value, {
      ...meta,
      name: workflowId.value
    })
    const validation = current
      ? dirty.value
        ? await revisionWrite(() =>
            api.workflowDrafts.validateContent(current.workflowId, current.revision, definition)
          )
        : await api.workflowDrafts.validate(current.workflowId)
      : await api.workflowDrafts.validateNewContent(definition)
    if (captured !== snapshot()) {
      validated.value = false
      throw Error('校验期间内容已变化，请重新校验')
    }
    problems.value = validation.problems
    if (validation.revision !== (current?.revision ?? 0)) {
      validated.value = false
      draftConflict.value = true
      activeTab.value = 'checks'
      throw new HttpClientError({
        status: 409,
        userMessage: '校验期间草稿已变化，请先查看最新差异'
      })
    }
    validated.value = true
    validatedSnapshot.value = captured
    activeTab.value = 'checks'
  })
}
async function acceptPublication(
  receipt: WorkflowSaveAndPublishReceipt | undefined,
  owner: string,
  id: string,
  requestId: string,
  session: string,
  identity: number
) {
  if (
    pageDisposed ||
    publicationSession() !== session ||
    auth.identityVersion !== identity ||
    !auth.user?.permissions.includes('foundation.workflows.read') ||
    installOwner.value !== owner ||
    route.params.workflowId !== id ||
    publicationPending.value !== requestId
  )
    return
  if (
    !receipt ||
    receipt.request_id !== requestId ||
    receipt.workflow_id !== id ||
    !['PROCESSING', 'REJECTED', 'SUCCEEDED'].includes(receipt.status)
  ) {
    message.warning('尚未查到完整匹配的原发布回执，请稍后核对')
    return
  }
  if (receipt.status === 'PROCESSING') {
    message.warning('原发布仍在处理，请稍后核对')
    return
  }
  if (receipt.status === 'REJECTED' && (!receipt.error_code || !receipt.error_detail)) {
    message.warning('原发布拒绝回执不完整，请稍后核对')
    return
  }
  if (receipt.status === 'SUCCEEDED') {
    const value = receipt.publication
    if (
      !value ||
      value.workflow_id !== id ||
      !Number.isSafeInteger(value.draft_revision) ||
      value.draft_revision < 1 ||
      !/^[1-9]\d*$/.test(value.definition_version_id) ||
      !/^[1-9]\d*$/.test(value.capability_version_id) ||
      !Number.isSafeInteger(value.definition_version) ||
      value.definition_version < 1
    ) {
      message.warning('原发布回执不完整，请稍后核对')
      return
    }
    notifyWorkflowPublished(owner)
    const fresh = await reload()
    if (
      pageDisposed ||
      publicationSession() !== session ||
      auth.identityVersion !== identity ||
      !auth.user?.permissions.includes('foundation.workflows.read') ||
      installOwner.value !== owner ||
      route.params.workflowId !== id ||
      publicationPending.value !== requestId
    )
      return
    if (
      !fresh ||
      fresh.value.workflowId !== id ||
      fresh.value.revision < value.draft_revision ||
      fresh.layout.workflowId !== id ||
      fresh.layout.draftRevision !== fresh.value.revision
    ) {
      message.warning('发布已确认，但草稿未刷新，请核对原发布后重新读取')
      return
    }
    accept(fresh.value, fresh.layout.layout, fresh.layout.viewport)
  }
  try {
    sessionStorage.removeItem(publicationKey(owner, id))
    publicationStorageError.value = false
  } catch {
    publicationStorageError.value = true
    return
  }
  publicationPending.value = ''
  if (receipt.status === 'REJECTED') {
    const detail = receipt.error_detail
    const text =
      typeof detail === 'string'
        ? detail
        : detail && typeof detail === 'object' && 'message' in detail
          ? String(detail.message)
          : '请检查工作流配置'
    if (receipt.error_code === 'CONFLICT') draftConflict.value = true
    if (
      detail &&
      typeof detail === 'object' &&
      'problems' in detail &&
      Array.isArray(detail.problems)
    ) {
      problems.value = detail.problems as WorkflowValidationProblem[]
      validated.value = false
      activeTab.value = 'checks'
    }
    message.error(`保存并发布未完成：${text}`)
  } else message.success('工作流已保存并发布')
}
async function checkPublication(automatic = false) {
  const requestId = publicationPending.value,
    owner = installOwner.value,
    id = String(route.params.workflowId ?? ''),
    session = publicationSession(),
    identity = auth.identityVersion
  if (
    !requestId ||
    !owner ||
    !id ||
    !session ||
    !auth.user?.permissions.includes('foundation.workflows.read')
  )
    return
  await run(async () => {
    try {
      await acceptPublication(
        await api.workflowDrafts.saveAndPublishResult(id, requestId),
        owner,
        id,
        requestId,
        session,
        identity
      )
    } catch (reason) {
      if (reason instanceof HttpClientError) reason.claimFeedback()
      if (
        !pageDisposed &&
        publicationSession() === session &&
        auth.identityVersion === identity &&
        !!auth.user?.permissions.includes('foundation.workflows.read') &&
        installOwner.value === owner &&
        route.params.workflowId === id &&
        publicationPending.value === requestId
      )
        throw reason
    }
  })
  if (
    automatic &&
    !pageDisposed &&
    publicationSession() === session &&
    auth.identityVersion === identity &&
    installOwner.value === owner &&
    route.params.workflowId === id &&
    publicationPending.value === requestId &&
    !auth.user?.permissions.includes('foundation.workflows.read')
  )
    publicationAutoCheck.value = { owner, id, requestId }
}
watch(
  [
    publicationAutoCheck,
    busy,
    loadingDraft,
    () => draft.value?.workflowId,
    () => auth.user?.permissions.includes('foundation.workflows.read')
  ],
  ([pending, active, loading, id, allowed]) => {
    if (
      !pending ||
      active ||
      loading ||
      loadError.value ||
      id !== pending.id ||
      !publicationSession() ||
      !allowed
    )
      return
    publicationAutoCheck.value = undefined
    void checkPublication(true)
  },
  { immediate: true }
)
async function publish() {
  if (busy.value || draftConflict.value || readonly.value) return
  const noticeOwner = installOwner.value
  const noticeSession = publicationSession()
  const noticeIdentity = auth.identityVersion
  const noticeId = workflowId.value
  await run(async () => {
    if (!draft.value || readonly.value || publishedSnapshot.value === snapshot()) return
    const saveFirst = dirty.value
    if (
      saveFirst &&
      !(await confirm({
        title: '保存并发布工作流',
        confirmText: '保存并发布',
        content: `保存并校验“${meta.displayName || workflowId.value}”的当前配置，校验通过后发布？新执行使用新版本，已有执行继续原版本。`
      }))
    )
      return
    if (saveFirst) {
      if (!governanceValid.value || resourcePending.value)
        throw Error('请先修正输入与输出格式，或保存正在编辑的脚本文件')
      const issues = workflowProblems(nodes.value, edges.value, meta, referenceContext)
      problems.value = issues
      if (issues.some((issue) => issue.severity === 'error')) {
        activeTab.value = 'checks'
        throw Error('请先处理校验面板中的问题')
      }
      const owner = installOwner.value,
        id = draft.value.workflowId,
        requestId = crypto.randomUUID(),
        session = publicationSession(),
        identity = auth.identityVersion
      if (!owner || !session || !auth.user?.permissions.includes('foundation.workflows.read'))
        throw Error('需要工作流读取权限才能核对发布结果')
      const definition = workflowDefinition(nodes.value, edges.value, { ...meta, name: id })
      const layout = Object.fromEntries(
        nodes.value.map((node) => [node.id, { x: node.x, y: node.y }])
      )
      try {
        sessionStorage.setItem(publicationKey(owner, id), requestId)
      } catch {
        publicationStorageError.value = true
        throw Error('无法保存发布请求记录，未发送请求')
      }
      publicationPending.value = requestId
      try {
        const result = await api.workflowDrafts.saveAndPublish({
          request_id: requestId,
          workflow_id: id,
          revision: draft.value.revision,
          definition,
          layout,
          viewport: { ...viewport.value }
        })
        await acceptPublication(result, owner, id, requestId, session, identity)
      } catch (reason) {
        if (reason instanceof HttpClientError) reason.claimFeedback()
        if (
          pageDisposed ||
          publicationSession() !== session ||
          auth.identityVersion !== identity ||
          installOwner.value !== owner ||
          route.params.workflowId !== id ||
          publicationPending.value !== requestId
        )
          return
        if (
          reason instanceof HttpClientError &&
          reason.code === 'WORKFLOW_PUBLISH_PRE_DISPATCH_REJECTED'
        ) {
          try {
            sessionStorage.removeItem(publicationKey(owner, id))
            publicationPending.value = ''
          } catch {
            publicationStorageError.value = true
          }
        }
        throw reason
      }
      return
    }
    const publicationDraft = draft.value
    const result = await api.workflowDrafts.validate(publicationDraft.workflowId)
    problems.value = result.problems
    if (result.revision !== publicationDraft.revision) {
      validated.value = false
      draftConflict.value = true
      activeTab.value = 'checks'
      throw new HttpClientError({
        status: 409,
        userMessage: '校验期间草稿已变化，请先查看最新差异'
      })
    }
    validated.value = true
    validatedSnapshot.value = snapshot()
    if (result.problems.some((p) => p.severity === 'error')) {
      activeTab.value = 'checks'
      throw Error('请修复校验问题后发布')
    }
    if (
      !saveFirst &&
      !(await confirm({
        title: '发布工作流',
        confirmText: '发布工作流',
        content: `发布“${meta.displayName || workflowId.value}”的当前草稿？新执行使用新版本，已有执行继续原版本。`
      }))
    )
      return
    await revisionWrite(() =>
      api.workflowDrafts.publish(publicationDraft.workflowId, publicationDraft.revision)
    )
    if (
      !pageDisposed &&
      noticeOwner &&
      noticeOwner === installOwner.value &&
      noticeSession &&
      noticeSession === publicationSession() &&
      noticeIdentity === auth.identityVersion &&
      noticeId === workflowId.value &&
      route.params.workflowId === noticeId
    )
      notifyWorkflowPublished(noticeOwner)
    publishedSnapshot.value = snapshot()
    message.success('工作流已发布')
    const value = await reload()
    if (value)
      accept(
        value.value,
        value.layout.draftRevision === value.value.revision ? value.layout.layout : {},
        value.layout.draftRevision === value.value.revision ? value.layout.viewport : {}
      )
  })
}
async function refreshDraft() {
  await run(async () => {
    if (
      dirty.value &&
      !(await confirm({
        title: '重新读取工作流',
        content: '重新读取会放弃当前未保存的修改。',
        confirmText: '放弃并读取'
      }))
    )
      return
    const value = await reload()
    if (value)
      accept(
        value.value,
        value.layout.draftRevision === value.value.revision ? value.layout.layout : {},
        value.layout.draftRevision === value.value.revision ? value.layout.viewport : {}
      )
  })
}
const publications = ref<WorkflowPublication[]>([]),
  publicationDetail = ref<WorkflowPublicationDetail>(),
  publicationOpen = ref(false)
const testOpen = ref(
  window.history.state?.workflowTestResume === route.params.workflowId && !!route.params.workflowId
)
if (testOpen.value) {
  const state = { ...window.history.state }
  delete state.workflowTestResume
  window.history.replaceState(state, '')
}
const checkedTestRecoveryScopes = new Set<string>()
let automaticTestOwner: { id: string; session: string; identity: number } | undefined
watch(
  [
    () => route.params.workflowId,
    workflowId,
    draft,
    busy,
    readonly,
    loadingDraft,
    creationBlocked,
    draftConflict,
    () => auth.identityVersion,
    () => auth.sessionIdentity,
    () => auth.user?.permissions.includes('foundation.workflows.update')
  ],
  () => {
    const id = typeof route.params.workflowId === 'string' ? route.params.workflowId : ''
    const session = publicationSession()
    const permitted = !!auth.user?.permissions.includes('foundation.workflows.update')
    if (
      automaticTestOwner &&
      (id !== automaticTestOwner.id ||
        session !== automaticTestOwner.session ||
        auth.identityVersion !== automaticTestOwner.identity ||
        !permitted)
    ) {
      testOpen.value = false
      automaticTestOwner = undefined
    }
    if (
      !id ||
      !session ||
      !permitted ||
      testOpen.value ||
      busy.value ||
      readonly.value ||
      loadingDraft.value ||
      creationBlocked.value ||
      draftConflict.value ||
      workflowId.value !== id ||
      draft.value?.workflowId !== id
    )
      return
    const scope = JSON.stringify([session, id])
    if (checkedTestRecoveryScopes.has(scope)) return
    checkedTestRecoveryScopes.add(scope)
    try {
      const text = sessionStorage.getItem(workflowTestRecoveryKey(session, id))
      if (!text) return
      const recovery = parseWorkflowTestRecovery(text)
      if (
        !recovery.requestId ||
        !recovery.testSessionId ||
        !recovery.replyOperations?.some(
          (operation) => !operation.accepted && operation.testSessionId === recovery.testSessionId
        )
      )
        return
      automaticTestOwner = { id, session, identity: auth.identityVersion }
      testOpen.value = true
    } catch {
      // 手动打开时由测试面板展示原记录读取错误。
    }
  },
  { immediate: true, flush: 'post' }
)
const testPublications = computed(() =>
  publications.value
    .filter((value) => /^[1-9]\d*$/.test(String(value.definitionVersionId)))
    .map((value) => ({
      id: String(value.definitionVersionId),
      label: '发布版本 ' + value.definitionVersion
    }))
)
async function openTest() {
  testOpen.value = true
  if (!draft.value) return
  const id = workflowId.value
  await run(async () => {
    const values = await api.workflowDrafts.history(id)
    if (id === workflowId.value) publications.value = values
  })
}
async function loadPublications(tab: string | number) {
  if (tab !== 'history' || !draft.value) return
  await run(async () => {
    publications.value = await api.workflowDrafts.history(draft.value!.workflowId)
  })
}
async function viewPublication(value: WorkflowPublication) {
  await run(async () => {
    const detail = await api.workflowDrafts.publicationDetail(
      workflowId.value,
      value.definitionVersionId
    )
    publicationDetail.value = detail
    publicationOpen.value = true
  })
}
async function restorePublication(value: WorkflowPublication) {
  if (busy.value || readonly.value || draftConflict.value) return
  await run(async () => {
    if (!draft.value || dirty.value) throw Error('请先保存当前修改')
    if (
      !(await confirm({
        title: '恢复工作流草稿',
        confirmText: '恢复为草稿',
        danger: true,
        content: `将发布修订 ${value.definitionVersion} 恢复到当前工作草稿？草稿内容会被覆盖，发布后才会生效。`
      }))
    )
      return
    const restored = await revisionWrite(() =>
      api.workflowDrafts.restore(
        draft.value!.workflowId,
        draft.value!.revision,
        value.definitionVersionId
      )
    )
    accept(restored, {})
    message.success('历史工作流已恢复为草稿，发布后生效')
  })
}
async function loadDiff() {
  await run(async () => {
    if (!draft.value) return
    if (diffSource.value === 'saved') {
      changes.value = (await api.workflowDrafts.diff(draft.value.workflowId)).changes
      diffCaption.value = '比较对象：服务端已保存草稿与当前发布版本；不包含本地未保存修改。'
      return
    }
    const current = (await api.workflowDrafts.history(draft.value.workflowId)).find(
      (value) => value.current
    )
    const publication = current
      ? await api.workflowDrafts.publicationDetail(
          draft.value.workflowId,
          current.definitionVersionId
        )
      : undefined
    changes.value = workflowDefinitionChanges(
      publication?.definition ?? {},
      workflowDefinition(nodes.value, edges.value, meta)
    )
    diffCaption.value = `比较对象：当前编辑内容（${dirty.value ? '包含未保存修改' : '已保存'}）与${current ? '发布修订 ' + current.definitionVersion : '空定义，尚无发布版本'}。`
  })
}
async function compareRemoteDraft() {
  await run(async () => {
    if (!draft.value) return
    const latest = await api.workflowDrafts.get(draft.value.workflowId)
    changes.value = workflowDefinitionChanges(
      latest.definition,
      workflowDefinition(nodes.value, edges.value, meta)
    )
    diffCaption.value = `比较对象：当前本地内容相对服务端最新草稿修订 ${latest.revision}；读取不会覆盖本地草稿。`
    activeTab.value = 'checks'
  })
}
function exportDefinition() {
  error.value = ''
  if (resourcePending.value || !governanceValid.value) {
    error.value = '请先保存脚本资源或修正 Schema 后再导出，当前编辑内容仍保留。'
    return
  }
  try {
    const blob = new Blob(
        [JSON.stringify(workflowDefinition(nodes.value, edges.value, meta), null, 2)],
        { type: 'application/json' }
      ),
      url = URL.createObjectURL(blob),
      a = document.createElement('a')
    a.href = url
    a.download = (workflowId.value || 'workflow') + '.json'
    a.click()
    setTimeout(() => URL.revokeObjectURL(url), 1000)
  } catch {
    error.value = '未能生成导出文件，当前内容仍保留在编辑区。请检查节点和配置后重新导出'
  }
}
const definitionUpload = ref<UploadInstance>()
async function importDefinition(selection: UploadFile) {
  const file = selection.raw
  definitionUpload.value?.clearFiles()
  if (!file || readonly.value || busy.value) return
  await run(async () => {
    if (
      dirty.value &&
      !(await confirm({
        title: '导入工作流定义',
        content: '导入会替换当前流程节点和配置，并丢弃尚未保存的修改。',
        confirmText: '替换并导入'
      }))
    )
      return
    const value = JSON.parse(await file.text())
    const parsed = parseWorkflowJson(value)
    await verifyWorkflowResourceIntegrity(value.resources)
    const importedMeta = Object.assign(
      {},
      defaultWorkflowMeta(),
      detailToMeta(value.meta ?? { name: workflowId.value, version: '', description: '' }),
      {
        displayName: value.meta?.display_name ?? value.meta?.name ?? '',
        originalDefinition: JSON.parse(JSON.stringify(value)),
        resources: workflowScriptResources(value.resources),
        entryNodeId: String(value.entry ?? '')
      }
    )
    replaying = true
    try {
      nodes.value = parsed.nodes
      edges.value = parsed.edges
      Object.assign(meta, importedMeta)
      resetEditors()
      selectedId.value = ''
      selectedIds.value = []
      propertiesOpen.value = false
      problems.value = []
      validated.value = false
    } finally {
      replaying = false
    }
    rememberEdit()
  })
}
interface InstallSlot {
  slot: string
  label: string
  type: string
  capability_id: string
  version_constraint: string
  required?: boolean
  versionId?: string
  deploymentKey?: string
}
const toolContracts = ref<Record<string, WorkflowToolContract>>({})
const referenceContext = {
  validate: (path: string, nodeId: string, includeCurrent: boolean) =>
    customVariableError(
      path,
      workflowVariables(nodes.value, edges.value, nodeId, meta, toolContracts.value, {
        includeCurrent
      })
    )
}
const visibleProblems = computed(() => {
  const references = workflowProblems(nodes.value, edges.value, meta, referenceContext).filter(
    (problem) => problem.code === 'VARIABLE_REFERENCE'
  )
  return [
    ...problems.value.filter((problem) => problem.code !== 'VARIABLE_REFERENCE'),
    ...references
  ]
})
let schemaRequest = 0
const schemaLoading = ref(false)
const schemaStale = ref(false)
async function refreshToolContracts(manual = false) {
  const request = ++schemaRequest
  schemaLoading.value = false
  if (!manual) {
    toolContracts.value = {}
    schemaStale.value = false
  }
  let declarations: Array<{ name: string; requirement: string }>,
    requirements: Array<{
      slot: string
      type: string
      capability_id: string
      version_constraint: string
    }>
  try {
    declarations = JSON.parse(meta.toolsText || '[]')
    requirements = JSON.parse(meta.capabilityRequirementsText || '[]')
  } catch {
    if (manual) message.warning('工具声明无法读取，请检查工作流信息中的工具与能力槽配置。')
    return
  }
  if (!Array.isArray(declarations) || !Array.isArray(requirements) || !declarations.length) {
    if (manual) message.info('当前没有可读取的工具声明。')
    return
  }
  schemaLoading.value = true
  try {
    const definitions = await api.capabilities.definitions('TOOL', 'PUBLISHED'),
      result: Record<string, WorkflowToolContract> = {}
    for (const tool of declarations) {
      const slot = requirements.find((r) => r.slot === tool.requirement && r.type === 'tool'),
        definition = definitions.find(
          (d) => d.capabilityType === 'TOOL' && d.capabilityKey === slot?.capability_id
        )
      if (!slot || !definition) continue
      const versions = await api.capabilities.versions(definition.id, 'PUBLISHED'),
        version = versions.find(
          (v) =>
            v.id === definition.defaultVersionId &&
            matchesWorkflowVersion(slot.version_constraint, v.versionKey)
        )
      const schema = version?.descriptor.input_schema,
        output = version?.descriptor.output_schema
      if (
        version &&
        schema &&
        typeof schema === 'object' &&
        !Array.isArray(schema) &&
        typeof version.descriptor.tool_name === 'string' &&
        version.descriptor.tool_name.trim()
      )
        result[tool.name] = {
          inputSchema: schema as Record<string, unknown>,
          ...(output && typeof output === 'object' && !Array.isArray(output)
            ? { outputSchema: output as Record<string, unknown> }
            : {}),
          versionId: version.id,
          capabilityKey: definition.capabilityKey,
          versionKey: version.versionKey,
          toolName: String(version.descriptor.tool_name ?? ''),
          sensitiveFields: Array.isArray(version.descriptor.sensitive_fields)
            ? version.descriptor.sensitive_fields.filter(
                (field): field is string => typeof field === 'string'
              )
            : [],
          outputSchemaPath: 'structuredContent'
        }
    }
    if (request === schemaRequest) {
      toolContracts.value = result
      schemaStale.value = false
      if (manual) {
        if (Object.keys(result).length < declarations.length)
          message.warning('工具参数已刷新；部分声明没有匹配的已发布配置。')
        else message.success('工具参数已刷新')
      }
    }
  } catch (reason) {
    if (reason instanceof HttpClientError) reason.claimFeedback()
    if (manual && request === schemaRequest) {
      schemaStale.value = true
      message.error('工具参数读取失败，请重试；当前编辑内容已保留。')
    }
    /* 无权读取或未发布时保留原始参数编辑，不编造 Schema。 */
  } finally {
    if (request === schemaRequest) schemaLoading.value = false
  }
}
watch(
  () => [meta.toolsText, meta.capabilityRequirementsText],
  () => {
    void refreshToolContracts()
  },
  { immediate: true }
)
const installOpen = ref(false),
  installSlots = ref<InstallSlot[]>([]),
  installVersions = ref<CapabilityVersion[]>([]),
  installDefinitions = ref<CapabilityDefinition[]>([]),
  deployments = ref<CapabilityDeployment[]>([]),
  customPrompt = ref(''),
  installConfigFields = ref<WorkflowConfigField[]>([]),
  installConfig = ref<Record<string, string | number | boolean | undefined>>({}),
  installReceipt = ref<{ instanceId: string; installationRevision: string }>(),
  installUncertain = ref(''),
  installStorageError = ref(false),
  knowledgeRevisions = ref<Record<string, number>>({})
let installDisposed = false
onScopeDispose(() => {
  installDisposed = true
})
const installStorageKey = (owner: string, id: string) =>
  `noewise:workflow-install:${JSON.stringify([owner, id])}`
watch(
  [() => route.params.workflowId, installOwner],
  ([value, owner]) => {
    installUncertain.value = ''
    installStorageError.value = false
    installReceipt.value = undefined
    const id = typeof value === 'string' ? value : ''
    if (!id) return
    if (!owner) {
      installStorageError.value = true
      return
    }
    try {
      const pending = sessionStorage.getItem(installStorageKey(owner, id))
      if (
        pending &&
        !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(pending)
      )
        throw Error('invalid')
      installUncertain.value = pending ?? ''
    } catch {
      installStorageError.value = true
    }
    if (installUncertain.value && auth.user?.permissions.includes('foundation.workflows.read'))
      void checkInstallation(true)
  },
  { immediate: true }
)
const installBaseline = ref('')
const installationSnapshot = () =>
  JSON.stringify([installSlots.value, installConfig.value, customPrompt.value])
function candidateVersions(slot: InstallSlot) {
  return installVersions.value.filter((version) => {
    const definition = installDefinitions.value.find((d) => d.id === version.definitionId)
    return (
      definition?.capabilityType === slot.type.toUpperCase() &&
      definition.capabilityKey === slot.capability_id &&
      definition.defaultVersionId === version.id &&
      matchesWorkflowVersion(slot.version_constraint, version.versionKey)
    )
  })
}
const { requestClose: closeInstall } = useDrawerClose({
  open: () => installOpen.value,
  busy: () => busy.value,
  dirty: () => installOpen.value && installationSnapshot() !== installBaseline.value,
  close: () => {
    installOpen.value = false
  }
})
async function readInstallDependencies(slots: InstallSlot[]) {
  const definitions = slots.length
    ? (await api.capabilities.definitions(undefined, 'PUBLISHED')).filter(
        (definition) =>
          definition.defaultVersionId &&
          slots.some(
            (slot) =>
              definition.capabilityType === slot.type.toUpperCase() &&
              definition.capabilityKey === slot.capability_id
          )
      )
    : []
  const versions = (
    await Promise.all(
      definitions.map((definition) => api.capabilities.versions(definition.id, 'PUBLISHED'))
    )
  ).flat()
  const toolDeployments = slots.some((slot) => slot.type === 'tool')
    ? await api.capabilities.deployments('ACTIVE')
    : []
  const revisions: Record<string, number> = {}
  const sources = [
    ...new Set(
      versions
        .filter((v) => {
          const definition = definitions.find((d) => d.id === v.definitionId)
          return (
            v.capabilityType === 'KNOWLEDGE_SOURCE' &&
            slots.some(
              (slot) =>
                definition?.capabilityType === slot.type.toUpperCase() &&
                definition.capabilityKey === slot.capability_id &&
                definition.defaultVersionId === v.id &&
                matchesWorkflowVersion(slot.version_constraint, v.versionKey)
            )
          )
        })
        .map((v) => String(v.descriptor.source_id ?? ''))
        .filter(Boolean)
    )
  ]
  for (const source of sources) {
    const response = await api.knowledge.listRevisions(source)
    for (const revision of response.items)
      if (
        revision.status === 'published' &&
        revision.capability_version_id !== null &&
        revision.capability_version_id !== undefined
      )
        revisions[String(revision.capability_version_id)] = Math.max(
          revisions[String(revision.capability_version_id)] ?? 0,
          revision.revision
        )
  }
  return { definitions, versions, toolDeployments, revisions }
}
function acceptInstallDependencies(value: Awaited<ReturnType<typeof readInstallDependencies>>) {
  installDefinitions.value = value.definitions
  installVersions.value = value.versions
  deployments.value = value.toolDeployments
  knowledgeRevisions.value = value.revisions
  installSlots.value = defaultInstallationSlots(
    installSlots.value,
    candidateVersions,
    deployments.value
  )
}
async function refreshInstallDependencies() {
  const owner = getSessionId(),
    id = workflowId.value
  await run(async () => {
    const value = await readInstallDependencies(installSlots.value)
    if (owner !== getSessionId() || id !== workflowId.value || !installOpen.value) return
    acceptInstallDependencies(value)
  })
}
async function openInstall() {
  if (installUncertain.value) {
    installOpen.value = true
    return
  }
  await run(async () => {
    const current = (await api.workflowDrafts.history(workflowId.value)).find((v) => v.current)
    if (!current) throw Error('请先发布工作流')
    const publication = await api.workflowDrafts.publicationDetail(
      workflowId.value,
      current.definitionVersionId
    )
    const raw = publication.definition.meta as
      { capability_requirements?: InstallSlot[]; config_schema?: WorkflowConfigField[] } | undefined
    installSlots.value = (raw?.capability_requirements ?? []).map((s) => ({
      ...s,
      label: s.label ?? s.slot
    }))
    installConfigFields.value = workflowConfigFields(JSON.stringify(raw?.config_schema ?? []))
    installConfig.value = Object.fromEntries(
      installConfigFields.value
        .filter((f) => f.default !== undefined)
        .map((f) => [f.key, f.default])
    )
    acceptInstallDependencies(await readInstallDependencies(installSlots.value))
    customPrompt.value = ''
    installBaseline.value = installationSnapshot()
    installOpen.value = true
  })
}
async function install() {
  if (installUncertain.value || installStorageError.value) return
  await run(async () => {
    const capabilities = installSlots.value
      .filter((s) => s.required !== false || s.versionId)
      .map((s) => {
        const version = candidateVersions(s).find((v) => v.id === s.versionId)
        if (!version) throw Error('请选择 ' + s.label + ' 的已发布能力')
        const dep = deployments.value.find(
          (d) =>
            d.deploymentKey === s.deploymentKey &&
            d.toolVersionId === version.id &&
            ['MCP', 'PLATFORM'].includes(d.providerType)
        )
        if (s.type === 'tool' && !dep) throw Error('请选择工具部署')
        const knowledgeRevision = knowledgeRevisions.value[version.id]
        if (s.type === 'knowledge_source' && !knowledgeRevision)
          throw Error('所选知识能力没有匹配的已发布修订')
        return {
          slot: s.slot,
          capability: {
            type: s.type.toUpperCase() as 'TOOL' | 'KNOWLEDGE_SOURCE',
            capabilityId: String(version.definitionId),
            versionId: String(version.id)
          },
          ...(s.type === 'tool' && dep
            ? { tool_deployment_id: dep.deploymentKey, tool_deployment_revision: dep.revision }
            : {}),
          ...(s.type === 'knowledge_source' ? { knowledge_revision: knowledgeRevision } : {})
        }
      })
    const config = workflowInstallationConfig(installConfigFields.value, installConfig.value)
    const attempt = crypto.randomUUID()
    const owner = installOwner.value,
      session = getSessionId(),
      id = workflowId.value
    if (!owner || !session || !id) {
      installStorageError.value = true
      return
    }
    try {
      sessionStorage.setItem(installStorageKey(owner, id), attempt)
    } catch {
      installStorageError.value = true
      return
    }
    installUncertain.value = attempt
    try {
      const result = await api.workflowDrafts.installWithReceipt(
        attempt,
        id,
        { capabilities, workflows: [], agents: [] },
        config,
        customPrompt.value
      )
      acceptInstallation(result, owner, session, id, attempt)
    } catch (caught) {
      if (
        installDisposed ||
        owner !== installOwner.value ||
        session !== getSessionId() ||
        route.params.workflowId !== id
      ) {
        if (caught instanceof HttpClientError) caught.claimFeedback()
        return
      }
      if (
        caught instanceof HttpClientError &&
        caught.code === 'WORKFLOW_INSTALL_REJECTED' &&
        installUncertain.value === attempt
      ) {
        try {
          sessionStorage.removeItem(installStorageKey(owner, id))
          installUncertain.value = ''
        } catch {
          installStorageError.value = true
        }
      }
      throw caught
    }
  })
}
function acceptInstallation(
  receipt: WorkflowInstallReceipt | undefined,
  owner: string,
  session: string,
  id: string,
  requestId: string
) {
  if (
    installDisposed ||
    getSessionId() !== session ||
    installOwner.value !== owner ||
    route.params.workflowId !== id ||
    installUncertain.value !== requestId
  )
    return
  if (
    !receipt ||
    receipt.request_id !== requestId ||
    receipt.workflow_id !== id ||
    !receipt.instance_id ||
    !/^[1-9]\d*$/.test(receipt.installation_revision)
  )
    return
  try {
    sessionStorage.removeItem(installStorageKey(owner, id))
  } catch {
    installStorageError.value = true
    return
  }
  installUncertain.value = ''
  installReceipt.value = {
    instanceId: receipt.instance_id,
    installationRevision: receipt.installation_revision
  }
  installOpen.value = false
  message.success('工作流已安装')
}
async function checkInstallation(automatic = false) {
  const owner = installOwner.value,
    session = getSessionId(),
    id = String(route.params.workflowId ?? ''),
    requestId = installUncertain.value
  if (
    !owner ||
    !session ||
    !id ||
    !requestId ||
    !auth.user?.permissions.includes('foundation.workflows.read')
  )
    return
  await run(async () => {
    const result = await api.workflowDrafts.installReceipt(id, requestId)
    acceptInstallation(result, owner, session, id, requestId)
    if (
      !automatic &&
      installUncertain.value === requestId &&
      !installDisposed &&
      getSessionId() === session &&
      installOwner.value === owner &&
      route.params.workflowId === id
    )
      message.warning('尚未查到匹配的原安装回执，结果仍待核对')
  })
}

const formTipId = useId()
</script>
<style scoped>
.workflow-editor {
  height: 100%;
  display: flex;
  flex-direction: column;
  min-height: 0;
}
.workflow-editor :deep(.admin-page__header) {
  align-items: center;
  gap: 12px;
  margin-bottom: 10px;
}
.workflow-editor :deep(.admin-page__header h1) {
  margin: 0;
  font-size: 20px;
  overflow-wrap: anywhere;
}
.workflow-editor :deep(.admin-page__body) {
  flex: 1;
  min-height: 0;
  gap: 8px;
}
.workflow-tabs {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
}
.workflow-tabs :deep(.el-tabs__header) {
  flex: none;
  margin-bottom: 8px;
}
.workflow-tabs :deep(.el-tabs__content) {
  flex: 1;
  min-height: 0;
  overflow: auto;
}
.workflow-editor.is-design .workflow-tabs :deep(.el-tabs__content) {
  overflow: visible;
}
.workflow-tabs :deep(#pane-design) {
  height: 100%;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.workflow-design-tools {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}
.workflow-design-tools .el-button + .el-button {
  margin-left: 0;
}
.workflow-mobile-actions {
  display: none;
}
.workflow-design-tools .workflow-node-type {
  width: 150px;
}
.workflow-design-tools .workflow-node-search {
  width: 200px;
  flex: 1;
  min-width: 160px;
  max-width: 300px;
}
.workflow-workspace {
  flex: 1;
  min-height: 360px;
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  gap: 12px;
}
.workflow-workspace.with-properties {
  grid-template-columns: minmax(0, 1fr) 360px;
}
.workflow-properties {
  min-height: 0;
  min-width: 0;
  display: flex;
  flex-direction: column;
  border: 1px solid var(--el-border-color);
  border-radius: 10px;
  background: var(--el-bg-color);
}
.workflow-properties-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 10px 12px;
  border-bottom: 1px solid var(--el-border-color);
}
.workflow-properties-heading h3 {
  margin: 0;
}
.workflow-properties-body {
  flex: 1;
  min-height: 0;
  overflow: auto;
  padding: 12px;
}
/* 专注模式只改变本页所在后台的布局，退出或离开页面即恢复导航。 */
:global(.admin-layout:has(.workflow-editor.is-focused)) {
  grid-template-columns: minmax(0, 1fr);
}
:global(.admin-layout:has(.workflow-editor.is-focused) .admin-sidebar),
:global(.admin-layout:has(.workflow-editor.is-focused) .admin-topbar) {
  display: none;
}
:global(.admin-layout:has(.workflow-editor.is-focused) .admin-content) {
  padding: 12px;
}
.workflow-problem {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  padding: 14px 0;
  border-bottom: 1px solid var(--el-border-color);
}
.workflow-problem > div {
  flex: 1;
  min-width: 0;
  overflow-wrap: anywhere;
}
.workflow-problem p {
  margin: 5px 0 0;
}
.workflow-status {
  padding: 0;
  margin: 0;
}
@media (max-width: 900px) {
  .workflow-editor :deep(.admin-page__header) {
    align-items: stretch;
  }
  .workflow-workspace.with-properties {
    grid-template-columns: minmax(0, 1fr);
  }
  .workflow-workspace.with-properties :deep(.workflow-canvas-shell) {
    display: none;
  }
}
@media (max-width: 600px) {
  .workflow-layout-action {
    display: none;
  }
  .workflow-mobile-actions {
    display: flex;
    margin-bottom: 12px;
  }
}
</style>
