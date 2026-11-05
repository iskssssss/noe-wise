<template>
  <AdminPage
    :title="agent?.name || '智能体配置'"
    description="配置提示词、模型、运行预算与可调用能力。"
    ><template #extra
      ><ElButton
        v-if="can('foundation.agents.read') && can('foundation.workflows.read')"
        :disabled="busy"
        @click="workflowReceiptOpen = true"
        >核对工作流接入</ElButton
      ><ElButton
        v-if="loadError || refreshRequired"
        :loading="loading"
        :disabled="busy || !!publicationOperation"
        @click="refreshAgent"
        >重新读取</ElButton
      ><ElButton
        v-permission="['foundation.sessions.read']"
        :disabled="editingBlocked || refreshRequired || agent?.status !== 'published'"
        @click="router.push({ path: '/admin/chat', query: { agent_id: id } })"
        >试用当前发布版本</ElButton
      ><ElButton
        v-permission="['foundation.agents.publish']"
        :disabled="
          editingBlocked ||
          refreshRequired ||
          !agent ||
          (dirty ? !canSaveAndPublish : !agent.has_draft_changes)
        "
        :loading="busy"
        :type="canSaveAndPublish ? 'primary' : undefined"
        @click="dirty ? saveAndPublish() : publish()"
        >{{ dirty && canSaveAndPublish ? '保存并发布' : '发布' }}</ElButton
      ><ElButton
        v-if="canPublishAndTrial"
        :disabled="editingBlocked || refreshRequired || !agent || !dirty"
        :loading="busy"
        @click="saveAndPublish(true)"
        >保存发布并试用</ElButton
      ><ElButton
        v-permission="['foundation.agents.update']"
        :type="canSaveAndPublish ? undefined : 'primary'"
        :loading="busy"
        :disabled="editingBlocked || !agent || refreshRequired"
        @click="save"
        >保存草稿</ElButton
      ></template
    >
    <div v-if="agent" class="admin-toolbar agent-overview" role="status">
      <ElTag :type="dirty || agent.has_draft_changes ? 'warning' : 'success'">{{
        dirty ? '有未保存修改' : agent.has_draft_changes ? '草稿待发布' : '已与发布同步'
      }}</ElTag>
      <span>发布修订 {{ agent.published_revision ?? '尚未发布' }}</span>
      <span v-if="agent.status !== 'published'">发布后可试用</span>
      <span v-else-if="dirty || agent.has_draft_changes"
        >试用仅使用当前发布版本，不包含草稿修改</span
      >
      <span>{{ agent.mounts.length }} 项能力</span>
      <span>{{ form.model_config_id ? '使用指定模型' : '继承机构默认模型' }}</span>
    </div>
    <ElAlert
      v-if="refreshRequired"
      :title="
        skillBudgetNeedsRefresh
          ? '技能预算更新结果尚未确认，请重新读取并核对最新状态后继续。'
          : '操作已完成，当前显示内容尚未更新。请点击“重新读取”核对最新状态后继续配置或发布，避免重复提交。'
      "
      type="warning"
      :closable="false"
    />
    <section
      v-if="publicationOperation"
      class="admin-card"
      :aria-label="publicationOperation.kind === 'publish' ? '发布结果核对' : '保存并发布结果核对'"
    >
      <p role="status">
        {{
          publicationOperation.kind === 'publish' ? '发布' : '保存并发布'
        }}结果尚未确认，当前配置已锁定。请求号：<code>{{ publicationOperation.requestId }}</code>
      </p>
      <p class="admin-field-help">
        核对只读取原操作回执。刷新后仅保留请求号，不保存配置正文；原结果未确认前不能再次发布。
        <template v-if="publicationOperation.command"
          >继续原操作使用相同请求号和内容，不会另建发布。</template
        >
      </p>
      <p v-if="publicationCheck" role="status">{{ publicationCheck }}</p>
      <ElButton :disabled="busy" @click="checkPublication">核对原操作</ElButton>
      <ElButton
        v-if="
          publicationOperation.command &&
          (publicationOperation.kind === 'publish'
            ? can('foundation.agents.publish')
            : canSaveAndPublish)
        "
        :disabled="busy"
        @click="continuePublication"
        >继续原操作</ElButton
      >
    </section>
    <p v-if="publicationStorageError" role="status">{{ publicationStorageError }}</p>
    <p v-if="publicationReceipt" class="admin-field-help" role="status">
      {{ publicationReceiptKind === 'publish' ? '发布' : '保存并发布' }}已确认：修订
      {{ publicationReceipt.revision }} · 发布记录 {{ publicationReceipt.publication_id }} · 请求号
      {{ publicationReceipt.request_id }}
    </p>
    <p class="admin-muted">
      保存更新草稿。发布后已有会话下一轮聊天采用新配置，正在执行的任务继续原配置。
    </p>
    <FeedbackNotice
      v-if="pageError"
      :title="pageError"
      :closable="false"
      :type="publicationReceipt && loadError && !error ? 'warning' : 'error'"
    />
    <ElButton
      v-if="modelOptions.error.value"
      :disabled="busy || modelOptions.loading.value"
      @click="loadModels"
    >
      重新读取模型选项 </ElButton
    ><ElButton
      v-if="mountNames.error.value"
      :disabled="mountNames.loading.value"
      @click="loadMountNames"
    >
      重新读取能力名称 </ElButton
    ><ElSkeleton v-if="loading" :rows="8" animated /><template v-else-if="agent"
      ><ElTabs v-model="activeTab" @tab-change="loadPublications"
        ><ElTabPane label="基本配置" name="config"
          ><section class="admin-card">
            <h3>身份与工作方式</h3>
            <ElForm
              ref="formRef"
              :model="form"
              label-position="top"
              @submit.prevent="save"
              :disabled="editingBlocked"
              @keydown.capture="guardFormEnter"
              ><AgentConfigFields
                allow-default-model
                :disabled="editingBlocked"
                :model-value="form"
                :models="models"
                @update:model-value="Object.assign(form, $event)"
              />
              <ElButton
                v-if="!modelOptions.error.value"
                :loading="modelOptions.loading.value"
                :disabled="busy"
                @click="loadModels"
                >刷新模型选项</ElButton
              >
              <p
                v-if="
                  form.model_config_id &&
                  modelOptions.data.value &&
                  !modelOptions.loading.value &&
                  !modelOptions.error.value &&
                  !models.some((model) => model.id === form.model_config_id)
                "
                class="admin-field-help"
                role="status"
              >
                当前指定的模型不在可选目录中，可能已停用或不在当前授权范围；原选择已保留，请核对后重新选择。
              </p>
              <p
                v-if="!form.model_config_id && can('foundation.models.read')"
                class="admin-field-help"
              >
                <RouterLink to="/admin/settings" target="_blank" rel="noopener noreferrer"
                  >查看机构默认模型设置（新标签页）</RouterLink
                >
              </p>
              <ElFormItem label="角色描述"
                ><ElInput
                  v-described-by="formTipId + '-field-0'"
                  :aria-describedby="formTipId + '-field-0'"
                  v-model="form.role_description"
                  type="textarea"
                />
                <FieldTip :id="formTipId + '-field-0'"
                  >说明智能体在协作和团队中的职责，便于选择合适成员。</FieldTip
                > </ElFormItem
              ><ElFormItem label="自动提取记忆"
                ><ElSwitch
                  v-described-by="formTipId + '-field-1'"
                  :aria-describedby="formTipId + '-field-1'"
                  v-model="form.memory"
                />
                <FieldTip :id="formTipId + '-field-1'"
                  >启用后允许从交互中自动提取记忆；提取结果仍受记忆范围和治理规则约束。</FieldTip
                >
              </ElFormItem></ElForm
            >
          </section></ElTabPane
        ><ElTabPane :label="'能力挂载（' + agent.mounts.length + '）'" name="capabilities"
          ><div class="admin-toolbar">
            <ElButton
              type="primary"
              :disabled="editingBlocked || refreshRequired"
              @click="openMount"
              >添加能力</ElButton
            >
            <span class="admin-muted"
              >按任务需要挂载技能、工具、工作流或知识库；修改随智能体发布生效。</span
            >
          </div>
          <ElTable
            table-layout="auto"
            :data="agent.mounts"
            v-loading="false"
            row-key="mountId"
            stripe
            empty-text="尚未挂载能力，添加智能体完成任务需要的工具或知识"
            ><ElTableColumn min-width="140" show-overflow-tooltip label="类型"
              ><template #default="{ row }">{{
                statusLabel(row.capabilityType)
              }}</template></ElTableColumn
            ><ElTableColumn min-width="200" show-overflow-tooltip label="能力（跟随当前发布）"
              ><template #default="{ row }"
                ><div>{{ mountVersionNames[row.capabilityVersionId] || '版本信息不可用' }}</div>
                <small class="muted">资源标识 {{ row.capabilityDefinitionId }}</small>
                <div v-if="row.capabilityType === 'SKILL'" class="admin-field-help">
                  Token 预算：{{ row.skillConfig.tokenBudget }}
                </div></template
              ></ElTableColumn
            ><ElTableColumn width="96" prop="priority" label="优先级"></ElTableColumn
            ><ElTableColumn width="128" show-overflow-tooltip label="状态"
              ><template #default="{ row }">{{ statusLabel(row.status) }}</template></ElTableColumn
            ><ElTableColumn width="150" class-name="admin-table-actions" prop="actions" label="操作"
              ><template #default="{ row }"
                ><ElButton
                  v-if="row.capabilityType === 'SKILL'"
                  v-permission="['foundation.agents.update']"
                  link
                  :disabled="editingBlocked || refreshRequired"
                  @click="openSkillBudget(row as CapabilityMount)"
                  >调整预算</ElButton
                ><ElButton
                  v-permission="['foundation.agents.delete']"
                  type="danger"
                  link
                  :disabled="editingBlocked"
                  @click="removeMount(row as CapabilityMount)"
                  >移除</ElButton
                ></template
              ></ElTableColumn
            ></ElTable
          ></ElTabPane
        ><ElTabPane label="运行与协作" name="runtime"
          ><section class="admin-card">
            <h3>运行预算</h3>
            <p class="admin-muted">通常保持继承即可；有明确资源限制时再逐项覆盖。</p>
            <ElCollapse
              ><ElCollapseItem title="自定义运行预算" name="budget">
                <ElAlert
                  title="留空继承场景与角色默认额度，任务受理时冻结；已受理运行继续使用原额度。"
                  type="info"
                  :closable="false"
                /><ElForm
                  class="admin-form-grid"
                  label-position="top"
                  @submit.prevent
                  :disabled="editingBlocked"
                  scroll-to-error
                  @keydown.capture="guardFormEnter"
                  ><ElFormItem v-for="field in budgets" :key="field.key" :label="field.label"
                    ><ElInputNumber
                      v-described-by="
                        formTipId + '-field-2' + '-' + encodeURIComponent(String(field.key))
                      "
                      :aria-describedby="
                        formTipId + '-field-2' + '-' + encodeURIComponent(String(field.key))
                      "
                      v-model="form.execution_config[field.key]"
                      :min="1"
                      :precision="0"
                      clearable
                    />
                    <FieldTip
                      :id="formTipId + '-field-2' + '-' + encodeURIComponent(String(field.key))"
                      >{{ budgetOverrideHelp(field.key) }}</FieldTip
                    >
                  </ElFormItem></ElForm
                ></ElCollapseItem
              ></ElCollapse
            >
          </section>
          <section class="admin-card">
            <h3>任务委派</h3>
            <ElForm label-position="top" :disabled="editingBlocked" @submit.prevent
              ><ElSwitch
                v-described-by="formTipId + '-group-0'"
                :aria-describedby="formTipId + '-group-0'"
                v-model="form.delegation_config.enabled"
                active-text="允许委派"
                :disabled="editingBlocked"
              /><ElFormItem v-if="form.delegation_config.enabled" label="允许接收任务的智能体"
                ><ElSelect
                  v-described-by="formTipId + '-group-0'"
                  :aria-describedby="formTipId + '-group-0'"
                  v-model="form.delegation_config.target_agent_ids"
                  multiple
                  filterable
                  :disabled="editingBlocked"
                  placeholder="选择允许委派的智能体"
                  ><ElOption
                    v-for="other in otherAgents"
                    :key="other.id"
                    :label="other.name"
                    :value="other.id" /></ElSelect
                ><ElButton
                  v-if="can('foundation.agents.read')"
                  :loading="delegationOptions.loading.value"
                  :disabled="delegationOptions.loading.value"
                  @click="loadDelegationAgents"
                  >刷新委派选项</ElButton
                ></ElFormItem
              >
              <FieldTip :id="formTipId + '-group-0'"
                >启用委派后选择允许接收任务的智能体；授权范围会随 Agent
                发布，委派任务共享任务根预算。</FieldTip
              >
            </ElForm>
          </section></ElTabPane
        ><ElTabPane label="发布历史" name="history">
          <ElButton :disabled="editingBlocked" @click="loadPublications('history')"
            >刷新历史</ElButton
          >
          <ElTable :data="publications" row-key="revision_id" empty-text="尚无发布记录">
            <ElTableColumn prop="revision" label="发布修订" width="110" />
            <ElTableColumn prop="name" label="名称" min-width="160" show-overflow-tooltip />
            <ElTableColumn prop="published_at" label="发布时间" min-width="190"
              ><template #default="{ row }">{{
                formatDateTime(row.published_at)
              }}</template></ElTableColumn
            >
            <ElTableColumn label="生效状态" width="120"
              ><template #default="{ row }">{{
                row.current ? '当前发布' : '历史发布'
              }}</template></ElTableColumn
            >
            <ElTableColumn label="操作" width="190"
              ><template #default="{ row }">
                <ElButton
                  link
                  :disabled="editingBlocked"
                  @click="viewPublication(row as AgentPublication)"
                  >查看配置</ElButton
                >
                <ElButton
                  v-permission="['foundation.agents.update']"
                  link
                  :disabled="editingBlocked || dirty"
                  @click="restorePublication(row as AgentPublication)"
                  >恢复为草稿</ElButton
                >
              </template></ElTableColumn
            >
          </ElTable>
        </ElTabPane>
      </ElTabs></template
    >
    <ElDrawer
      v-model="mountOpen"
      :before-close="requestCloseMount"
      title="添加能力挂载"
      size="680px"
      append-to-body
      direction="rtl"
      :close-on-click-modal="false"
      :close-on-press-escape="!busy"
      :show-close="!busy"
      class="admin-drawer"
      ><p v-if="mountOptionsState === 'loading'" role="status">正在读取当前能力选项…</p>
      <ElButton
        v-if="mountOptionsState === 'failed'"
        :disabled="editingBlocked || onboardingLocked"
        @click="loadVersions"
      >
        重试当前能力选项
      </ElButton>
      <ElForm
        :disabled="editingBlocked || onboardingLocked || onboardingOpen || workflowOnboardingOpen"
        label-position="top"
        @submit.prevent
        scroll-to-error
        @keydown.capture="guardFormEnter"
        ><ElFormItem label="能力类型"
          ><ElSelect
            v-described-by="formTipId + '-field-3'"
            :aria-describedby="formTipId + '-field-3'"
            v-model="mountType"
            @change="loadVersions"
            ><ElOption
              v-for="type in ['SKILL', 'TOOL', 'WORKFLOW', 'KNOWLEDGE_SOURCE']"
              :key="type"
              :label="statusLabel(type)"
              :value="type"
          /></ElSelect>
          <FieldTip :id="formTipId + '-field-3'">选择需要挂载的能力类别。</FieldTip> </ElFormItem
        ><template v-if="mountType === 'TOOL'">
          <ElFormItem label="可用工具" v-if="!advancedToolMount">
            <ElSelect
              v-model="definitionId"
              @change="autoToolId = undefined"
              @visible-change="
                (open: boolean) => {
                  if (open) autoToolId = undefined
                }
              "
              filterable
              :filter-method="(query: string) => (toolSearch = query)"
              aria-label="可用工具"
            >
              <ElOption
                v-for="tool in filteredAvailableTools"
                class="agent-capability-option"
                :key="tool.definition_id"
                :value="tool.definition_id"
                :label="tool.name"
              >
                <div>{{ tool.name }}</div>
                <div class="admin-field-help">{{ tool.description }}</div>
                <div class="admin-field-help">
                  {{ tool.deployments.map((item) => item.provider_label).join('、') }}
                </div>
              </ElOption>
            </ElSelect>
            <p class="admin-field-help">
              选择工具即可使用其服务绑定。只有多个可用实例时才需要选择服务。
            </p>
          </ElFormItem>
          <ElFormItem
            v-if="
              !advancedToolMount &&
              selectedAvailableTool &&
              (selectedAvailableTool.deployments.length > 1 ||
                (selectedAvailableTool.deployments.length === 1 && !deploymentKey))
            "
            label="服务实例"
          >
            <ElSelect v-model="deploymentKey" aria-label="服务实例">
              <ElOption
                v-for="item in selectedAvailableTool.deployments"
                :key="item.deployment_key"
                :value="item.deployment_key"
                :label="item.provider_label + ' · ' + statusLabel(item.environment)"
              />
            </ElSelect>
          </ElFormItem>
          <p v-if="definitionId && !selectedAvailableTool" class="admin-field-help" role="status">
            所选工具已不在可用目录中，请重新选择工具。
          </p>
          <p
            v-else-if="selectedAvailableTool && !selectedAvailableTool.deployments.length"
            class="admin-field-help"
            role="status"
          >
            所选工具当前没有可用服务实例，请刷新目录或接入服务。
          </p>
          <p
            v-if="
              mountOptionsState === 'ready' &&
              (advancedToolMount
                ? definitions.length && !mountableDefinitions.length
                : availableTools.length && !mountableTools.length)
            "
            class="admin-field-help"
            role="status"
          >
            可用工具均已挂载，可在能力列表中查看。
          </p>
          <ElCheckbox v-model="advancedToolMount" @change="loadVersions"
            >高级：按能力与部署选择</ElCheckbox
          >
        </template>
        <ElFormItem
          v-if="mountType !== 'KNOWLEDGE_SOURCE' && (mountType !== 'TOOL' || advancedToolMount)"
          label="能力"
          ><ElSelect
            v-described-by="formTipId + '-field-4'"
            :aria-describedby="formTipId + '-field-4'"
            v-model="definitionId"
            filterable
            :filter-method="(query: string) => (definitionSearch = query)"
            ><ElOption
              v-for="definition in filteredDefinitions"
              class="agent-capability-option"
              :key="definition.id"
              :label="definition.name"
              :value="definition.id"
              ><div>{{ definition.name }}</div>
              <div class="admin-field-help">{{ definition.description }}</div>
              <div class="admin-field-help">{{ definition.capabilityKey }}</div></ElOption
            ></ElSelect
          >
          <FieldTip :id="formTipId + '-field-4'"
            >按名称、用途或标识查找已发布能力；新执行跟随当前发布版本。</FieldTip
          >
        </ElFormItem>
        <p
          v-if="mountType === 'SKILL' && mountOptionsState === 'ready' && !definitions.length"
          class="admin-field-help"
          role="status"
        >
          当前没有可选的已发布技能。
          <RouterLink
            v-if="can('foundation.skills.read')"
            to="/admin/skills"
            target="_blank"
            rel="noopener noreferrer"
            >前往技能工作台（新标签页）</RouterLink
          ><span v-else>请联系管理员创建并发布技能。</span>
        </p>
        <p
          v-if="
            mountType === 'SKILL' &&
            mountOptionsState === 'ready' &&
            definitions.length &&
            !mountableDefinitions.length
          "
          class="admin-field-help"
          role="status"
        >
          可用技能均已挂载，可在能力列表中查看。
        </p>
        <p v-if="mountType === 'SKILL' && mountOptionsState === 'ready'" class="admin-field-help">
          <RouterLink
            v-if="can('foundation.skills.read') && definitions.length"
            to="/admin/skills"
            target="_blank"
            rel="noopener noreferrer"
            >管理技能（新标签页）</RouterLink
          >
          <ElButton link :disabled="busy" @click="refreshSkills">刷新技能选项</ElButton>
          <span v-if="definitionId && !selectedDefinition"
            >所选技能已不在可用目录中，请重新选择。</span
          >
        </p>
        <p v-if="skillSelectionStale" role="status" class="admin-field-help">
          <template v-if="skillCatalogNeedsRefresh"
            >技能目录需要重新核对，请刷新技能选项。</template
          >
          <template v-else>
            所选技能的当前发布版本已变化，请确认后再添加。
            <ElButton link :disabled="busy" @click="confirmCurrentSkill">确认当前发布版本</ElButton>
          </template>
        </p>
        <p
          v-if="
            mountType === 'WORKFLOW' &&
            mountOptionsState === 'ready' &&
            !mountableDefinitions.length
          "
          class="admin-field-help"
          role="status"
        >
          {{
            definitions.length
              ? '可用工作流均已挂载，可在能力列表中查看。'
              : `当前没有可挂载的已安装工作流。${canOnboardWorkflow ? '请先接入工作流。' : '请联系管理员接入工作流。'}`
          }}
        </p>
        <ElCollapse
          ><ElCollapseItem title="挂载优先级（默认 0）" name="priority"
            ><ElFormItem label="优先级"
              ><ElInputNumber
                v-described-by="formTipId + '-field-5'"
                :aria-describedby="formTipId + '-field-5'"
                v-model="priority"
                :precision="0"
              />
              <FieldTip :id="formTipId + '-field-5'"
                >用于能力装配的优先级；数值应符合当前装配规则。</FieldTip
              >
            </ElFormItem></ElCollapseItem
          ></ElCollapse
        ><template v-if="mountType === 'SKILL'"
          ><p class="admin-field-help">激活方式由技能的当前发布版本决定。</p>
          <ElFormItem label="Token 预算" required :error="tokenBudgetError"
            ><ElInputNumber
              v-described-by="'mount-token-help'"
              aria-describedby="mount-token-help"
              v-model="tokenBudget"
              :min="1"
              :precision="0"
            />
            <p id="mount-token-help" class="admin-field-help">
              必须填写正整数，限制每次加载技能入口或资源的估算大小，不是供应商实际用量。
              <template v-if="selectedSkillEstimatedTokens != null">
                技能入口估算 {{ selectedSkillEstimatedTokens }} Token。
              </template>
            </p>
            <p
              v-if="
                selectedSkillEstimatedTokens != null && tokenBudget < selectedSkillEstimatedTokens
              "
              class="admin-field-help"
              role="status"
            >
              当前预算不足，不会加载该技能入口，ALWAYS 技能也会跳过；请提高预算后再使用。
            </p></ElFormItem
          ></template
        ><ElFormItem v-if="mountType === 'TOOL' && advancedToolMount" label="工具部署"
          ><ElSelect
            v-described-by="formTipId + '-field-8'"
            :aria-describedby="formTipId + '-field-8'"
            v-model="deploymentKey"
            ><ElOption
              v-for="item in matchingDeployments"
              :key="item.deploymentKey"
              :value="item.deploymentKey"
              :label="item.deploymentKey"
          /></ElSelect>
          <FieldTip :id="formTipId + '-field-8'"
            >选择该工具的可用部署，部署决定实际提供方和运行环境。</FieldTip
          >
        </ElFormItem>
        <p
          v-if="mountType === 'WORKFLOW' && singleWorkflowInstance"
          class="admin-field-help"
          role="status"
        >
          已选用唯一安装实例：{{ singleWorkflowInstance.workflowId }} /
          {{ singleWorkflowInstance.instanceId }}
        </p>
        <ElFormItem v-else-if="mountType === 'WORKFLOW'" label="工作流安装实例"
          ><ElSelect
            v-described-by="formTipId + '-field-9'"
            :aria-describedby="formTipId + '-field-9'"
            v-model="workflowInstanceId"
            ><ElOption
              v-for="item in matchingInstances"
              :key="item.id"
              :value="item.id"
              :label="item.workflowId + ' / ' + item.instanceId"
          /></ElSelect>
          <FieldTip :id="formTipId + '-field-9'"
            >选择已安装的工作流实例，实例提供绑定和安装配置。</FieldTip
          > </ElFormItem
        ><template v-if="mountType === 'KNOWLEDGE_SOURCE'"
          ><ElFormItem label="知识库" required
            ><EntitySelect
              v-described-by="formTipId + '-field-10'"
              :aria-describedby="formTipId + '-field-10'"
              v-model="knowledgeBaseId"
              @update:model-value="knowledgeSelectedByUser"
              :options="filteredKnowledgeBases"
              :filter-method="(query: string) => (knowledgeSearch = query)"
              placeholder="选择知识库"
            />
            <FieldTip :id="formTipId + '-field-10'">按名称、用途或标识查找知识库。</FieldTip>
          </ElFormItem>
          <p
            v-if="autoKnowledgeId && knowledgeBaseId === autoKnowledgeId"
            class="admin-field-help"
            role="status"
          >
            已预选唯一活动知识库，可按需修改；添加仍需确认。
          </p>
          <p class="admin-field-help">绑定知识库身份，每轮聊天使用其当前发布修订。</p>
          <p
            v-if="mountOptionsState === 'ready' && !knowledgeBases.length"
            class="admin-field-help"
            role="status"
          >
            当前没有可选知识库。{{
              can('foundation.knowledge.create') && can('foundation.knowledge.read')
                ? '创建并发布后可返回刷新。'
                : '请联系管理员创建并发布知识库。'
            }}
            <RouterLink
              v-if="can('foundation.knowledge.create') && can('foundation.knowledge.read')"
              to="/admin/knowledge"
              target="_blank"
              rel="noopener noreferrer"
              >前往知识库（新标签页）</RouterLink
            >
            <ElButton link :disabled="busy" @click="refreshKnowledgeBases">刷新知识库选项</ElButton>
          </p>
          <p v-if="mountOptionsState === 'ready' && knowledgeBases.length" class="admin-field-help">
            <RouterLink
              v-if="can('foundation.knowledge.read')"
              to="/admin/knowledge"
              target="_blank"
              rel="noopener noreferrer"
              >前往知识库（新标签页）</RouterLink
            >
            <ElButton link :disabled="busy" @click="refreshKnowledgeBases">刷新知识库选项</ElButton>
          </p>
          <p
            v-if="
              knowledgeBaseId && activeKnowledgeRevisionId && (!versionId || !definitionId) && !busy
            "
            class="admin-field-help"
            role="status"
          >
            当前知识发布与能力目录不一致，请刷新知识库选项；仍不一致时联系管理员。
          </p>
          <p v-if="knowledgeAlreadyMounted" class="admin-field-help" role="status">
            该知识库已挂载，可在能力列表中查看。
          </p>
          <p v-if="knowledgeUnpublished" class="admin-field-help" role="status">
            该知识库尚未发布，请先发布知识后再添加。
            <RouterLink
              v-if="can('foundation.knowledge.read')"
              :to="'/admin/knowledge/' + knowledgeBaseId"
              target="_blank"
              rel="noopener noreferrer"
              >打开知识库（新标签页）</RouterLink
            >
            <ElButton link :disabled="busy" @click="refreshKnowledgeBases"
              >重新读取发布状态</ElButton
            >
          </p></template
        ></ElForm
      ><ElButton
        v-if="
          mountType === 'TOOL' &&
          !advancedToolMount &&
          mountOptionsState === 'ready' &&
          canOnboardTools
        "
        :disabled="editingBlocked || onboardingBusy"
        @click="toggleOnboarding"
        >{{ onboardingOpen ? '收起工具接入' : '接入可用工具' }}</ElButton
      ><ElButton
        v-if="mountType === 'TOOL' && !advancedToolMount && mountOptionsState === 'ready'"
        :disabled="editingBlocked || onboardingLocked"
        @click="refreshAvailableTools"
        >刷新可用工具</ElButton
      >
      <p v-if="toolsRefreshRequired" role="status">
        工具接入已确认，但挂载选项尚未刷新，请刷新可用工具，无需重复接入。
      </p>
      <ToolsPage
        v-if="onboardingOpen && mountType === 'TOOL' && !advancedToolMount"
        embedded
        @locked="onboardingLocked = $event"
        @unknown="onboardingUnknown = $event"
        @busy="onboardingBusy = $event"
        @confirmed="toolsConfirmed"
      />
      <ElButton
        v-if="mountType === 'WORKFLOW' && canOnboardWorkflow"
        :disabled="editingBlocked || onboardingBusy"
        @click="toggleWorkflowOnboarding"
      >
        {{ workflowOnboardingOpen ? '收起工作流接入' : '接入工作流' }}
      </ElButton>
      <p v-if="mountType === 'WORKFLOW' && workflowOptionsRefreshRequired" role="status">
        工作流接入已确认，智能体或挂载选项尚未刷新。
        <ElButton :disabled="busy || onboardingBusy" @click="refreshWorkflowOptions"
          >重新读取接入状态</ElButton
        >
      </p>
      <WorkflowOnboarding
        v-if="workflowOnboardingOpen && mountType === 'WORKFLOW'"
        ref="workflowOnboarding"
        :agent-id="id"
        :mounts="agent?.mounts ?? []"
        :can-create="can('foundation.agents.create')"
        :can-update="can('foundation.agents.update')"
        :can-install="can('foundation.workflows.create')"
        :refresh-agent="refreshWorkflowAgent"
        @locked="onboardingLocked = $event"
        @unknown="onboardingUnknown = $event"
        @busy="onboardingBusy = $event"
        @confirmed="workflowConfirmed"
      />
      <template #footer
        ><ElButton @click="requestCloseMount" :disabled="editingBlocked || onboardingBusy"
          >取消</ElButton
        ><ElButton
          v-permission="['foundation.agents.create']"
          type="primary"
          :loading="busy"
          :disabled="
            mountOptionsState !== 'ready' ||
            refreshRequired ||
            (mountType === 'WORKFLOW' && workflowOptionsRefreshRequired) ||
            onboardingLocked ||
            onboardingOpen ||
            workflowOnboardingOpen ||
            (mountType === 'WORKFLOW' && (!selectedDefinition || !workflowInstanceId)) ||
            (mountType === 'SKILL' && (!selectedDefinition || skillSelectionStale)) ||
            (mountType === 'TOOL' &&
              !(advancedToolMount ? selectedDefinition : selectedAvailableTool)) ||
            (mountType === 'KNOWLEDGE_SOURCE' &&
              (!knowledgeBaseId ||
                knowledgeUnpublished ||
                knowledgeAlreadyMounted ||
                !versionId ||
                !definitionId))
          "
          @click="addMount"
          >添加能力</ElButton
        ></template
      ></ElDrawer
    >
    <ElDrawer
      v-model="workflowReceiptOpen"
      title="核对工作流接入"
      size="680px"
      append-to-body
      :before-close="closeWorkflowReceipt"
      :close-on-click-modal="false"
    >
      <WorkflowOnboarding
        v-if="workflowReceiptOpen"
        receipt-only
        :agent-id="id"
        :mounts="agent?.mounts ?? []"
        :can-create="false"
        :can-update="false"
        :can-install="false"
        :refresh-agent="refreshWorkflowAgent"
        @unknown="workflowReceiptUnknown = $event"
        @busy="workflowReceiptBusy = $event"
        @confirmed="workflowConfirmed"
      />
    </ElDrawer>
    <ElDialog
      v-model="skillBudgetOpen"
      title="调整技能预算"
      :close-on-click-modal="false"
      :close-on-press-escape="!busy"
      :show-close="!busy"
    >
      <ElForm label-position="top" @submit.prevent="saveSkillBudget">
        <ElFormItem label="Token 预算" required :error="skillBudgetError">
          <ElInputNumber v-model="skillBudget" :min="1" :precision="0" />
          <p class="admin-field-help">
            限制每次加载技能入口或资源的估算大小。保存时使用技能当前发布版本，智能体发布后在后续运行生效。
          </p>
        </ElFormItem>
        <p v-if="skillBudgetNeedsRefresh" role="status">
          更新结果尚未确认，请先读取最新挂载并核对预算，再决定是否保存。
        </p>
      </ElForm>
      <template #footer>
        <ElButton v-if="skillBudgetNeedsRefresh" :loading="loading" @click="refreshSkillBudget">
          重新读取挂载
        </ElButton>
        <ElButton :disabled="busy" @click="skillBudgetOpen = false">取消</ElButton>
        <ElButton
          type="primary"
          :loading="busy"
          :disabled="skillBudgetNeedsRefresh"
          @click="saveSkillBudget"
        >
          保存预算
        </ElButton>
      </template>
    </ElDialog>
  </AdminPage>
  <ElDrawer
    v-model="publicationOpen"
    :title="'发布修订 ' + publicationDetail?.revision"
    size="760px"
    append-to-body
  >
    <template v-if="publicationDetail">
      <ElAlert
        title="历史发布配置仅供查看。恢复为草稿后需再次发布才能生效。"
        type="info"
        :closable="false"
      />
      <ElDescriptions :column="1" border>
        <ElDescriptionsItem label="名称">{{ publicationDetail.name }}</ElDescriptionsItem>
        <ElDescriptionsItem label="模型">{{
          publicationDetail.model_config_id || '继承机构默认模型'
        }}</ElDescriptionsItem>
        <ElDescriptionsItem label="发布时间">{{
          formatDateTime(publicationDetail.published_at)
        }}</ElDescriptionsItem>
      </ElDescriptions>
      <h3>系统提示词</h3>
      <pre class="readable">{{ publicationDetail.system_prompt }}</pre>
      <h3>完整配置</h3>
      <pre class="readable">{{ JSON.stringify(publicationDetail, null, 2) }}</pre>
    </template>
  </ElDrawer>
</template>
<script setup lang="ts">
import FeedbackNotice from '@skillforge/shared/feedback/FeedbackNotice.vue'
import { formatDateTime } from '@skillforge/shared/utils/datetime'
import { catalogDecimalIdentity } from '@skillforge/shared/integration/decimalIdentity'
import { vDescribedBy } from '@skillforge/shared/directives/describedBy'

import { budgetOverrideHelp } from '@skillforge/shared/utils/formHelp'
import FieldTip from '@skillforge/shared/components/FieldTip.vue'
import ToolsPage from './ToolsPage.vue'

import { guardFormEnter } from '@skillforge/shared/admin'
import { computed, reactive, ref, shallowRef, watch, useId, onScopeDispose, nextTick } from 'vue'
import type { FormInstance } from 'element-plus'
import { useRoute, useRouter } from 'vue-router'
import {
  AdminPage,
  EntitySelect,
  statusLabel,
  AgentConfigFields,
  useTask,
  useResource,
  useUnsavedChanges,
  useDrawerClose
} from '@skillforge/shared/admin'
import { confirm, message } from '@skillforge/shared/feedback'
import {
  api,
  getSessionId,
  type AgentDetail,
  type AgentSaveAndPublishCommand,
  type AgentSaveAndPublishReceipt,
  type AgentItem,
  type AgentPublication,
  type AgentExecutionConfig,
  type ModelSummary,
  type CapabilityType,
  type CapabilityVersion,
  type CapabilityDefinition,
  type CapabilityMount,
  type CapabilityMountRequest,
  type CapabilityDeployment,
  type WorkflowMountOption
} from '../../api'
import { HttpClientError } from '@skillforge/shared/utils/http'
import { useAuthStore } from '../../stores/auth'
import { toolsApi, type AvailableTool, type ToolOnboardingReceipt } from '../../api/tools'
import WorkflowOnboarding from '../../components/WorkflowOnboarding.vue'
import { listenKnowledgePublished } from './knowledgePublicationEvents'
import { listenSkillPublished } from './skillPublicationEvents'
const auth = useAuthStore()
const can = (permission: string) => auth.user?.permissions.includes(permission) === true
const canSaveAndPublish = computed(
  () => can('foundation.agents.update') && can('foundation.agents.publish')
)
const canPublishAndTrial = computed(
  () =>
    canSaveAndPublish.value &&
    [
      'foundation.agents.read',
      'foundation.sessions.read',
      'foundation.sessions.create',
      'foundation.sessions.run'
    ].every(can)
)
const canOnboardWorkflow = computed(() =>
  ['foundation.agents.read', 'foundation.workflows.read', 'foundation.capabilities.read'].every(can)
)
const workflowReceiptOpen = ref(false)
const workflowReceiptBusy = ref(false)
const workflowReceiptUnknown = ref(false)
async function closeWorkflowReceipt() {
  if (workflowReceiptBusy.value) return
  if (
    workflowReceiptUnknown.value &&
    !(await confirm({
      title: '关闭回执核对',
      content: '原操作尚未确认。请记录请求 ID，之后继续核对。',
      confirmText: '关闭',
      cancelText: '继续核对'
    }))
  )
    return
  workflowReceiptOpen.value = false
}
const canOnboardTools = computed(() =>
  [
    'foundation.connectors.read',
    'foundation.capabilities.read',
    'foundation.connectors.update',
    'foundation.capabilities.create',
    'foundation.capabilities.publish'
  ].every(can)
)
const router = useRouter()
const route = useRoute(),
  id = computed(() => String(route.params.id))
let publicationReadGuard: (() => boolean) | undefined
let agentReadSerial = 0
const {
  data: agent,
  loading,
  error: loadError,
  invalidate: invalidateAgent,
  reload
} = useResource(async () => {
  const guard = publicationReadGuard
  publicationReadGuard = undefined
  const read = ++agentReadSerial
  try {
    const value = await api.agents.get(id.value)
    if (guard && !guard() && read === agentReadSerial) invalidateAgent()
    return value
  } catch (reason) {
    if (guard && !guard()) {
      if (read === agentReadSerial) invalidateAgent()
      if (reason instanceof HttpClientError) reason.claimFeedback()
    }
    throw reason
  }
})
const { busy, error, run } = useTask({
  handlesError: (reason) => {
    if (
      reason instanceof HttpClientError &&
      reason.code === 'INSTITUTION_MODEL_SELECTION_REQUIRED' &&
      !publicationOperation.value
    ) {
      message.error('请先在机构设置中配置默认对话模型，再发布智能体')
      return true
    }
    return false
  }
})
const modelOptions = useResource(async () =>
  (await api.settings.availableModels()).filter((model) => model.kind === 'LLM')
)
const delegationOptions = useResource(async () =>
  (await api.agents.list()).items.filter(
    (agent) => agent.id !== id.value && agent.status === 'published'
  )
)
const pageError = computed(() =>
  [
    error.value ||
      (loadError.value && publicationReceipt.value
        ? `保存并发布已确认，但配置未刷新：${loadError.value}。请重新读取，不必重复提交。`
        : loadError.value),
    modelOptions.error.value && `模型选项读取失败：${modelOptions.error.value}`,
    delegationOptions.error.value && `委派选项读取失败：${delegationOptions.error.value}`,
    mountNames.error.value && `能力版本名称读取失败：${mountNames.error.value}`,
    publicationHistory.error.value && `发布历史读取失败：${publicationHistory.error.value}`
  ]
    .filter(Boolean)
    .join('；')
)
async function loadModels() {
  const value = await modelOptions.reload()
  if (value) models.value = value
}
async function loadDelegationAgents() {
  const value = await delegationOptions.reload()
  if (value) otherAgents.value = value
}
const activeTab = ref('config')
const refreshRequired = ref(false)
const publicationOperation = shallowRef<{
  requestId: string
  agentId: string
  kind?: 'save-and-publish' | 'publish'
  command?: AgentSaveAndPublishCommand | { agent_id: string; request_id: string }
  formSnapshot?: string
}>()
const publicationStorageError = ref('')
const publicationStorageKey = () =>
  auth.user?.tenant_id && auth.user?.sub
    ? `skillforge.agent-publication:${encodeURIComponent(JSON.stringify([auth.user.tenant_id, auth.user.sub, id.value]))}`
    : ''
function loadPublicationOperation() {
  publicationOperation.value = undefined
  publicationStorageError.value = ''
  const key = publicationStorageKey()
  if (!key) return
  try {
    const raw = sessionStorage.getItem(key)
    if (!raw) return
    const value = JSON.parse(raw) as {
      requestId: string
      agentId: string
      kind?: 'save-and-publish' | 'publish'
      sessionIdentity?: string
    }
    if (
      value.agentId !== id.value ||
      !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(value.requestId)
    )
      throw Error('invalid')
    if (value.sessionIdentity && value.sessionIdentity !== auth.sessionIdentity) return
    publicationOperation.value = {
      requestId: value.requestId,
      agentId: value.agentId,
      kind: value.kind
    }
  } catch {
    publicationStorageError.value = '无法读取原发布请求记录，请恢复浏览器存储后重新进入。'
  }
}
function clearPublicationOperation() {
  try {
    sessionStorage.removeItem(publicationStorageKey())
    publicationOperation.value = undefined
    publicationStorageError.value = ''
    return true
  } catch {
    publicationStorageError.value = '无法清除原发布请求记录，请恢复浏览器存储后重新核对。'
    return false
  }
}
const publicationReceipt = shallowRef<AgentSaveAndPublishReceipt>()
const publicationReceiptKind = ref<'save-and-publish' | 'publish'>('save-and-publish')
const publicationCheck = ref('')
const publicationOwner = computed(() => `${auth.identityVersion}:${id.value}`)
let publicationDisposed = false
let pendingKnowledgeNotice = ''
function refreshAfterKnowledgeNotice(owner: string) {
  const current =
    auth.user?.tenant_id && auth.user?.sub
      ? JSON.stringify([auth.user.tenant_id, auth.user.sub])
      : ''
  if (
    !current ||
    !mountOpen.value ||
    mountType.value !== 'KNOWLEDGE_SOURCE' ||
    !can('foundation.knowledge.read') ||
    !can('foundation.capabilities.read')
  ) {
    pendingKnowledgeNotice = ''
    return
  }
  if (owner !== current) {
    if (pendingKnowledgeNotice === owner) pendingKnowledgeNotice = ''
    return
  }
  if (busy.value) {
    pendingKnowledgeNotice = owner
    return
  }
  pendingKnowledgeNotice = ''
  void refreshKnowledgeBases()
}
const stopKnowledgeNotifications = listenKnowledgePublished(refreshAfterKnowledgeNotice)
watch(busy, (working) => {
  if (!working && pendingKnowledgeNotice) refreshAfterKnowledgeNotice(pendingKnowledgeNotice)
})
onScopeDispose(() => {
  publicationDisposed = true
  stopKnowledgeNotifications()
})
const editingBlocked = computed(
  () => busy.value || !!publicationOperation.value || !!publicationStorageError.value
)
const mountVersionNames = ref<Record<string, string>>({})
const skillBudgetOpen = ref(false)
const skillBudgetMount = shallowRef<Extract<CapabilityMount, { capabilityType: 'SKILL' }>>()
const skillBudget = ref(1)
const skillBudgetError = ref('')
const skillBudgetNeedsRefresh = ref(false)
function openSkillBudget(mount: CapabilityMount) {
  if (mount.capabilityType !== 'SKILL') return
  skillBudgetMount.value = mount
  skillBudget.value = mount.skillConfig.tokenBudget
  skillBudgetError.value = ''
  skillBudgetNeedsRefresh.value = false
  skillBudgetOpen.value = true
}
async function refreshSkillBudget() {
  const mount = skillBudgetMount.value
  if (!mount) return
  const next = await reload()
  if (!next) return
  const current = next.mounts.find((item) => item.mountId === mount.mountId)
  skillBudgetNeedsRefresh.value = false
  refreshRequired.value = false
  if (!current || current.capabilityType !== 'SKILL') {
    skillBudgetOpen.value = false
    message.warning('该技能挂载已变化，请查看最新能力列表。')
    return
  }
  skillBudgetMount.value = current
  message.info(`当前挂载预算为 ${current.skillConfig.tokenBudget}，请核对后再保存。`)
}
async function saveSkillBudget() {
  const mount = skillBudgetMount.value
  if (!mount || skillBudgetNeedsRefresh.value || refreshRequired.value) return
  skillBudgetError.value =
    Number.isSafeInteger(skillBudget.value) && skillBudget.value > 0
      ? ''
      : '请输入正整数 Token 预算'
  if (skillBudgetError.value) return
  await run(async () => {
    let confirmed = false
    try {
      await api.agents.capabilityMounts.update(id.value, mount.mountId, mount.revision, {
        capabilityType: 'SKILL',
        capabilityDefinitionId: mount.capabilityDefinitionId,
        priority: mount.priority,
        status: mount.status,
        skillConfig: { tokenBudget: skillBudget.value }
      })
      confirmed = true
    } finally {
      if (!confirmed) {
        skillBudgetNeedsRefresh.value = true
        refreshRequired.value = true
      }
    }
    skillBudgetOpen.value = false
    refreshRequired.value = true
    message.success('技能预算已保存，发布后生效')
    if (await reload()) refreshRequired.value = false
  })
}
const mountNames = useResource(async () => {
  const ids = [...new Set((agent.value?.mounts ?? []).map((mount) => mount.capabilityVersionId))]
  const versions = await Promise.all(ids.map((versionId) => api.capabilities.getVersion(versionId)))
  return Object.fromEntries(
    versions.map((version) => [version.id, `${version.name} / ${version.versionKey}`])
  )
})
async function loadMountNames() {
  if (!can('foundation.capabilities.read')) return
  const names = await mountNames.reload()
  if (names) mountVersionNames.value = names
}
const saved = ref(''),
  models = ref<ModelSummary[]>([]),
  otherAgents = ref<AgentItem[]>([]),
  formRef = ref<FormInstance>()
const form = reactive({
  name: '',
  role_description: '',
  system_prompt: '',
  model_config_id: '',
  memory: false,
  execution_config: {} as AgentExecutionConfig,
  delegation_config: { enabled: false, target_agent_ids: [] as string[] }
})
const dirty = computed(() => !!agent.value && JSON.stringify(form) !== saved.value)
useUnsavedChanges({
  dirty: () => dirty.value || !!publicationOperation.value,
  busy: () => busy.value,
  leaveMessage: () =>
    publicationOperation.value
      ? `保存并发布结果尚未确认。离开后原提交内容将丢失，但仍可用请求号 ${publicationOperation.value.requestId} 核对原操作。`
      : undefined
})
watch(
  publicationOwner,
  () => {
    invalidateAgent()
    skillBudgetOpen.value = false
    skillBudgetMount.value = undefined
    mountNames.invalidate()
    mountNames.error.value = ''
    mountVersionNames.value = {}
    loadPublicationOperation()
    publicationReceipt.value = undefined
    publicationCheck.value = ''
    error.value = ''
  },
  { flush: 'sync', immediate: true }
)
function fill(value: AgentDetail) {
  refreshRequired.value = false
  skillBudgetNeedsRefresh.value = false
  Object.assign(form, {
    name: value.name,
    role_description: value.role_description ?? '',
    system_prompt: value.system_prompt ?? '',
    model_config_id: value.model_config_id ?? '',
    memory:
      (value.config?.memory as { automatic_extraction?: boolean } | undefined)
        ?.automatic_extraction === true,
    execution_config: { ...value.execution_config },
    delegation_config: structuredClone(value.delegation_config)
  })
  saved.value = JSON.stringify(form)
}
watch(
  id,
  async () => {
    const owner = publicationOwner.value
    const value = await reload()
    if (!value) return
    // 主对象先完整回填；模型与委派选项失败不能留下可提交的空表单。
    fill(value)
    void loadModels()
    void loadDelegationAgents()
    void loadMountNames()
    if (
      !publicationDisposed &&
      owner === publicationOwner.value &&
      !busy.value &&
      !error.value &&
      publicationOperation.value &&
      !publicationOperation.value.command &&
      can('foundation.agents.read')
    )
      await checkPublication()
  },
  { immediate: true }
)
const budgets: Array<{ key: keyof AgentExecutionConfig; label: string }> = [
  { key: 'max_business_model_calls', label: '业务模型调用次数' },
  { key: 'max_tool_calls', label: '最大工具调用次数' },
  { key: 'max_knowledge_calls', label: '最大知识检索次数' },
  { key: 'max_total_tokens', label: '累计总 Tokens' },
  { key: 'max_wall_time_seconds', label: '活跃执行时长（秒）' }
]
function currentConfiguration(): AgentSaveAndPublishCommand['payload'] {
  return {
    name: form.name,
    role_description: form.role_description,
    system_prompt: form.system_prompt,
    model_config_id: form.model_config_id,
    execution_config: form.execution_config,
    delegation_config: form.delegation_config,
    config: {
      ...agent.value?.config,
      memory: {
        ...((agent.value?.config?.memory ?? {}) as object),
        automatic_extraction: form.memory
      }
    }
  }
}
async function acceptPublication(
  value: AgentSaveAndPublishReceipt,
  operation: NonNullable<typeof publicationOperation.value>,
  owner: string,
  ownsSession?: () => boolean
) {
  if (
    !value ||
    value.request_id !== operation.requestId ||
    value.agent_id !== operation.agentId ||
    typeof value.publication_id !== 'string' ||
    !value.publication_id.trim() ||
    !Number.isSafeInteger(value.revision) ||
    value.revision < 1 ||
    typeof value.published_at !== 'string' ||
    !Number.isFinite(Date.parse(value.published_at))
  )
    throw Error('保存并发布回执不完整，请核对原操作')
  if (ownsSession && !ownsSession()) return
  if (!clearPublicationOperation()) return
  publicationReceiptKind.value = operation.kind ?? 'save-and-publish'
  publicationReceipt.value = value
  publicationCheck.value = ''
  if (operation.formSnapshot) saved.value = operation.formSnapshot
  refreshRequired.value = true
  if (agent.value) agent.value.has_draft_changes = false
  let next: AgentDetail | undefined
  publicationReadGuard = ownsSession
  try {
    next = await reload()
  } finally {
    if (publicationReadGuard === ownsSession) publicationReadGuard = undefined
  }
  if (publicationDisposed || owner !== publicationOwner.value || (ownsSession && !ownsSession()))
    return
  if (next) {
    fill(next)
    message.success(
      `智能体已${operation.kind === 'publish' ? '发布' : '保存并发布'}，修订 ${value.revision}`
    )
    if (activeTab.value === 'history') await publicationHistory.reload()
    return next
  }
}
async function dispatchPublication(
  operation: NonNullable<typeof publicationOperation.value>,
  owner: string,
  retry: boolean,
  ownsSession?: () => boolean
) {
  try {
    const value =
      operation.kind === 'publish'
        ? await api.agents.publishWithReceipt(operation.command!)
        : await api.agents.saveAndPublish(operation.command! as AgentSaveAndPublishCommand)
    if (publicationDisposed || owner !== publicationOwner.value || (ownsSession && !ownsSession()))
      return
    const detail = await acceptPublication(value, operation, owner, ownsSession)
    return detail ? { receipt: value, detail } : undefined
  } catch (cause) {
    if (
      publicationDisposed ||
      owner !== publicationOwner.value ||
      (ownsSession && !ownsSession())
    ) {
      if (cause instanceof HttpClientError) cause.claimFeedback()
      throw 'cancel'
    }
    const rejected =
      cause instanceof HttpClientError &&
      cause.status >= 400 &&
      cause.status < 500 &&
      cause.status !== 401 &&
      cause.status !== 408 &&
      cause.code !== 'AGENT_SAVE_PUBLISH_REQUEST_CONFLICT' &&
      !!cause.code &&
      !!cause.traceId
    if (!retry && rejected) clearPublicationOperation()
    throw cause
  }
}
async function validateConfiguration() {
  if (!formRef.value) return false
  const result = await formRef.value
    .validate()
    .catch((fields: Record<string, unknown>) => Object.keys(fields)[0])
  if (result === true) return true
  activeTab.value = 'config'
  await nextTick()
  if (result) formRef.value?.scrollToField(result)
  return false
}
async function saveAndPublish(trial = false) {
  if (
    !canSaveAndPublish.value ||
    !dirty.value ||
    refreshRequired.value ||
    publicationOperation.value ||
    publicationStorageError.value
  )
    return
  if (
    trial &&
    (!canPublishAndTrial.value ||
      !auth.sessionIdentity ||
      !auth.user?.session_id ||
      !getSessionId())
  )
    return
  const owner = publicationOwner.value
  const browserSession = trial ? getSessionId() : null
  const sessionIdentity = auth.sessionIdentity
  const securitySession = auth.user?.session_id
  const tenant = auth.user?.tenant_id
  const subject = auth.user?.sub
  const ownsSession = () =>
    !!browserSession &&
    !!sessionIdentity &&
    !!securitySession &&
    !!tenant &&
    !!subject &&
    auth.sessionIdentity === sessionIdentity &&
    getSessionId() === browserSession &&
    auth.user?.session_id === securitySession &&
    auth.user?.tenant_id === tenant &&
    auth.user?.sub === subject
  const result = await run(async () => {
    if (!(await validateConfiguration())) return
    if (!form.name.trim()) throw Error('请输入名称')
    if (
      !(await confirm({
        title: trial ? '保存发布并试用智能体' : '保存并发布智能体',
        confirmText: trial ? '保存发布并试用' : '保存并发布',
        content: `保存“${form.name}”的本次配置并发布，同时采用已保存的能力挂载。已有会话下一轮聊天采用新配置，当前执行继续原配置。${trial ? '发布确认后进入试用，消息仍需手动发送。' : ''}`
      }))
    )
      return
    if (
      publicationDisposed ||
      owner !== publicationOwner.value ||
      !canSaveAndPublish.value ||
      (trial && (!canPublishAndTrial.value || !ownsSession()))
    )
      return
    const requestId = crypto.randomUUID()
    const operation = {
      requestId,
      agentId: id.value,
      command: {
        agent_id: id.value,
        request_id: requestId,
        payload: JSON.parse(
          JSON.stringify(currentConfiguration())
        ) as AgentSaveAndPublishCommand['payload']
      },
      formSnapshot: JSON.stringify(form)
    }
    const key = publicationStorageKey()
    if (!key) throw Error('当前身份不可用，尚未发送发布请求')
    try {
      sessionStorage.setItem(
        key,
        JSON.stringify({ requestId: operation.requestId, agentId: operation.agentId })
      )
    } catch {
      publicationStorageError.value = '无法保存原发布请求记录，尚未发送发布请求。'
      throw Error(publicationStorageError.value)
    }
    publicationReceipt.value = undefined
    publicationOperation.value = operation
    publicationCheck.value = ''
    return await dispatchPublication(operation, owner, false, trial ? ownsSession : undefined)
  })
  if (
    trial &&
    result &&
    !publicationDisposed &&
    owner === publicationOwner.value &&
    ownsSession()
  ) {
    if (
      canPublishAndTrial.value &&
      !publicationOperation.value &&
      !publicationStorageError.value &&
      !refreshRequired.value &&
      !dirty.value &&
      result.detail.id === id.value &&
      result.detail.status === 'published' &&
      result.detail.published_revision === result.receipt.revision &&
      publicationReceipt.value === result.receipt
    )
      await router.push({ path: '/admin/chat', query: { agent_id: id.value } })
    else message.warning('智能体已发布，但当前状态或试用权限已变化，请核对后进入试用。')
  }
}
async function continuePublication() {
  if (
    !publicationOperation.value?.command ||
    !(publicationOperation.value.kind === 'publish'
      ? can('foundation.agents.publish')
      : canSaveAndPublish.value)
  )
    return
  const operation = publicationOperation.value
  const owner = publicationOwner.value
  await run(() => dispatchPublication(operation, owner, true))
}
async function checkPublication() {
  if (!publicationOperation.value) return
  const operation = publicationOperation.value
  const owner = publicationOwner.value
  await run(async () => {
    try {
      const value = await api.agents.saveAndPublishResult(operation.agentId, operation.requestId)
      if (publicationDisposed || owner !== publicationOwner.value) return
      if (value) await acceptPublication(value, operation, owner)
      else
        publicationCheck.value = operation.command
          ? '尚未查询到原操作回执，结果仍未确认。可稍后核对，或继续原操作。'
          : '尚未查询到原操作回执，结果仍未确认，请稍后核对。'
    } catch (cause) {
      if (publicationDisposed || owner !== publicationOwner.value) {
        if (cause instanceof HttpClientError) cause.claimFeedback()
        throw 'cancel'
      }
      throw cause
    }
  })
}
async function save() {
  if (refreshRequired.value || publicationOperation.value || publicationStorageError.value) return
  await run(async () => {
    if (!(await validateConfiguration())) return
    if (!form.name.trim()) throw Error('请输入名称')
    const submitted = JSON.stringify(form)
    await api.agents.update(id.value, currentConfiguration())
    saved.value = submitted
    refreshRequired.value = true
    if (agent.value) agent.value.has_draft_changes = true
    message.success('智能体草稿已保存，发布后生效')
    const next = await reload()
    if (next) fill(next)
  })
}
async function publish() {
  if (
    !can('foundation.agents.publish') ||
    refreshRequired.value ||
    publicationOperation.value ||
    publicationStorageError.value
  )
    return
  const owner = publicationOwner.value
  await run(async () => {
    if (dirty.value) throw Error('请先保存当前修改')
    if (
      !(await confirm({
        title: '发布智能体',
        confirmText: '发布',
        content: `发布“${agent.value?.name}”的当前草稿？已有会话下一轮聊天采用新配置，当前执行继续原配置。`
      }))
    )
      return
    if (
      publicationDisposed ||
      owner !== publicationOwner.value ||
      !can('foundation.agents.publish')
    )
      return
    const requestId = crypto.randomUUID()
    const operation = {
      kind: 'publish' as const,
      requestId,
      agentId: id.value,
      command: { agent_id: id.value, request_id: requestId }
    }
    const key = publicationStorageKey()
    if (!key) throw Error('当前身份不可用，尚未发送发布请求')
    try {
      sessionStorage.setItem(
        key,
        JSON.stringify({
          kind: operation.kind,
          requestId,
          agentId: id.value,
          sessionIdentity: auth.sessionIdentity
        })
      )
    } catch {
      publicationStorageError.value = '无法保存原发布请求记录，尚未发送发布请求。'
      throw Error(publicationStorageError.value)
    }
    publicationReceipt.value = undefined
    publicationOperation.value = operation
    publicationCheck.value = ''
    await dispatchPublication(operation, owner, false)
  })
}

async function refreshAgent() {
  await run(async () => {
    if (
      dirty.value &&
      !(await confirm({
        title: '重新读取智能体',
        content: '重新读取会放弃当前未保存的配置。',
        confirmText: '放弃并读取'
      }))
    )
      return
    const next = await reload()
    if (next) fill(next)
  })
}
const publicationHistory = useResource(
  async () => (await api.agents.revisions(id.value)).items,
  [] as AgentPublication[]
)
const publications = publicationHistory.data
watch(publicationOwner, () => {
  publicationHistory.invalidate()
  publications.value = []
  publicationHistory.error.value = ''
})
const publicationDetail = ref<AgentDetail>(),
  publicationOpen = ref(false)
async function loadPublications(tab: string | number) {
  if (tab !== 'history') return
  await publicationHistory.reload()
}
async function viewPublication(publication: AgentPublication) {
  await run(async () => {
    publicationDetail.value = await api.agents.revisionDetail(id.value, publication.revision)
    publicationOpen.value = true
  })
}
async function restorePublication(publication: AgentPublication) {
  if (refreshRequired.value || publicationOperation.value || publicationStorageError.value) return
  await run(async () => {
    if (dirty.value) throw Error('请先保存当前修改')
    if (
      !(await confirm({
        title: '恢复智能体草稿',
        confirmText: '恢复为草稿',
        danger: true,
        content: `将“${publication.name}”发布修订 ${publication.revision} 恢复到工作草稿？当前草稿将被覆盖，发布后才会生效。`
      }))
    )
      return
    await api.agents.restoreRevision(id.value, publication.revision)
    refreshRequired.value = true
    message.success('历史配置已恢复为草稿，发布后生效')
    const next = await reload()
    if (next) fill(next)
  })
}
const mountSaved = ref('')
const mountSavedWithoutType = ref('')
const mountSnapshot = (includeType = true) =>
  JSON.stringify([
    ...(includeType ? [mountType.value] : []),
    versionId.value,
    priority.value,
    tokenBudget.value,
    deploymentKey.value,
    workflowInstanceId.value,
    definitionId.value,
    knowledgeBaseId.value
  ])
const mountOpen = ref(false),
  mountType = ref<CapabilityType>('SKILL'),
  versions = ref<
    Array<
      Pick<CapabilityVersion, 'id' | 'definitionId' | 'name' | 'versionKey'> & {
        skillEstimatedTokens?: number | null
      }
    >
  >([]),
  definitions = ref<CapabilityDefinition[]>([]),
  definitionId = ref<string>(),
  versionId = ref<string>(),
  priority = ref(0),
  tokenBudget = ref(2000),
  deploymentKey = ref(''),
  deployments = ref<CapabilityDeployment[]>([]),
  workflowInstanceId = ref(''),
  instances = ref<WorkflowMountOption[]>([]),
  knowledgeBaseId = ref(''),
  knowledgeBases = ref<Array<{ id: string; label: string; description: string }>>([]),
  knowledgeSearch = ref(''),
  activeKnowledgeRevisionId = ref(''),
  tokenBudgetError = ref('')
const advancedToolMount = ref(false)
const autoToolId = ref<string>()
const onboardingOpen = ref(false)
const workflowOnboardingOpen = ref(false)
const workflowOnboarding = ref<InstanceType<typeof WorkflowOnboarding>>()
const onboardingLocked = ref(false)
const onboardingUnknown = ref(false)
const onboardingBusy = ref(false)
const toolsRefreshRequired = ref(false)
const workflowOptionsRefreshRequired = ref(false)
const knowledgeUnpublished = ref(false)
const autoKnowledgeId = ref('')
const knowledgeTouched = ref(false)
let assigningAutoKnowledge = false
const pendingToolReceipt = ref<ToolOnboardingReceipt>()
const availableTools = ref<AvailableTool[]>([])
const mountOptionsState = ref<'loading' | 'ready' | 'failed'>('loading')
const toolSearch = ref('')
const definitionSearch = ref('')
const skillCatalogNeedsRefresh = ref(false)
let skillNoticeGeneration = 0
type SkillNotice = { actor: string; owner: string; session: string; browserSession: string | null }
let pendingSkillNotice: SkillNotice | undefined
let openingSkillNotice: SkillNotice | undefined
function skillNoticeCurrent(notice: SkillNotice, includeOpening = false) {
  const actor =
    auth.user?.tenant_id && auth.user?.sub
      ? JSON.stringify([auth.user.tenant_id, auth.user.sub])
      : ''
  return (
    !publicationDisposed &&
    !!actor &&
    actor === notice.actor &&
    publicationOwner.value === notice.owner &&
    auth.sessionIdentity === notice.session &&
    getSessionId() === notice.browserSession &&
    (mountOpen.value ||
      (includeOpening &&
        openingSkillNotice?.actor === notice.actor &&
        openingSkillNotice.owner === notice.owner &&
        openingSkillNotice.session === notice.session &&
        openingSkillNotice.browserSession === notice.browserSession)) &&
    mountType.value === 'SKILL' &&
    can('foundation.capabilities.read')
  )
}
function acceptSkillNotice(actor: string) {
  const notice = {
    actor,
    owner: publicationOwner.value,
    session: auth.sessionIdentity,
    browserSession: getSessionId()
  }
  if (!skillNoticeCurrent(notice, true)) return
  skillCatalogNeedsRefresh.value = true
  skillNoticeGeneration++
  if (busy.value || onboardingBusy.value) {
    pendingSkillNotice = notice
    return
  }
  pendingSkillNotice = undefined
  void refreshSkills()
}
const stopSkillNotifications = listenSkillPublished(acceptSkillNotice)
watch([busy, onboardingBusy], ([working, onboarding]) => {
  if (working || onboarding || !pendingSkillNotice) return
  const notice = pendingSkillNotice
  pendingSkillNotice = undefined
  if (skillNoticeCurrent(notice)) void refreshSkills()
})
onScopeDispose(stopSkillNotifications)
const filteredKnowledgeBases = computed(() => {
  const keyword = knowledgeSearch.value.trim().toLocaleLowerCase()
  return knowledgeBases.value.filter((base) =>
    [base.label, base.description, base.id].some((value) =>
      value.toLocaleLowerCase().includes(keyword)
    )
  )
})
const mountedDefinitionIds = computed(
  () => new Set((agent.value?.mounts ?? []).map((mount) => mount.capabilityDefinitionId))
)
const mountableDefinitions = computed(() =>
  definitions.value.filter((definition) => !mountedDefinitionIds.value.has(definition.id))
)
const mountableTools = computed(() =>
  availableTools.value.filter((tool) => !mountedDefinitionIds.value.has(tool.definition_id))
)
const knowledgeAlreadyMounted = computed(
  () => !!definitionId.value && mountedDefinitionIds.value.has(definitionId.value)
)
watch(mountableDefinitions, (items) => {
  if (
    mountType.value === 'WORKFLOW' &&
    definitionId.value &&
    !items.some((item) => item.id === definitionId.value)
  ) {
    definitionId.value = undefined
  }
})
const filteredDefinitions = computed(() => {
  const keyword = definitionSearch.value.trim().toLocaleLowerCase()
  return mountableDefinitions.value.filter((definition) =>
    [definition.name, definition.description ?? '', definition.capabilityKey ?? ''].some((value) =>
      value.toLocaleLowerCase().includes(keyword)
    )
  )
})
const filteredAvailableTools = computed(() => {
  const keyword = toolSearch.value.trim().toLocaleLowerCase()
  return mountableTools.value.filter((tool) =>
    [
      tool.name,
      tool.tool_name,
      tool.description,
      ...tool.deployments.map((item) => item.provider_label)
    ].some((value) => value.toLocaleLowerCase().includes(keyword))
  )
})
const selectedAvailableTool = computed(() =>
  mountableTools.value.find((tool) => tool.definition_id === definitionId.value)
)
const selectedDefinition = computed(() =>
  mountableDefinitions.value.find((d) => d.id === definitionId.value)
)
const selectedSkillEstimatedTokens = computed(() =>
  mountType.value === 'SKILL' && !skillSelectionStale.value
    ? versions.value.find(
        (version) => version.id === versionId.value && version.definitionId === definitionId.value
      )?.skillEstimatedTokens
    : undefined
)
const skillSelectionStale = computed(
  () =>
    mountType.value === 'SKILL' &&
    !!selectedDefinition.value &&
    (skillCatalogNeedsRefresh.value ||
      (!!versionId.value && selectedDefinition.value.defaultVersionId !== versionId.value))
)
function confirmCurrentSkill() {
  if (mountType.value === 'SKILL' && selectedDefinition.value)
    versionId.value = selectedDefinition.value.defaultVersionId ?? undefined
}
const versionBelongsToDefinition = (versionId: string | null) =>
  versions.value.some((v) => v.id === versionId && v.definitionId === definitionId.value)
const matchingInstances = computed(() =>
  instances.value.filter((item) => item.definitionId === definitionId.value)
)
const singleWorkflowInstance = computed(() =>
  matchingInstances.value.length === 1 && matchingInstances.value[0]?.installationRevision
    ? matchingInstances.value[0]
    : undefined
)
const matchingDeployments = computed(() =>
  deployments.value.filter((item) => versionBelongsToDefinition(item.toolVersionId))
)
watch(definitionId, () => {
  if (definitionId.value !== autoToolId.value) autoToolId.value = undefined
  if (mountType.value === 'KNOWLEDGE_SOURCE') return
  if (mountType.value === 'TOOL' && !advancedToolMount.value) {
    versionId.value = selectedAvailableTool.value?.default_version_id
    deploymentKey.value =
      selectedAvailableTool.value?.deployments.length === 1
        ? selectedAvailableTool.value.deployments[0]!.deployment_key
        : ''
    return
  }
  versionId.value = selectedDefinition.value?.defaultVersionId ?? undefined
  deploymentKey.value = ''
  workflowInstanceId.value =
    mountType.value === 'WORKFLOW' ? (singleWorkflowInstance.value?.id ?? '') : ''
})
const knowledgeVersion = ref<{
  id: string
  definitionId: string
  versionKey: string
  name: string
}>()
function applyKnowledgeOption(option: Awaited<ReturnType<typeof api.knowledge.mountOption>>) {
  if (option.kb_id !== knowledgeBaseId.value) throw Error('知识库发布身份不匹配')
  activeKnowledgeRevisionId.value = option.active_revision_id ?? ''
  knowledgeUnpublished.value = !option.active_revision_id
  knowledgeVersion.value =
    option.version_id && option.definition_id && option.version_key
      ? {
          id: catalogDecimalIdentity(option.version_id, 'version_id'),
          definitionId: catalogDecimalIdentity(option.definition_id, 'definition_id'),
          versionKey: option.version_key,
          name:
            option.version_name ||
            knowledgeBases.value.find((base) => base.id === option.kb_id)?.label ||
            '知识来源'
        }
      : undefined
  versionId.value = knowledgeVersion.value?.id
  definitionId.value = knowledgeVersion.value?.definitionId
}
async function readKnowledgeBase(kbId: string) {
  if (!kbId) return
  const owner = publicationOwner.value
  const session = auth.sessionIdentity
  const browserSession = getSessionId()
  const current = () =>
    !publicationDisposed &&
    publicationOwner.value === owner &&
    auth.sessionIdentity === session &&
    getSessionId() === browserSession &&
    mountOpen.value &&
    mountType.value === 'KNOWLEDGE_SOURCE' &&
    knowledgeBaseId.value === kbId
  await run(async () => {
    try {
      const option = await api.knowledge.mountOption(kbId)
      if (current()) applyKnowledgeOption(option)
    } catch (cause) {
      if (!current()) {
        if (cause instanceof HttpClientError) cause.claimFeedback()
        throw 'cancel'
      }
      throw cause
    }
  })
}
function knowledgeSelectedByUser() {
  knowledgeTouched.value = true
  autoKnowledgeId.value = ''
}
watch(
  knowledgeBaseId,
  (kbId) => {
    if (mountType.value !== 'KNOWLEDGE_SOURCE') return
    activeKnowledgeRevisionId.value = ''
    knowledgeUnpublished.value = false
    knowledgeVersion.value = undefined
    versionId.value = undefined
    definitionId.value = undefined
    if (!assigningAutoKnowledge) void readKnowledgeBase(kbId)
  },
  { flush: 'sync' }
)
async function selectOnlyKnowledge(
  bases: Awaited<ReturnType<typeof api.knowledge.listBases>>['items'],
  current: () => boolean
) {
  if (knowledgeTouched.value || knowledgeBaseId.value) return
  if (bases.some((base) => base.status !== 'active' && base.status !== 'archived')) return
  const available = bases.filter((base) => base.status === 'active')
  if (available.length !== 1) return
  const kbId = available[0]!.id
  assigningAutoKnowledge = true
  try {
    knowledgeBaseId.value = kbId
  } finally {
    assigningAutoKnowledge = false
  }
  autoKnowledgeId.value = kbId
  try {
    const option = await api.knowledge.mountOption(kbId)
    if (current() && knowledgeBaseId.value === kbId && !knowledgeTouched.value)
      applyKnowledgeOption(option)
  } catch (cause) {
    if (!current() || knowledgeBaseId.value !== kbId) {
      if (cause instanceof HttpClientError) cause.claimFeedback()
      throw 'cancel'
    }
    throw cause
  }
}
async function readCurrentOptions(type: 'SKILL' | 'WORKFLOW', current?: () => boolean) {
  const guard = <T,>(promise: Promise<T>) =>
    promise.catch((cause: unknown) => {
      if (current && !current()) {
        if (cause instanceof HttpClientError) cause.claimFeedback()
        throw 'cancel'
      }
      throw cause
    })
  const [defs, options] = await Promise.all([
    guard(api.capabilities.definitions(type, 'PUBLISHED')),
    guard(api.capabilities.listRequirementOptions(type))
  ])
  const byDefinition = new Map(options.map((option) => [option.id, option]))
  return {
    definitions: defs.flatMap((definition) => {
      const option = byDefinition.get(definition.id)
      return option
        ? [{ ...definition, name: option.name, defaultVersionId: option.versionId }]
        : []
    }),
    versions: options.map((option) => ({
      id: option.versionId,
      definitionId: option.id,
      name: option.name,
      versionKey: option.versionKey,
      skillEstimatedTokens: option.skillEstimatedTokens
    }))
  }
}
async function readMountOptions() {
  mountOptionsState.value = 'loading'
  const generation = skillNoticeGeneration
  const owner = publicationOwner.value
  const session = auth.sessionIdentity
  const type = mountType.value
  const browserSession =
    type === 'KNOWLEDGE_SOURCE' || type === 'SKILL' ? getSessionId() : undefined
  const current = () =>
    !publicationDisposed &&
    publicationOwner.value === owner &&
    auth.sessionIdentity === session &&
    (browserSession === undefined || getSessionId() === browserSession) &&
    mountType.value === type
  try {
    pendingToolReceipt.value = undefined
    autoToolId.value = undefined
    autoKnowledgeId.value = ''
    knowledgeTouched.value = false
    versionId.value = undefined
    definitionId.value = undefined
    knowledgeBaseId.value = ''
    deploymentKey.value = ''
    workflowInstanceId.value = ''
    definitions.value = []
    versions.value = []
    knowledgeBases.value = []
    knowledgeSearch.value = ''
    deployments.value = []
    instances.value = []
    tokenBudgetError.value = ''
    availableTools.value = []
    toolSearch.value = ''
    definitionSearch.value = ''
    if (type === 'KNOWLEDGE_SOURCE') {
      const bases = await api.knowledge.listBases()
      if (!current()) return
      knowledgeBases.value = bases.items.map((base) => ({
        id: base.id,
        label: base.name,
        description: [base.description, `标识：${base.id}`].filter(Boolean).join(' · ')
      }))
      await selectOnlyKnowledge(bases.items, current)
      if (!current()) return
      mountOptionsState.value = 'ready'
      return
    }
    if (type === 'SKILL') {
      const options = await readCurrentOptions('SKILL', current)
      if (!current()) return
      definitions.value = options.definitions
      versions.value = options.versions
      if (generation === skillNoticeGeneration) skillCatalogNeedsRefresh.value = false
      if (mountableDefinitions.value.length === 1)
        definitionId.value = mountableDefinitions.value[0]!.id
      mountOptionsState.value = 'ready'
      return
    }
    if (type === 'WORKFLOW') {
      const [options, workflowInstances] = await Promise.all([
        readCurrentOptions('WORKFLOW'),
        api.agents.capabilityMounts.workflowOptions()
      ])
      instances.value = workflowInstances
      definitions.value = options.definitions.filter((definition) =>
        workflowInstances.some((instance) => instance.definitionId === definition.id)
      )
      versions.value = options.versions
      if (mountableDefinitions.value.length === 1)
        definitionId.value = mountableDefinitions.value[0]!.id
      workflowOptionsRefreshRequired.value = false
      mountOptionsState.value = 'ready'
      return
    }
    if (type === 'TOOL' && !advancedToolMount.value) {
      availableTools.value = (await toolsApi.available()).items
      toolsRefreshRequired.value = false
      if (mountableTools.value.length === 1) {
        autoToolId.value = mountableTools.value[0]!.definition_id
        definitionId.value = autoToolId.value
      }
      mountOptionsState.value = 'ready'
      return
    }
    const [defs, toolDeployments] = await Promise.all([
      api.capabilities.definitions(type, 'PUBLISHED'),
      api.capabilities.deployments('ACTIVE')
    ])
    const nextVersions = (
      await Promise.all(
        defs.map((definition) => api.capabilities.versions(definition.id, 'PUBLISHED'))
      )
    ).flat()
    definitions.value = defs.filter((definition) => definition.defaultVersionId)
    versions.value = nextVersions
    deployments.value = toolDeployments ?? []
    mountOptionsState.value = 'ready'
  } catch (cause) {
    if (!current()) {
      if (cause instanceof HttpClientError) cause.claimFeedback()
      throw 'cancel'
    }
    mountOptionsState.value = 'failed'
    throw cause
  }
}
async function loadVersions() {
  await run(readMountOptions)
}
async function refreshSkills() {
  const owner = publicationOwner.value
  const generation = skillNoticeGeneration
  const session = auth.sessionIdentity
  const browserSession = getSessionId()
  const current = () =>
    !publicationDisposed &&
    publicationOwner.value === owner &&
    auth.sessionIdentity === session &&
    getSessionId() === browserSession &&
    mountOpen.value &&
    mountType.value === 'SKILL' &&
    can('foundation.capabilities.read')
  await run(async () => {
    const options = await readCurrentOptions('SKILL', current)
    if (!current()) return
    definitions.value = options.definitions
    versions.value = options.versions
    if (generation === skillNoticeGeneration) skillCatalogNeedsRefresh.value = false
  })
}
async function refreshKnowledgeBases() {
  const owner = publicationOwner.value
  const session = auth.sessionIdentity
  const browserSession = getSessionId()
  const selected = knowledgeBaseId.value
  const current = () =>
    !publicationDisposed &&
    publicationOwner.value === owner &&
    auth.sessionIdentity === session &&
    getSessionId() === browserSession &&
    mountOpen.value &&
    mountType.value === 'KNOWLEDGE_SOURCE'
  const guard = <T,>(promise: Promise<T>) =>
    promise.catch((cause: unknown) => {
      if (!current() || knowledgeBaseId.value !== selected) {
        if (cause instanceof HttpClientError) cause.claimFeedback()
        throw 'cancel'
      }
      throw cause
    })
  await run(async () => {
    const [bases, option] = await Promise.all([
      guard(api.knowledge.listBases()),
      selected ? guard(api.knowledge.mountOption(selected)) : undefined
    ])
    if (!current() || knowledgeBaseId.value !== selected) return
    knowledgeBases.value = bases.items.map((item) => ({
      id: item.id,
      label: item.name,
      description: [item.description, `标识：${item.id}`].filter(Boolean).join(' · ')
    }))
    if (option) applyKnowledgeOption(option)
    else {
      const wasClean = mountSnapshot() === mountSaved.value
      await selectOnlyKnowledge(bases.items, current)
      if (wasClean && current()) mountSaved.value = mountSnapshot()
    }
  })
}
async function refreshAvailableTools() {
  await run(async () => {
    availableTools.value = (await toolsApi.available()).items
    if (
      definitionId.value &&
      !selectedAvailableTool.value?.deployments.some(
        (deployment) => deployment.deployment_key === deploymentKey.value
      )
    )
      deploymentKey.value = ''
    if (toolsRefreshRequired.value) {
      toolsRefreshRequired.value = false
      const items = pendingToolReceipt.value?.items
      if (items?.length === 1 && (!definitionId.value || definitionId.value === autoToolId.value)) {
        const item = items[0]!
        const tool = mountableTools.value.find(
          (candidate) =>
            candidate.definition_id === item.definition_id &&
            candidate.deployments.some(
              (deployment) => deployment.deployment_key === item.deployment_key
            )
        )
        if (tool) {
          toolSearch.value = ''
          definitionId.value = tool.definition_id
        }
      }
      autoToolId.value = undefined
      pendingToolReceipt.value = undefined
      onboardingOpen.value = false
      message.success('原请求已成功，已刷新当前工具目录')
    }
  })
}
function toolsConfirmed(receipt: ToolOnboardingReceipt) {
  pendingToolReceipt.value = receipt
  toolsRefreshRequired.value = true
  void refreshAvailableTools()
}
async function refreshWorkflowOptions() {
  const owner = publicationOwner.value
  await run(async () => {
    if (refreshRequired.value) {
      const next = await reload()
      if (
        !next ||
        publicationOwner.value !== owner ||
        !mountOpen.value ||
        mountType.value !== 'WORKFLOW'
      )
        return
      refreshRequired.value = false
    }
    const [options, nextInstances] = await Promise.all([
      readCurrentOptions('WORKFLOW'),
      api.agents.capabilityMounts.workflowOptions()
    ])
    if (publicationOwner.value !== owner || !mountOpen.value || mountType.value !== 'WORKFLOW')
      return
    instances.value = nextInstances
    definitions.value = options.definitions.filter((definition) =>
      nextInstances.some((instance) => instance.definitionId === definition.id)
    )
    versions.value = options.versions
    versionId.value = selectedDefinition.value?.defaultVersionId ?? undefined
    if (
      workflowInstanceId.value &&
      !matchingInstances.value.some((item) => item.id === workflowInstanceId.value)
    )
      workflowInstanceId.value = ''
    workflowOptionsRefreshRequired.value = false
  })
}
function workflowConfirmed() {
  const owner = publicationOwner.value
  refreshRequired.value = true
  if (mountOpen.value && mountType.value === 'WORKFLOW') workflowOptionsRefreshRequired.value = true
  void reload().then(async (value) => {
    if (!value || publicationOwner.value !== owner) return
    refreshRequired.value = false
    if (workflowOptionsRefreshRequired.value) void refreshWorkflowOptions()
    const ids = [...new Set(value.mounts.map((mount) => mount.capabilityVersionId))].filter(
      (versionId) => !mountVersionNames.value[versionId]
    )
    if (!ids.length) return
    try {
      const versions = await Promise.all(
        ids.map((versionId) => api.capabilities.getVersion(versionId))
      )
      if (publicationDisposed || publicationOwner.value !== owner) return
      for (const version of versions)
        mountVersionNames.value[version.id] = `${version.name} / ${version.versionKey}`
    } catch (cause) {
      if (cause instanceof HttpClientError) cause.claimFeedback()
      if (!publicationDisposed && publicationOwner.value === owner)
        message.warning('工作流已接入，但能力名称未刷新，请重新读取')
    }
  })
}
async function refreshWorkflowAgent() {
  return !!(await reload())
}
async function toggleWorkflowOnboarding() {
  if (workflowOnboardingOpen.value && !(await confirmOnboardingExit())) return
  workflowOnboardingOpen.value = !workflowOnboardingOpen.value
  onboardingLocked.value = false
  onboardingUnknown.value = false
}
async function openMount() {
  if (refreshRequired.value || publicationOperation.value || publicationStorageError.value) return
  const owner = publicationOwner.value
  const session = auth.sessionIdentity
  const browserSession =
    mountType.value === 'KNOWLEDGE_SOURCE' || mountType.value === 'SKILL'
      ? getSessionId()
      : undefined
  openingSkillNotice =
    mountType.value === 'SKILL' && auth.user?.tenant_id && auth.user?.sub
      ? {
          actor: JSON.stringify([auth.user.tenant_id, auth.user.sub]),
          owner,
          session,
          browserSession: browserSession ?? null
        }
      : undefined
  await run(async () => {
    priority.value = 0
    onboardingOpen.value = false
    workflowOnboardingOpen.value = false
    advancedToolMount.value = false
    tokenBudget.value = 2000
    await readMountOptions()
    if (
      publicationDisposed ||
      publicationOwner.value !== owner ||
      auth.sessionIdentity !== session ||
      (browserSession !== undefined && getSessionId() !== browserSession)
    )
      return
    mountSaved.value = mountSnapshot()
    mountSavedWithoutType.value = mountSnapshot(false)
    mountOpen.value = true
  })
  openingSkillNotice = undefined
}
async function addMount() {
  await run(async () => {
    if (mountType.value === 'SKILL' && skillSelectionStale.value)
      throw Error('所选技能的当前发布版本已变化，请先确认')
    if (mountType.value === 'WORKFLOW' && !selectedDefinition.value)
      throw Error('请选择可添加的工作流')
    const tool =
      mountType.value === 'TOOL' && !advancedToolMount.value
        ? selectedAvailableTool.value
        : undefined
    const version = tool
      ? {
          id: tool.default_version_id,
          definitionId: tool.definition_id,
          name: tool.name,
          versionKey: ''
        }
      : mountType.value === 'KNOWLEDGE_SOURCE'
        ? knowledgeVersion.value
        : versions.value.find((v) => v.id === versionId.value)
    if (!version || !definitionId.value) throw Error('请选择已发布能力')
    if (mountedDefinitionIds.value.has(version.definitionId)) throw Error('该能力已挂载')
    const base = {
      capabilityDefinitionId: version.definitionId,
      priority: priority.value
    }
    let request: CapabilityMountRequest
    if (mountType.value === 'SKILL') {
      tokenBudgetError.value =
        Number.isInteger(tokenBudget.value) && tokenBudget.value > 0
          ? ''
          : '请输入正整数 Token 预算'
      if (tokenBudgetError.value) throw Error(tokenBudgetError.value)
      request = {
        ...base,
        capabilityType: 'SKILL',
        skillConfig: { tokenBudget: tokenBudget.value }
      }
    } else if (mountType.value === 'TOOL') {
      const key = tool
        ? tool.deployments.find((item) => item.deployment_key === deploymentKey.value)
            ?.deployment_key
        : matchingDeployments.value.find((item) => item.deploymentKey === deploymentKey.value)
            ?.deploymentKey
      if (!key) throw Error('请选择匹配的工具服务实例')
      request = {
        ...base,
        capabilityType: 'TOOL',
        toolConfig: { toolDeploymentId: key }
      }
    } else if (mountType.value === 'WORKFLOW') {
      const item = matchingInstances.value.find((d) => d.id === workflowInstanceId.value)
      if (!item?.installationRevision) throw Error('请选择已安装工作流实例')
      request = {
        ...base,
        capabilityType: 'WORKFLOW',
        workflowConfig: {
          workflowInstanceId: item.id
        }
      }
    } else {
      if (!knowledgeBaseId.value || !activeKnowledgeRevisionId.value)
        throw Error('请选择已发布知识库')
      request = {
        ...base,
        capabilityType: 'KNOWLEDGE_SOURCE',
        knowledgeConfig: { mode: 'FOLLOW_ACTIVE', pinnedRevisionId: null }
      }
    }
    await api.agents.capabilityMounts.create(id.value, request)
    mountVersionNames.value[version.id] = version.versionKey
      ? `${version.name} / ${version.versionKey}`
      : version.name
    mountOpen.value = false
    refreshRequired.value = true
    message.success('能力已挂载')
    if (await reload()) refreshRequired.value = false
  })
}
async function removeMount(mount: CapabilityMount) {
  if (refreshRequired.value || publicationOperation.value || publicationStorageError.value) return
  if (
    await confirm({
      title: '移除能力',
      confirmText: '移除能力',
      danger: true,
      content: `从“${agent.value?.name ?? '当前智能体'}”移除“${mountVersionNames.value[mount.capabilityVersionId] ?? '所选能力'}”？修改会保存到草稿，发布后在后续运行生效。`
    })
  )
    await run(async () => {
      await api.agents.capabilityMounts.delete(id.value, mount.mountId, mount.revision)
      refreshRequired.value = true
      message.success('能力已移除')
      if (await reload()) refreshRequired.value = false
    })
}

useDrawerClose({
  open: () => mountOpen.value,
  busy: () => busy.value || onboardingBusy.value,
  dirty: () =>
    mountOpen.value &&
    (mountSnapshot() !== mountSaved.value || onboardingOpen.value || workflowOnboardingOpen.value),
  close: () => {
    mountOpen.value = false
  }
})
async function confirmOnboardingExit() {
  if (!onboardingOpen.value && !workflowOnboardingOpen.value) return true
  return confirm({
    title: onboardingUnknown.value ? '离开未确认的接入' : '收起接入',
    content: onboardingUnknown.value
      ? '接入结果未知，原操作可能已经完成；请记录页面上的请求 ID，离开后使用它查询原回执。关闭挂载抽屉也会丢弃未保存的挂载选择。'
      : '收起后将丢弃未提交的接入选择；关闭挂载抽屉也会丢弃未保存的挂载选择。',
    confirmText: '确认离开',
    cancelText: '继续接入'
  })
}
async function toggleOnboarding() {
  if (onboardingOpen.value && !(await confirmOnboardingExit())) return
  onboardingOpen.value = !onboardingOpen.value
  onboardingLocked.value = false
  onboardingUnknown.value = false
}
async function requestCloseMount() {
  if (onboardingBusy.value || busy.value) return
  const completedWorkflow =
    workflowOnboardingOpen.value &&
    !workflowOptionsRefreshRequired.value &&
    !refreshRequired.value &&
    mountSnapshot(false) === mountSavedWithoutType.value &&
    workflowOnboarding.value?.canCloseConfirmed()
  if ((onboardingOpen.value || workflowOnboardingOpen.value) && !completedWorkflow) {
    if (!(await confirmOnboardingExit())) return
  } else if (
    !completedWorkflow &&
    mountSnapshot() !== mountSaved.value &&
    !(await confirm({
      title: '关闭表单',
      content: '关闭后将丢弃未保存的内容。',
      confirmText: '丢弃并关闭',
      cancelText: '继续编辑'
    }))
  )
    return
  onboardingOpen.value = false
  workflowOnboardingOpen.value = false
  onboardingLocked.value = false
  onboardingUnknown.value = false
  mountOpen.value = false
}

const formTipId = useId()
</script>
<style scoped>
.agent-capability-option {
  height: auto;
  line-height: 1.5;
  padding-block: 8px;
  white-space: normal;
}
.agent-capability-option .admin-field-help {
  margin: 0;
}

.admin-card + .admin-card {
  margin-top: 20px;
}
.agent-overview {
  padding-block: 10px;
}
</style>
