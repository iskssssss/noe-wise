<template>
  <AdminPage title="智能体调试" description="以机构终端用户身份运行已发布智能体或团队。"
    ><FeedbackNotice v-if="pageFeedback" :title="pageFeedback" type="error" />
    <div v-if="directoryFailures.length" class="toolbar" aria-label="读取恢复">
      <ElButton
        v-for="entry in directoryFailures"
        :key="entry.label"
        :disabled="entry.label === '终端用户' && userOpen"
        @click="entry.retry"
      >
        重新读取{{ entry.label }}
      </ElButton>
    </div>
    <div v-if="restoreSessionError" class="admin-card" role="status">
      上次会话读取失败，请重新读取或选择其他会话。
      <ElButton :disabled="restoringSession" @click="restoreRecentSession"
        >重新读取上次会话</ElButton
      >
    </div>
    <p v-if="!canRun" class="admin-field-help">
      当前账号没有会话执行权限，可以查看历史，但不能发送或回复。
    </p>
    <p v-else-if="!canCreate && !sessionId" class="admin-field-help">
      当前账号没有会话创建权限，请选择已有会话继续。
    </p>
    <p v-if="!can('foundation.agents.read')" class="admin-field-help">
      当前账号没有智能体读取权限，不能选择新智能体；已有会话仍按原绑定查看。
    </p>
    <p v-if="sessionId && !canCancel" class="admin-field-help">
      当前账号没有会话停止权限，不能停止运行或关闭会话。
    </p>
    <p v-if="!canTeams" class="admin-field-help">
      当前账号没有团队读取权限，不能选择新团队；已有会话仍按原绑定查看。
    </p>
    <div v-if="pendingSession || sessionStorageError" class="admin-card" role="status">
      <p v-if="sessionStorageError">{{ sessionStorageError }}</p>
      <p v-else>
        会话创建结果尚未确认，请核对原请求；不会自动发送消息。刷新页面后需重新选择未上传的文件。
      </p>
      <ElButton
        v-if="pendingSession"
        :disabled="busy || !can('foundation.sessions.read')"
        @click="inspectSessionCreation()"
        >核对创建结果</ElButton
      >
    </div>
    <div v-if="deferredUpload" class="admin-card" role="status">
      已选择“{{ deferredUpload.file.name }}”，文件尚未上传。
      <span v-if="!sessionId && !pendingSession">请选择终端用户和对话对象后继续。</span>
      <ElButton
        v-if="!pendingSession"
        :disabled="busy || readOnly || (!sessionId && (!canCreate || !endUserId || !targetKey))"
        @click="continueUpload"
        >继续上传原文件</ElButton
      >
      <ElButton :disabled="busy" @click="deferredUpload = undefined">移除文件选择</ElButton>
    </div>
    <div v-for="upload in pendingUploads" :key="upload.uploadId" class="admin-card" role="status">
      文件“{{ upload.name }}”上传结果尚未确认，尚未加入消息；请核对原上传，不要重新上传。
      会话：<code>{{ upload.session }}</code
      >；上传标识：<code>{{ upload.uploadId }}</code
      >。
      <ElButton
        v-if="sessionId !== upload.session"
        :disabled="busy"
        @click="loadSession(upload.session)"
      >
        恢复原会话
      </ElButton>
      <ElButton
        v-else
        :disabled="busy || !can('foundation.sessions.read')"
        @click="inspectUpload(upload)"
      >
        核对原上传
      </ElButton>
    </div>
    <div v-if="pendingInput" class="admin-card" role="status">
      <p>
        {{
          pendingInput.body.hitl_response
            ? '本次回复受理结果尚未确认，已保留原等待与回复。读取待办后如仍未确认，请重试原回复；确认前不能切换会话或发送新内容。'
            : '本次消息受理结果尚未确认，已保留原请求。请只读核对受理状态，或显式重试原消息；确认前不能切换会话或发送新内容。'
        }}
      </p>
      <ElButton :disabled="busy" @click="inspectInput">{{
        pendingInput.body.hitl_response ? '核对原回复受理' : '核对原消息受理'
      }}</ElButton>
      <ElButton :disabled="busy || !canRun" @click="retryInput">{{
        pendingInput.body.hitl_response ? '重试原回复' : '重试原消息'
      }}</ElButton>
    </div>
    <ElAlert
      v-if="trialAgentUnavailable"
      title="入口指定的智能体当前不可试用：可能尚未发布、已停用或访问授权已撤销。请选择其他已发布对象，或返回配置页核对。"
      type="warning"
      :closable="false"
    />
    <p v-else-if="trialAgentReady" class="admin-muted" role="status">
      已预选智能体的当前发布版本。请选择终端用户并手动发送消息；草稿修改不会用于本次试用。
    </p>
    <ElAlert
      v-if="trialTeamUnavailable"
      title="入口指定的团队当前不可试用：可能尚未发布、已停用或访问授权已撤销。请选择其他已发布对象。"
      type="warning"
      :closable="false"
    />
    <p v-else-if="trialTeamReady" class="admin-muted" role="status">
      已预选团队的当前发布版本。请选择终端用户并手动发送消息。
    </p>
    <p
      v-if="autoTarget && autoTarget === targetKey && !sessionId"
      class="admin-muted"
      role="status"
    >
      已选择唯一可用的对话对象，请确认终端用户后发送。
    </p>
    <ElAlert
      v-if="sessionClosed"
      title="此会话已关闭，保留历史记录供查看。请新建会话继续对话；关闭不会撤销已执行的操作。"
      type="info"
      :closable="false"
    />
    <ElAlert
      v-else-if="closeUnconfirmed"
      title="关闭结果尚待核对。请读取会话状态，确认前不会再次关闭或提交新输入。"
      type="warning"
      :closable="false"
    />
    <div class="toolbar session-setup">
      <ElSelect
        v-described-by="formTipId + '-group-0'"
        :aria-describedby="formTipId + '-group-0'"
        v-model="endUserId"
        aria-label="终端用户"
        :disabled="
          !!sessionId || busy || readOnly || usersTask.busy.value || sessionsTask.busy.value
        "
        filterable
        placeholder="终端用户"
        @visible-change="onRestoreSelectionVisible"
        @change="cancelSessionRestore"
        style="width: 210px"
        ><ElOption
          v-for="user in endUsers"
          :key="user.id"
          :label="user.display_name || user.id"
          :value="user.id" /></ElSelect
      ><ElButton
        :disabled="
          busy || readOnly || !canCreate || usersTask.busy.value || sessionsTask.busy.value
        "
        @click="openUserDialog"
        >新增用户</ElButton
      ><ElSelect
        v-described-by="formTipId + '-group-0'"
        :aria-describedby="formTipId + '-group-0'"
        v-model="targetKey"
        :disabled="!!sessionId || busy || readOnly || sessionsTask.busy.value"
        filterable
        placeholder="选择智能体或团队"
        @change="markTargetTouched"
        @visible-change="onRestoreSelectionVisible"
        style="width: 250px"
        ><ElOptionGroup label="智能体"
          ><ElOption
            v-for="agent in agents"
            :key="agent.id"
            :label="agent.name"
            :value="'agent:' + agent.id" /></ElOptionGroup
        ><ElOptionGroup label="团队"
          ><ElOption
            v-for="team in teams"
            :key="team.id"
            :label="team.name"
            :value="'team:' + team.id" /></ElOptionGroup></ElSelect
      ><ElButton
        :disabled="
          busy ||
          sessionsTask.busy.value ||
          !!pendingSession ||
          !!sessionStorageError ||
          !!pendingInput ||
          !!pendingSteering
        "
        @click="newSession"
        >新会话</ElButton
      ><ElSelect
        v-described-by="formTipId + '-group-0'"
        :aria-describedby="formTipId + '-group-0'"
        :model-value="selectedSession"
        :disabled="
          busy ||
          sessionsTask.busy.value ||
          !!pendingSession ||
          !!sessionStorageError ||
          !!pendingInput ||
          !!pendingSteering
        "
        placeholder="历史会话"
        style="width: 250px"
        filterable
        @change="loadSession"
        @visible-change="onRestoreSelectionVisible"
        ><ElOption
          v-for="session in sessions"
          :key="session.session_id"
          :value="session.session_id"
          :label="session.summary || session.session_id" /></ElSelect
      ><ElSelect
        :model-value="sessionScope"
        placeholder="历史范围"
        aria-label="历史范围"
        :disabled="busy || sessionsTask.busy.value || userOpen"
        @change="refreshSessions"
      >
        <ElOption label="全部会话" value="all" /><ElOption
          label="当前用户与对象"
          value="selected"
        />
      </ElSelect>
      <ElButton :disabled="busy || sessionsTask.busy.value" @click="refreshSessions()"
        >刷新会话列表</ElButton
      >
      <ElButton v-if="sessionId" :disabled="busy" @click="readSessionState">读取会话状态</ElButton>
      <ElButton
        v-if="sessionId"
        v-permission="['foundation.sessions.cancel']"
        :disabled="busy || sessionReadOnly || !canCancel"
        @click="closeSession"
        >关闭会话</ElButton
      >
      <ElTag v-if="sessionId" role="status">{{ connectionLabel }}</ElTag>

      <FieldTip :id="formTipId + '-group-0'"
        >先选择终端用户和智能体或团队，再发送消息；加载历史会话会切换会话上下文，已有会话期间不能更换用户或对话对象。</FieldTip
      >
    </div>
    <ElTabs @tab-change="refreshPanels"
      ><ElTabPane label="对话">
        <p v-if="pendingItems.length" class="pending-summary" role="status">
          {{ pendingItems.length }} 项待处理 · 完成下方回复后继续
        </p>
        <ElSelect
          v-described-by="formTipId + '-group-1'"
          :aria-describedby="formTipId + '-group-1'"
          v-if="pendingItems.length > 1"
          v-model="pendingSelection"
          :disabled="busy"
          placeholder="选择需要回复的任务"
          aria-label="等待任务"
        >
          <ElOption
            v-for="(item, index) in pendingItems"
            :key="item.execution_id"
            :value="item.execution_id"
            :label="`待办 ${index + 1}：${('question' in item && item.question) || item.card?.title || '需要你的回复'}`"
          />
        </ElSelect>
        <FieldTip v-if="pendingItems.length > 1" :id="formTipId + '-group-1'"
          >选择需要处理的待办，回复会发送给当前选中的待办。</FieldTip
        >

        <template
          v-for="item in pendingItems"
          :key="`${item.execution_id}:${'tool_wait' in item ? item.tool_wait?.generation : item.checkpoint_revision}`"
        >
          <BrowserConnectionCard
            v-if="
              !readOnly &&
              item.interaction_type === 'tool_input' &&
              item.card?.type === 'browser_connection' &&
              item.execution_id === pending?.execution_id
            "
            :session="sessionId"
            :request="String(item.card.connection_request_id ?? '')"
            :title="item.card.title"
            :reason="String(item.card.reason ?? '')"
          />
          <ElCard
            v-else-if="
              item.interaction_type === 'tool_input' && item.card?.type !== 'browser_connection'
            "
            v-show="item.execution_id === pending?.execution_id"
            class="pending-card"
          >
            <h3>{{ item.card?.title ?? '需要你的回复' }}</h3>
            <ToolInputForm
              ref="toolInputForms"
              :pending="item"
              :busy="busy || readOnly"
              @respond="submitHitl"
            />
            <ElButton :disabled="busy || !canCancel" @click="stop(item.execution_id)"
              >停止此等待任务</ElButton
            >
          </ElCard>
        </template>
        <ElCard
          v-if="pending && pending.interaction_type !== 'tool_input'"
          :key="pendingKey"
          class="pending-card"
          ><h3>{{ pendingTitle }}</h3>
          <MarkdownContent v-if="'plan' in pending" :content="pending.plan" />
          <p v-else>{{ pending.question || pending.card?.body || pending.card?.message }}</p>
          <ElForm
            @submit.prevent="respond"
            :disabled="busy || readOnly"
            scroll-to-error
            @keydown.capture="guardFormEnter"
            ><ElInput
              v-described-by="formTipId + '-group-2' + '-' + encodeURIComponent(String(pendingKey))"
              :aria-describedby="
                formTipId + '-group-2' + '-' + encodeURIComponent(String(pendingKey))
              "
              v-model="pendingText"
              type="textarea"
              :rows="3"
              placeholder="输入回复或评审意见"
            />
            <div class="toolbar">
              <ElButton
                v-if="pending.interaction_type === 'plan_review'"
                type="primary"
                :loading="busy"
                @click="respond('approve')"
                >批准计划</ElButton
              ><ElButton
                v-if="pending.interaction_type === 'plan_review'"
                :loading="busy"
                @click="respond('keep_planning')"
                >继续规划</ElButton
              ><ElButton v-else type="primary" native-type="submit" :loading="busy"
                >提交回复</ElButton
              ><ElButton
                v-for="action in pendingActions"
                :key="action.id || action.label"
                :loading="busy"
                @click="respond(action.id || action.type)"
                >{{ action.label }}</ElButton
              >
            </div>
            <FieldTip :id="formTipId + '-group-2' + '-' + encodeURIComponent(String(pendingKey))"
              >填写所选待办需要的回复或计划评审意见，点击对应操作提交。</FieldTip
            >
          </ElForm>
          <ElButton :disabled="busy || !canCancel" @click="stop(pending.execution_id)"
            >停止此等待任务</ElButton
          >
        </ElCard>
        <div class="debug-messages">
          <ElButton v-if="history?.has_more" :disabled="busy" @click="older">加载较早消息</ElButton
          ><ElCard v-for="message in messages" :key="message.message_id" class="debug-message"
            ><div class="message-heading">
              <strong>{{
                message.role === 'user'
                  ? '用户'
                  : message.role === 'assistant'
                    ? '智能体'
                    : message.role
              }}</strong
              ><ElTag v-if="message.metadata?.phase" size="small" type="info">{{
                message.metadata.phase === 'final'
                  ? '最终回答'
                  : message.metadata.phase === 'commentary'
                    ? '公开过程'
                    : message.metadata.phase
              }}</ElTag
              ><small>{{ formatDateTime(message.created_at) }}</small>
            </div>
            <SessionMessageContent
              :session="sessionId"
              :message="message"
              @citation="citationSelection = $event" /></ElCard
          ><ElEmpty
            v-if="!messages.length && !busy && !readError"
            :description="
              sessionId
                ? '本会话暂无消息，可在下方开始对话'
                : '选择终端用户和智能体或团队，发送第一条消息'
            "
          />
        </div>
        <ElForm
          class="debug-composer"
          @submit.prevent="send"
          :disabled="busy || readOnly"
          scroll-to-error
          @keydown.capture="guardFormEnter"
          ><ElInput
            v-described-by="'chat-compose-help'"
            v-model="input"
            type="textarea"
            :rows="4"
            placeholder="输入消息"
            @keydown.ctrl.enter.prevent="send"
            aria-describedby="chat-compose-help"
          />
          <p id="chat-compose-help" class="admin-field-help">
            Enter 换行，Ctrl+Enter 发送。新会话可重新选择终端用户和智能体。
          </p>
          <div class="toolbar">
            <ElButton
              type="primary"
              native-type="submit"
              :loading="busy"
              :disabled="
                readOnly ||
                !!deferredUpload ||
                (!sessionId && !canCreate) ||
                (!input.trim() && !attachments.length && !mediaIds.length)
              "
              >发送</ElButton
            ><ElButton :disabled="!runId || busy || !canCancel" @click="stop()">停止运行</ElButton
            ><ElUpload
              v-described-by="formTipId + '-upload'"
              :aria-describedby="formTipId + '-upload'"
              ref="attachmentUpload"
              :auto-upload="false"
              :show-file-list="false"
              :disabled="busy || readOnly || !!deferredUpload"
              :on-change="uploadAttachment"
              ><ElButton :disabled="busy || readOnly || !!deferredUpload"
                >附加文件</ElButton
              ></ElUpload
            ><ElUpload
              v-described-by="formTipId + '-upload'"
              :aria-describedby="formTipId + '-upload'"
              ref="imageUpload"
              :auto-upload="false"
              :show-file-list="false"
              :disabled="busy || readOnly || !!deferredUpload"
              :on-change="uploadImage"
              accept="image/*"
              ><ElButton :disabled="busy || readOnly || !!deferredUpload"
                >附加图片</ElButton
              ></ElUpload
            >
            <FieldTip :id="formTipId + '-upload'"
              >选中文件或图片后会上传到当前会话，发送消息时附带已选内容；图片输入需要对话模型支持视觉。</FieldTip
            >
            <ElCheckbox
              v-described-by="formTipId + '-group-3'"
              :aria-describedby="formTipId + '-group-3'"
              :model-value="collaboration?.selected_active ?? false"
              :disabled="!sessionId || busy || readOnly"
              @change="setPlan"
              >规划模式</ElCheckbox
            ><ElTag
              v-for="attachment in attachments"
              :key="attachment.id"
              :closable="!busy && !readOnly"
              @close="attachments = attachments.filter((a) => a.id !== attachment.id)"
              >{{ attachment.name }}</ElTag
            ><ElTag
              v-for="media in mediaIds"
              :key="media"
              :closable="!busy && !readOnly"
              @close="mediaIds = mediaIds.filter((i) => i !== media)"
              >图片 {{ media }}</ElTag
            >

            <FieldTip :id="formTipId + '-group-3'"
              >启用计划协作后，后续任务按协作方式推进；需要先进入会话。</FieldTip
            >
          </div></ElForm
        ></ElTabPane
      >
      <ElTabPane label="运行过程">
        <FeedbackNotice v-if="budgetError" :title="budgetError" type="error" :closable="false" />
        <p v-if="budgetError && budget" class="admin-field-help">
          刷新失败，以下保留上次读取的额度观察。
        </p>
        <p v-if="budgetStopReason" role="status">停止原因：{{ budgetStopReason }}</p>
        <details class="secondary-panel">
          <summary>运行额度观察</summary>
          <div class="toolbar">
            <ElSelect v-model="budgetRunId" placeholder="选择运行" aria-label="额度观察运行">
              <ElOption
                v-for="option in budgetRuns"
                :key="option.value"
                :value="option.value"
                :label="option.label"
              />
            </ElSelect>
            <ElButton
              :disabled="!budgetRunId || budgetLoading"
              :loading="budgetLoading"
              @click="readBudget"
              >读取运行额度</ElButton
            >
          </div>
          <ChatRunBudgetView v-if="budget" :budget="budget" />
        </details>
        <h3>公开运行过程</h3>
        <ElTable
          table-layout="auto"
          :data="processes"
          empty-text="当前会话尚无执行过程，发起对话后可在这里查看"
          ><ElTableColumn
            min-width="140"
            show-overflow-tooltip
            prop="type"
            label="类型" /><ElTableColumn
            min-width="200"
            show-overflow-tooltip
            prop="presentation.title"
            label="步骤" /><ElTableColumn
            width="128"
            show-overflow-tooltip
            prop="status"
            label="状态" /><ElTableColumn
            min-width="280"
            show-overflow-tooltip
            prop="presentation.summary"
            label="内容"
        /></ElTable>
        <details class="secondary-panel">
          <summary>运行中补充指令</summary>
          <ElInput
            v-described-by="formTipId + '-group-4'"
            :aria-describedby="formTipId + '-group-4'"
            v-model="steerText"
            type="textarea"
          />
          <FieldTip :id="formTipId + '-group-4'"
            >填写对当前运行的补充要求，确认要补充的本次运行后提交。</FieldTip
          >
          <ElButton :disabled="readOnly || !runId || busy || !steerText.trim()" @click="steer()"
            >提交补充</ElButton
          >
          <div v-if="pendingSteering" role="status">
            <p>补充结果尚未确认。请保留原补充，核对状态或按原内容重试。</p>
            <p>绑定提交时的运行</p>
            <ElButton :disabled="busy" @click="querySteering">核对补充状态</ElButton>
            <ElButton :disabled="busy || !canRun" @click="steer(true)">重试原补充</ElButton>
          </div>
          <div v-if="confirmedSteering" role="status">
            <p>补充状态：{{ steeringLabels[confirmedSteering.item.status] }}</p>
            <ElButton :disabled="busy || !!pendingSteering" @click="querySteering"
              >刷新补充状态</ElButton
            >
            <details>
              <summary>查看原补充</summary>
              <p>{{ confirmedSteering.item.text }}</p>
              <p>绑定运行：{{ confirmedSteering.request.run }}</p>
            </details>
            <ElButton
              v-if="!confirmedSteering.accepting && confirmedSteering.item.status !== 'CONSUMED'"
              :disabled="busy || readOnly"
              @click="appendSteeringToMessage"
              >追加到下一条消息</ElButton
            >
          </div>
        </details>
        <RouterLink v-if="sessionId" :to="'/admin/chat/sessions/' + sessionId + '/trace'"
          >打开完整运行追踪</RouterLink
        ></ElTabPane
      >
      <ElTabPane label="计划与目标"
        ><ElDescriptions v-if="goal?.goal" :column="1" border
          ><ElDescriptionsItem label="目标">{{ goal.goal.objective }}</ElDescriptionsItem
          ><ElDescriptionsItem label="状态">{{ goal.goal.state }}</ElDescriptionsItem
          ><ElDescriptionsItem label="轮次"
            >{{ goal.goal.rounds_started }} / {{ goal.goal.max_rounds }}</ElDescriptionsItem
          ></ElDescriptions
        ><ElForm
          label-position="top"
          @submit.prevent
          :disabled="busy || readOnly"
          scroll-to-error
          @keydown.capture="guardFormEnter"
          ><ElFormItem label="目标"
            ><ElInput
              v-described-by="formTipId + '-field-0'"
              :aria-describedby="formTipId + '-field-0'"
              v-model="objective"
              type="textarea"
            />
            <FieldTip :id="formTipId + '-field-0'"
              >描述希望持续推进的目标，内容应包含可判断的完成条件。</FieldTip
            > </ElFormItem
          ><ElFormItem label="最大轮次"
            ><ElInputNumber
              v-described-by="formTipId + '-field-1'"
              :aria-describedby="formTipId + '-field-1'"
              v-model="maxRounds"
              :min="1"
              :max="1024"
            />
            <FieldTip :id="formTipId + '-field-1'"
              >限制持续目标的推进轮次，填写 1–1,024 的整数。</FieldTip
            >
          </ElFormItem>
          <div class="actions">
            <ElButton :disabled="!sessionId || busy || readOnly" @click="createGoal"
              >创建目标</ElButton
            ><ElButton :disabled="readOnly || !goal?.goal || busy" @click="controlGoal('pause')"
              >暂停</ElButton
            ><ElButton :disabled="readOnly || !goal?.goal || busy" @click="controlGoal('resume')"
              >恢复</ElButton
            >
          </div></ElForm
        ></ElTabPane
      >
      <ElTabPane label="成果文件"
        ><ElTable
          table-layout="auto"
          :data="artifacts"
          empty-text="当前会话尚无成果文件，生成后会显示在这里"
          ><ElTableColumn
            min-width="200"
            show-overflow-tooltip
            prop="name"
            label="成果"
          /><ElTableColumn width="96" prop="revision" label="修订" /><ElTableColumn
            width="128"
            show-overflow-tooltip
            prop="status"
            label="状态"
          /><ElTableColumn min-width="360" label="文件"
            ><template #default="{ row }"
              ><div v-for="file in row.files" :key="file.file_id" class="toolbar">
                <span>{{ file.path }}</span
                ><ElButton link :disabled="busy" @click="download(row.id, file.file_id)"
                  >下载</ElButton
                ><ElButton link :disabled="busy" @click="preview(row.id, file.file_id)"
                  >预览</ElButton
                >
              </div></template
            ></ElTableColumn
          ></ElTable
        ></ElTabPane
      >
      <ElTabPane label="浏览器设备" name="browser"
        ><p class="muted">设备属于当前终端用户，可跨对话使用；独立配对只建立设备连接。</p>
        <FeedbackNotice v-if="browserError" :title="browserError" type="error" :closable="false" />
        <FeedbackNotice v-if="browserFeedback" :title="browserFeedback" type="info" />
        <p v-if="browserPairUnknown && !browserPairPending" role="status">
          配对操作已发出，结果尚未确认。请查看插件并刷新设备列表，不要重复配对。
        </p>
        <div class="toolbar">
          <ElButton
            :disabled="
              !sessionId || busy || browserBusy || browserPairPending || browserPairUnknown
            "
            @click="pairBrowser"
            >配对当前浏览器</ElButton
          ><ElButton :disabled="!sessionId || browserBusy" @click="loadDevices"
            >刷新设备与任务</ElButton
          >
        </div>
        <p class="muted">
          请先打开适配扩展并信任当前站点，再在插件中确认配对。已有任务的连接等待请使用对话中的连接卡。
        </p>
        <ElTable
          table-layout="auto"
          :data="devices?.items ?? []"
          empty-text="尚未连接浏览器设备，可在上方连接设备"
          ><ElTableColumn
            min-width="200"
            show-overflow-tooltip
            prop="label"
            label="设备"
          /><ElTableColumn
            min-width="200"
            show-overflow-tooltip
            prop="browser_id"
            label="浏览器"
          /><ElTableColumn width="128" label="连接"
            ><template #default="{ row }">{{
              row.connected ? '在线' : '离线'
            }}</template></ElTableColumn
          ><ElTableColumn width="112" label="默认设备"
            ><template #default="{ row }">{{
              devices?.selected_browser_id === row.browser_id ? '是' : '否'
            }}</template></ElTableColumn
          ><ElTableColumn width="112" class-name="admin-table-actions" label="操作"
            ><template #default="{ row }"
              ><ElButton
                link
                :disabled="busy || browserBusy || browserPairPending"
                @click="selectDevice(row.device_id)"
                >设为默认</ElButton
              ><ElButton
                link
                type="danger"
                :disabled="busy || browserBusy || browserPairPending"
                @click="revokeDevice(row.device_id)"
                >解绑</ElButton
              ></template
            ></ElTableColumn
          ></ElTable
        >
        <section class="browser-tasks" aria-label="浏览器任务">
          <h3>浏览器任务与回执</h3>
          <p v-if="!browserTasks.length && !browserBusy && !browserError" class="muted">
            暂无浏览器任务。
          </p>
          <ElCard v-for="item in browserTasks" :key="item.receipt.invocation" class="browser-task">
            <div class="toolbar">
              <strong>{{ item.receipt.operation }}</strong
              ><ElTag>回执状态：{{ item.receipt.status }}</ElTag>
            </div>
            <p>
              {{ item.receipt.operation === 'screenshot' ? '截图回执时间' : '回执创建时间' }}：
              <time :datetime="item.receipt.created_at">{{
                formatDateTime(item.receipt.created_at)
              }}</time>
            </p>
            <p>任务：{{ item.receipt.task }}</p>
            <p>调用：{{ item.receipt.invocation }}</p>
            <p>运行：{{ item.receipt.run }}</p>
            <p v-if="browserTaskError(item)">错误：{{ browserTaskError(item) }}</p>
            <p v-if="browserTaskCleanup(item)">清理回执：{{ browserTaskCleanup(item) }}</p>
            <SessionMediaImage
              v-for="image in item.content"
              :key="`${image.session_id}:${image.media_id}`"
              :session="image.session_id"
              :media="image.media_id"
              label="浏览器截图"
              alt="浏览器任务截图"
            />
            <ElButton
              :disabled="busy || browserBusy || browserPairPending"
              @click="cancelBrowser(item.receipt.invocation)"
              >请求取消</ElButton
            >
          </ElCard>
          <p class="muted">
            刷新可读取最新保存的状态；结果未知时仍需核对，取消与清理以服务返回的回执为准。
          </p>
        </section>
      </ElTabPane>
      <ElTabPane label="任务计划"
        ><TeamDebugPanel
          :session-id="sessionId"
          :update="workUpdate"
          :read-only="readOnly" /></ElTabPane
      ><ElTabPane label="任务"
        ><RouterLink
          v-if="sessionId"
          :to="{ path: '/admin/conversation-tasks', query: { sessionId } }"
          >管理当前会话任务</RouterLink
        ><HandoffCreation :session-id="sessionId" :disabled="!sessionId || busy || readOnly"
      /></ElTabPane> </ElTabs
    ><CitationDetail
      v-if="citationSelection"
      :selection="citationSelection"
      @close="citationSelection = undefined"
    /><ElDrawer
      v-model="userOpen"
      title="新增终端用户"
      :before-close="closeUser"
      append-to-body
      direction="rtl"
      :close-on-click-modal="false"
      :close-on-press-escape="!busy"
      :show-close="!busy"
      class="admin-drawer"
      size="680px"
      ><FeedbackNotice
        v-if="error"
        :title="error"
        type="error"
        :closable="false"
        role="alert"
      /><ElForm
        @submit.prevent="createUser"
        :disabled="busy || readOnly"
        scroll-to-error
        @keydown.capture="guardFormEnter"
        ><ElFormItem label="显示名称"
          ><ElInput
            v-described-by="formTipId + '-field-2'"
            :aria-describedby="formTipId + '-field-2'"
            v-model="userName"
          />
          <FieldTip :id="formTipId + '-field-2'"
            >用于识别终端用户；创建后可在调试会话中选择。</FieldTip
          >
        </ElFormItem></ElForm
      ><template #footer
        ><ElButton @click="closeUser" :disabled="busy || readOnly">取消</ElButton
        ><ElButton type="primary" :loading="busy" @click="createUser">创建</ElButton></template
      ></ElDrawer
    >
    <ElDrawer
      v-model="previewOpen"
      title="成果预览"
      size="80%"
      append-to-body
      direction="rtl"
      class="admin-drawer"
      ><FeedbackNotice
        v-if="error"
        :title="error"
        type="error"
        :closable="false"
        role="alert"
      /><iframe
        v-if="previewUrl"
        :src="previewUrl"
        title="成果预览"
        sandbox="allow-scripts"
        referrerpolicy="no-referrer"
        style="border: 0; width: 100%; height: 80vh"
      />
      <pre v-else class="readable">{{ previewText }}</pre>
    </ElDrawer>
  </AdminPage>
</template>
<script setup lang="ts">
import type {
  ChatSteeringCommand,
  ChatSteeringState
} from '@skillforge/shared/components/chatSteering'
import { HttpClientError } from '@skillforge/shared/utils/http'
import FeedbackNotice from '@skillforge/shared/feedback/FeedbackNotice.vue'
import ChatRunBudgetView from '@skillforge/shared/components/ChatRunBudgetView.vue'
import { executionStopReason, type RunBudget } from '@skillforge/shared/components/chatRunBudget'
import { formatDateTime } from '@skillforge/shared/utils/datetime'
import { vDescribedBy } from '@skillforge/shared/directives/describedBy'

import FieldTip from '@skillforge/shared/components/FieldTip.vue'

import { guardFormEnter } from '@skillforge/shared/admin'
import type { UploadFile, UploadInstance } from 'element-plus'
import { computed, onMounted, onScopeDispose, ref, shallowRef, useId, watch } from 'vue'
import { useRoute } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import { AdminPage, useTask, useUnsavedChanges, useDrawerClose } from '@skillforge/shared/admin'
import MarkdownContent from '@skillforge/shared/components/MarkdownContent.vue'
import {
  artifactPreviewOrigin,
  isArtifactPreviewUrlAllowed
} from '../config/artifact-preview-policy'
import { confirm, message } from '@skillforge/shared/feedback'
import TeamDebugPanel from '../components/TeamDebugPanel.vue'
import HandoffCreation from '../components/HandoffCreation.vue'
import ToolInputForm from '../components/ToolInputForm.vue'
import BrowserConnectionCard from '../components/BrowserConnectionCard.vue'
import SessionMediaImage from '../components/SessionMediaImage.vue'
import SessionMessageContent from '../components/SessionMessageContent.vue'
import CitationDetail from '../components/CitationDetail.vue'
import { browserBridge, connectionBridgeMessage } from '@skillforge/shared/utils/browserConnection'
import { CHAT_CONNECTION_STATES } from '@skillforge/shared/utils/stream'
import {
  api,
  type AgentItem,
  type Team,
  type ChatEndUserItem,
  type ChatHistoryResponse,
  type ChatHistoryMessage,
  type SessionAttachment,
  type ChatArtifact,
  type ChatProcessItem,
  type ChatCollaborationState,
  type StreamEvent,
  type ChatStreamRequest,
  type ChatTarget
} from '../api'
import {
  CHAT_GOAL_DEFAULT_MAX_ROUNDS,
  type ChatGoalStatus
} from '@skillforge/shared/components/chatGoal'
import type { BrowserDevices, BrowserTask } from '@skillforge/shared/components/chatBrowser'
import type { ChatHitlResponse } from '@skillforge/shared/types/chatCollaboration'
const route = useRoute()
const citationSelection = ref<{ session: string; message: string; citation: string }>()
const trialAgentId = typeof route.query.agent_id === 'string' ? route.query.agent_id : ''
const trialTeamId = typeof route.query.team_id === 'string' ? route.query.team_id : ''
const teamTrialChecked = ref(false)
const auth = useAuthStore()
const can = (permission: string) => auth.user?.permissions.includes(permission) === true
const canRun = computed(() => can('foundation.sessions.run'))
const canCancel = computed(() => can('foundation.sessions.cancel'))
const canCreate = computed(() => can('foundation.sessions.create'))
const canTeams = computed(() => can('foundation.teams.read'))
const trialChecked = ref(false)
const trialAgentUnavailable = computed(
  () =>
    trialChecked.value &&
    !sessionId.value &&
    !targetKey.value &&
    !!trialAgentId &&
    !agents.value.some((agent) => agent.id === trialAgentId)
)
const trialAgentReady = computed(
  () =>
    trialChecked.value &&
    !sessionId.value &&
    targetKey.value === `agent:${trialAgentId}` &&
    !!trialAgentId
)

const trialTeamUnavailable = computed(
  () =>
    teamTrialChecked.value &&
    !sessionId.value &&
    !targetKey.value &&
    !!trialTeamId &&
    !teams.value.some((team) => team.id === trialTeamId)
)
const trialTeamReady = computed(
  () =>
    teamTrialChecked.value &&
    !sessionId.value &&
    !!trialTeamId &&
    targetKey.value === `team:${trialTeamId}`
)

type PendingSession = { requestId: string; endUserId: string; targetKey: string }
const pendingSession = shallowRef<PendingSession>()
const deferredUpload = shallowRef<{ file: File; kind: 'file' | 'image' }>()
type PendingUpload = { session: string; uploadId: string; name: string; kind: 'file' | 'image' }
const pendingUploads = shallowRef<PendingUpload[]>([])
const sessionStorageError = ref('')
const creationActor = computed(() =>
  auth.user?.tenant_id && auth.user?.sub ? `${auth.user.tenant_id}:${auth.user.sub}` : ''
)
const creationKey = (actor: string) => `skillforge.chat-session-create:${actor}`
const uploadKey = (actor: string) => `skillforge.chat-upload:${actor}`
const inputKey = (actor: string) => `skillforge.chat-input:${actor}`
let pageDisposed = false
onScopeDispose(() => {
  pageDisposed = true
})
const sessionClosed = ref(false)
const pendingCloses = ref(new Set<string>())
const closeUnconfirmed = computed(() => pendingCloses.value.has(sessionId.value))
const sessionScope = ref('all')
let requestedSessionScope = 'all'
type FrozenMessage = {
  unknown: boolean
  replyDraftKey?: string
  draft: string
  body: ChatStreamRequest & { session_id: string; client_message_id: string }
}
const pendingInput = shallowRef<FrozenMessage>()
type FrozenSteering = {
  session: string
  run: string
  command: string
  text: string
  unknown: boolean
}
const pendingSteering = shallowRef<FrozenSteering>()
const confirmedSteering = shallowRef<{
  request: FrozenSteering
  item: ChatSteeringCommand
  accepting: boolean
}>()
const steerText = ref('')
const steeringLabels = { PENDING: '待纳入', INCLUDED: '已纳入', CONSUMED: '已消费' }
const sessionReadOnly = computed(
  () =>
    sessionClosed.value ||
    closeUnconfirmed.value ||
    !!pendingInput.value ||
    !!pendingSteering.value ||
    !!pendingSession.value ||
    !!sessionStorageError.value
)
const readOnly = computed(() => !canRun.value || sessionReadOnly.value)
const { busy, error, run } = useTask(),
  readError = ref(''),
  endUsers = ref<ChatEndUserItem[]>([]),
  agents = ref<AgentItem[]>([]),
  teams = ref<Team[]>([]),
  sessions = ref<Awaited<ReturnType<typeof api.chat.sessions>>>([]),
  endUserId = ref(''),
  targetKey = ref(''),
  sessionId = ref(''),
  selectedSession = ref(''),
  connection = ref('未连接'),
  input = ref(''),
  runId = ref(''),
  messages = ref<ChatHistoryMessage[]>([]),
  history = ref<ChatHistoryResponse>(),
  processes = ref<ChatProcessItem[]>([]),
  collaboration = ref<ChatCollaborationState>(),
  pendingItems = ref<ChatHistoryResponse['pending_interactions']>([]),
  pendingSelection = ref(''),
  pendingDrafts = ref<Record<string, string>>({}),
  attachments = ref<SessionAttachment[]>([]),
  mediaIds = ref<string[]>([])
const restoreSessionError = ref(false),
  restoringSession = ref(false)
let restoreEpoch = 0
const recentSessionKey = (actor: string) => `skillforge.chat-recent-session:${actor}`
const trialContext = trialAgentId || trialTeamId ? JSON.stringify([trialAgentId, trialTeamId]) : ''
function rememberSession(session?: string) {
  if (!creationActor.value) return
  try {
    if (session) sessionStorage.setItem(recentSessionKey(creationActor.value), session)
    else sessionStorage.removeItem(recentSessionKey(creationActor.value))
  } catch {
    // 最近会话只是导航偏好，存储故障不能影响已完成的会话操作。
  }
  try {
    const state = { ...window.history.state }
    if (session && trialContext)
      state.noeWiseTrialSession = { actor: creationActor.value, trialContext, session }
    else delete state.noeWiseTrialSession
    window.history.replaceState(state, '')
  } catch {
    // 当前历史条目不可写时，保持原会话操作结果。
  }
}
function cancelSessionRestore() {
  restoreEpoch++
  restoringSession.value = false
  restoreSessionError.value = false
}
function onRestoreSelectionVisible(visible: boolean) {
  if (visible) cancelSessionRestore()
}
function openUserDialog() {
  cancelSessionRestore()
  userOpen.value = true
}
watch(input, cancelSessionRestore, { flush: 'sync' })
watch(() => auth.identityVersion, cancelSessionRestore, { flush: 'sync' })
watch(
  () => auth.identityVersion,
  () => {
    pendingInput.value = undefined
    pendingSession.value = undefined
    pendingUploads.value = []
    deferredUpload.value = undefined
    sessionStorageError.value = ''
    if (!creationActor.value) return
    try {
      const frozen = sessionStorage.getItem(inputKey(creationActor.value))
      if (frozen) {
        const sent = JSON.parse(frozen) as FrozenMessage
        if (
          !sent?.body?.session_id ||
          !/^[0-9a-f-]{36}$/i.test(sent.body.client_message_id) ||
          typeof sent.draft !== 'string' ||
          !sent.body.context?.end_user_id ||
          (!sent.body.agent_id && !sent.body.team_id)
        )
          throw Error('invalid input')
        sent.unknown = true
        pendingInput.value = sent
        sessionId.value = sent.body.session_id
        selectedSession.value = sent.body.session_id
        endUserId.value = String(sent.body.context.end_user_id)
        targetKey.value = sent.body.team_id
          ? `team:${sent.body.team_id}`
          : `agent:${sent.body.agent_id}`
        if (!sent.body.hitl_response) input.value = sent.draft
      }
      const upload = sessionStorage.getItem(uploadKey(creationActor.value))
      if (upload) {
        const values = JSON.parse(upload) as PendingUpload[]
        if (
          !Array.isArray(values) ||
          values.some(
            (value) =>
              !value.session ||
              !value.name ||
              !/^[0-9a-f-]{36}$/i.test(value.uploadId) ||
              !['file', 'image'].includes(value.kind)
          )
        )
          throw Error('invalid upload')
        pendingUploads.value = values
      }
      const raw = sessionStorage.getItem(creationKey(creationActor.value))
      if (!raw) return
      const operation = JSON.parse(raw) as PendingSession
      if (
        !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(
          operation.requestId
        ) ||
        !operation.endUserId ||
        !/^(agent|team):.+$/.test(operation.targetKey)
      )
        throw Error('invalid')
      pendingSession.value = operation
      endUserId.value = operation.endUserId
      targetKey.value = operation.targetKey
    } catch {
      sessionStorageError.value = '无法读取原消息、会话创建或上传记录，请恢复浏览器存储后重新进入。'
    }
  },
  { immediate: true, flush: 'sync' }
)
let controller: AbortController | undefined
let readEpoch = 0
watch(
  sessionId,
  () => {
    citationSelection.value = undefined
  },
  { flush: 'sync' }
)
const budget = ref<RunBudget>(),
  budgetRunId = ref(''),
  budgetLoading = ref(false),
  budgetError = ref('')
let budgetEpoch = 0
const budgetRuns = computed(() => {
  const runs = new Map<string, string>()
  for (const activity of history.value?.activities ?? [])
    if (activity.node_type === 'agent_runtime' && typeof activity.node_id === 'string')
      runs.set(activity.node_id, `${activity.node_id} · ${activity.status ?? '状态待核实'}`)
  if (runId.value && !runs.has(runId.value)) runs.set(runId.value, `${runId.value} · 当前运行`)
  return Array.from(runs, ([value, label]) => ({ value, label }))
})
const budgetStopReason = computed(() => {
  const reason = history.value?.activities?.find((item) => item.node_id === budgetRunId.value)
    ?.render?.reason
  return typeof reason === 'string' && reason !== 'completed' && reason !== 'respond'
    ? executionStopReason(reason)
    : ''
})
watch(budgetRuns, (options) => {
  if (!options.some((option) => option.value === budgetRunId.value))
    budgetRunId.value = runId.value || options[0]?.value || ''
})
watch([sessionId, budgetRunId], () => {
  budgetEpoch++
  budget.value = undefined
  budgetError.value = ''
  budgetLoading.value = false
})
async function readBudget() {
  const session = sessionId.value,
    run = budgetRunId.value,
    epoch = ++budgetEpoch
  if (!session || !run) return
  budgetLoading.value = true
  budgetError.value = ''
  try {
    const value = await api.chat.runBudget(session, run)
    if (epoch !== budgetEpoch || session !== sessionId.value || run !== budgetRunId.value) return
    if (value.run_id !== run)
      throw new Error('返回的额度不属于所选运行，请点击“读取运行额度”重新查询。')
    budget.value = value
  } catch (cause) {
    if (cause instanceof HttpClientError) cause.claimFeedback()
    if (epoch === budgetEpoch)
      budgetError.value = cause instanceof Error ? cause.message : '运行额度读取失败。'
  } finally {
    if (epoch === budgetEpoch) budgetLoading.value = false
  }
}
const pending = computed(
  () =>
    pendingItems.value.find((item) => item.execution_id === pendingSelection.value) ??
    pendingItems.value[0]
)
const pendingKey = computed(() =>
  pending.value
    ? `${pending.value.execution_id}:${'tool_wait' in pending.value ? pending.value.tool_wait?.generation : pending.value.checkpoint_revision}`
    : ''
)
const pendingText = computed({
  get: () => pendingDrafts.value[pendingKey.value] ?? '',
  set: (value: string) => {
    pendingDrafts.value[pendingKey.value] = value
  }
})
const toolInputForms = ref<{ dirty: boolean }[]>([])
const hasSessionDraft = () =>
  !!(
    pendingSession.value ||
    deferredUpload.value ||
    pendingInput.value ||
    pendingSteering.value ||
    steerText.value ||
    input.value ||
    Object.values(pendingDrafts.value).some(Boolean) ||
    toolInputForms.value.some((form) => form.dirty) ||
    attachments.value.length ||
    mediaIds.value.length
  )
useUnsavedChanges({
  dirty: hasSessionDraft,
  busy: () =>
    busy.value ||
    !!pendingSession.value ||
    !!sessionStorageError.value ||
    !!pendingInput.value ||
    !!pendingSteering.value
})
const pendingActions = computed(() =>
  pending.value?.card && 'actions' in pending.value.card ? (pending.value.card.actions ?? []) : []
)
const connectionLabels: Record<string, string> = {
  [CHAT_CONNECTION_STATES.CONNECTED]: '已连接',
  [CHAT_CONNECTION_STATES.RECONNECTING]: '正在重连',
  [CHAT_CONNECTION_STATES.UNAVAILABLE]: '连接不可用'
}
const connectionLabel = computed(() => connectionLabels[connection.value] ?? connection.value)
const pendingTitle = computed(() =>
  pending.value && 'title' in pending.value
    ? pending.value.title
    : (pending.value?.card?.title ?? '需要你的回复')
)
const usersTask = useTask(),
  agentsTask = useTask(),
  teamsTask = useTask(),
  sessionsTask = useTask()
async function loadUsers() {
  await usersTask.run(async () => {
    endUsers.value = await api.chat.endUsers()
  })
}
const agentCatalogReady = ref(false),
  teamCatalogReady = ref(false)
const targetTouched = ref(false),
  autoTarget = ref('')
function markTargetTouched() {
  cancelSessionRestore()
  targetTouched.value = true
  autoTarget.value = ''
}
function selectOnlyTarget() {
  if (trialAgentId || trialTeamId || targetTouched.value || targetKey.value || sessionId.value)
    return
  if (
    (can('foundation.agents.read') && !agentCatalogReady.value) ||
    (canTeams.value && !teamCatalogReady.value)
  )
    return
  const candidates = [
    ...(can('foundation.agents.read') ? agents.value.map((agent) => `agent:${agent.id}`) : []),
    ...(canTeams.value ? teams.value.map((team) => `team:${team.id}`) : [])
  ]
  if (candidates.length === 1) {
    targetKey.value = candidates[0]!
    autoTarget.value = candidates[0]!
  }
}
async function loadAgents() {
  agentCatalogReady.value = false
  await agentsTask.run(async () => {
    agents.value = (await api.agents.list()).items.filter((agent) => agent.status === 'published')
    trialChecked.value = true
    if (
      trialAgentId &&
      !sessionId.value &&
      !targetKey.value &&
      agents.value.some((agent) => agent.id === trialAgentId)
    )
      targetKey.value = `agent:${trialAgentId}`
    agentCatalogReady.value = true
    selectOnlyTarget()
  })
}
async function loadTeams() {
  teamCatalogReady.value = false
  await teamsTask.run(async () => {
    teams.value = (await api.teams.list()).filter((team) => team.status === 'published')
    teamTrialChecked.value = true
    if (
      can('foundation.sessions.read') &&
      trialTeamId &&
      !sessionId.value &&
      !targetKey.value &&
      teams.value.some((team) => team.id === trialTeamId)
    )
      targetKey.value = `team:${trialTeamId}`
    teamCatalogReady.value = true
    selectOnlyTarget()
  })
}
async function loadSessions() {
  await refreshSessions(requestedSessionScope)
}
const directoryFailures = computed(() =>
  [
    { label: '终端用户', error: usersTask.error.value, retry: loadUsers },
    { label: '智能体', error: agentsTask.error.value, retry: loadAgents },
    { label: '团队', error: teamsTask.error.value, retry: loadTeams },
    { label: '历史会话', error: sessionsTask.error.value, retry: loadSessions }
  ].filter((entry) => entry.error)
)
const pageFeedback = computed(() =>
  [
    error.value || readError.value,
    ...directoryFailures.value.map((entry) => `${entry.label}读取失败：${entry.error}`)
  ]
    .filter(Boolean)
    .join('；')
)
onMounted(() => {
  if (can('foundation.sessions.read')) {
    if (pendingSession.value) void inspectSessionCreation(true)
    if (pendingInput.value) {
      void inspectInput()
      observe()
    }
    void loadUsers()
    void loadSessions()
    void restoreRecentSession()
  }
  if (can('foundation.agents.read')) void loadAgents()
  if (canTeams.value) void loadTeams()
})

onScopeDispose(() => {
  budgetEpoch++
  readEpoch++
  controller?.abort()
  resetBrowserView()
})
function mergeHistoryMessages(
  current: ChatHistoryMessage[],
  incoming: ChatHistoryMessage[],
  prepend = false
) {
  const merged = new Map<string, ChatHistoryMessage>()
  for (const message of prepend ? [...incoming, ...current] : [...current, ...incoming]) {
    const previous = merged.get(message.message_id)
    if (!previous || message.message_version >= previous.message_version)
      merged.set(message.message_id, message)
  }
  return [...merged.values()]
}
function applyHistory(value: ChatHistoryResponse) {
  const sent = pendingInput.value
  if (
    sent &&
    value.session_id === sent.body.session_id &&
    value.messages.some(
      (message) =>
        message.role === 'user' &&
        (message.message_id === sent.body.client_message_id ||
          message.metadata?.client_message_id === sent.body.client_message_id)
    )
  )
    acceptInput(sent)
  const current = history.value?.session_id === value.session_id ? history.value : undefined
  messages.value = mergeHistoryMessages(current ? messages.value : [], value.messages)
  history.value = {
    ...value,
    messages: messages.value,
    ...(current
      ? { has_more: current.has_more, next_before_message_id: current.next_before_message_id }
      : {})
  }
  pendingItems.value = value.pending_interactions
  if (!pendingItems.value.some((item) => item.execution_id === pendingSelection.value))
    pendingSelection.value = pendingItems.value[0]?.execution_id ?? ''
  processes.value = value.processes ?? []
  collaboration.value = value.collaboration
  const running = (value.activities ?? []).filter(
    (item) =>
      item.node_type === 'agent_runtime' &&
      item.status &&
      ![
        'completed',
        'failed',
        'cancelled',
        'outcome_unknown',
        'terminated',
        'waiting_hitl'
      ].includes(item.status)
  )
  runId.value =
    running.find((item) => item.node_id === runId.value)?.node_id ?? running[0]?.node_id ?? ''
}
async function refreshHistory() {
  const id = sessionId.value,
    epoch = ++readEpoch
  if (!id) return
  try {
    const value = await api.chat.history(id)
    if (epoch === readEpoch && id === sessionId.value) {
      applyHistory(value)
      readError.value = ''
    }
  } catch (e) {
    if (e instanceof HttpClientError) e.claimFeedback()
    if (epoch === readEpoch) readError.value = e instanceof Error ? e.message : '读取消息失败'
  }
}
function observe() {
  controller?.abort()
  if (!sessionId.value) return
  controller = new AbortController()
  const id = sessionId.value,
    signal = controller.signal
  void api.chat
    .observeSession(
      { sessionId: id, afterEventId: history.value?.event_cursor, signal },
      {
        onConnection: (state) => {
          if (id === sessionId.value && !signal.aborted) connection.value = state
        },
        onHistory: (_id, value) => {
          if (id === sessionId.value && !signal.aborted) applyHistory(value as ChatHistoryResponse)
        },
        onEvent: (event) => {
          if (id === sessionId.value && !signal.aborted) onEvent(event)
        }
      }
    )
    .catch((e) => {
      if (e instanceof HttpClientError) e.claimFeedback()
      if (!signal.aborted) readError.value = e instanceof Error ? e.message : '连接中断'
    })
}
const workUpdate = ref({ workId: '', sequence: 0 })
function onEvent(event: StreamEvent) {
  const data = event.data
  if (event.event === 'message.delta') {
    const id = String(data.message_id ?? '')
    if (!id) return
    const version = Number(data.message_version ?? 0),
      index = messages.value.findIndex((m) => m.message_id === id)
    const current = messages.value[index]
    if (current && current.message_version > version) return
    const value: ChatHistoryMessage = {
      message_id: id,
      message_version: version,
      references: [],
      role: String(data.role ?? 'assistant') as ChatHistoryMessage['role'],
      content: String(data.content ?? ''),
      created_at: String(data.created_at ?? new Date().toISOString()),
      metadata: { ...current?.metadata, ...data }
    }
    if (index >= 0) messages.value[index] = value
    else messages.value.push(value)
  } else if (
    event.event === 'execution.accepted' ||
    event.event === 'run.started' ||
    event.event === 'run.resumed'
  )
    runId.value = String(data.run_id ?? '')
  else if (event.event === 'work.updated' && typeof data.work_id === 'string')
    workUpdate.value = { workId: data.work_id, sequence: workUpdate.value.sequence + 1 }
  else if (event.event === 'collaboration.updated')
    collaboration.value = data as unknown as ChatCollaborationState
  else if (
    event.event.startsWith('process.') &&
    typeof data.process_id === 'string' &&
    data.presentation
  ) {
    const next = data as unknown as ChatProcessItem
    const current = processes.value.find((item) => item.process_id === next.process_id)
    if (!current || next.updated_sequence > current.updated_sequence) {
      if (!(
        current &&
        ['failed', 'outcome_unknown'].includes(current.status) &&
        ['succeeded', 'cancelled'].includes(next.status)
      )) {
        const incoming = current
          ? {
              ...next,
              sequence: current.sequence,
              started_at: current.started_at ?? next.started_at
            }
          : next
        processes.value = [
          ...processes.value.filter((item) => item.process_id !== next.process_id),
          incoming
        ].sort((left, right) => left.sequence - right.sequence || left.ordinal - right.ordinal)
      }
    }
    if (event.event === 'process.finished') void refreshHistory()
  } else if (
    event.event === 'execution.completed' ||
    event.event === 'hitl.required' ||
    event.event === 'interaction.resolved'
  ) {
    if (event.event === 'execution.completed' && data.run_id === runId.value) runId.value = ''
    void refreshHistory()
  } else if (event.event === 'error') readError.value = String(data.message ?? '运行失败')
}
// 创建会话与发送消息使用同一显式选择，不从 Session 或名称推断目标。
function selectedTarget(): ChatTarget {
  const [kind, id] = targetKey.value.split(':')
  if (!id || (kind !== 'agent' && kind !== 'team')) throw Error('请选择终端用户及智能体或团队')
  return kind === 'team' ? { team_id: id } : { agent_id: id }
}
async function ensureSession() {
  if (readOnly.value) throw Error('当前会话只读，请读取会话状态或确认执行权限')
  if (sessionId.value) {
    if (!endUserId.value || !targetKey.value)
      throw Error('会话缺少已确认的用户或目标绑定，仅可查看历史')
    return
  }
  if (!canCreate.value) throw Error('当前账号没有会话创建权限，请选择已有会话')
  if (!endUserId.value || !targetKey.value) throw Error('请选择终端用户及智能体或团队')
  const actor = creationActor.value,
    identity = auth.identityVersion
  if (!actor) throw Error('当前身份不可用，请重新登录')
  const operation = {
    requestId: crypto.randomUUID(),
    endUserId: endUserId.value,
    targetKey: targetKey.value
  }
  try {
    sessionStorage.setItem(creationKey(actor), JSON.stringify(operation))
  } catch {
    sessionStorageError.value = '无法保存会话创建记录，请恢复浏览器存储后重试。'
    throw Error(sessionStorageError.value)
  }
  pendingSession.value = operation
  try {
    const result = await api.chat.createSessionWithReceipt(
      operation.requestId,
      operation.endUserId,
      selectedTarget()
    )
    if (pageDisposed || creationActor.value !== actor || auth.identityVersion !== identity)
      throw Error('当前身份已变更，请重新核对会话创建结果')
    if (!result || result.request_id !== operation.requestId || !result.session_id)
      throw Error('会话创建回执不完整，请核对原请求')
    acceptSessionCreation(operation, result.session_id, actor)
  } catch (cause) {
    if (
      !pageDisposed &&
      creationActor.value === actor &&
      auth.identityVersion === identity &&
      cause instanceof HttpClientError &&
      cause.code === 'SESSION_CREATE_REJECTED'
    ) {
      sessionStorage.removeItem(creationKey(actor))
      pendingSession.value = undefined
    }
    throw cause
  }
}
function acceptSessionCreation(operation: PendingSession, session: string, actor: string) {
  sessionStorage.removeItem(creationKey(actor))
  pendingSession.value = undefined
  endUserId.value = operation.endUserId
  targetKey.value = operation.targetKey
  sessionId.value = session
  selectedSession.value = session
  rememberSession(session)
  observe()
}
async function inspectSessionCreation(automatic = false) {
  const operation = pendingSession.value,
    actor = creationActor.value,
    identity = auth.identityVersion
  if (!operation || !actor) return
  await run(async () => {
    const result = await api.chat.sessionCreationResult(operation.requestId)
    if (
      pageDisposed ||
      creationActor.value !== actor ||
      auth.identityVersion !== identity ||
      pendingSession.value !== operation
    )
      return
    if (!result || result.request_id !== operation.requestId || !result.session_id) {
      if (!automatic) message.warning('尚未查到原创建结果，请继续核对，不要重新创建。')
      return
    }
    acceptSessionCreation(operation, result.session_id, actor)
    message.success(
      deferredUpload.value
        ? '会话创建已确认，可继续上传所选文件。'
        : '会话创建已确认，请继续发送或重新选择文件。'
    )
  })
}
/** 仅清理已确认的原消息快照；恢复期间的其他草稿资源不属于此回执。 */
function acceptInput(sent: FrozenMessage) {
  if (pendingInput.value !== sent) return
  sessionStorage.removeItem(inputKey(creationActor.value))
  pendingInput.value = undefined
  const response = sent.body.hitl_response
  if (response) {
    if (sent.replyDraftKey && pendingDrafts.value[sent.replyDraftKey] === sent.draft)
      delete pendingDrafts.value[sent.replyDraftKey]
    pendingItems.value = pendingItems.value.filter(
      (item) =>
        !('execution_id' in response) ||
        item.execution_id !== response.execution_id ||
        !samePendingResponse(item, response)
    )
    return
  }
  if (input.value === sent.draft) input.value = ''
  const files = new Set(sent.body.context?.attachment_ids ?? [])
  attachments.value = attachments.value.filter((file) => !files.has(file.id))
  const images = new Set(
    (sent.body.content ?? []).filter((part) => part.type === 'image').map((part) => part.media_id)
  )
  mediaIds.value = mediaIds.value.filter((id) => !images.has(id))
}
async function dispatchInput(sent: FrozenMessage) {
  const actor = creationActor.value,
    identity = auth.identityVersion
  if (!actor || pageDisposed) throw Error('当前身份不可用，请重新登录')
  // 在派发前保存；页面重建不能把在途请求变成一次新消息。
  try {
    sessionStorage.setItem(inputKey(actor), JSON.stringify({ ...sent, unknown: true }))
  } catch {
    throw Error('无法保存原消息记录，消息尚未发送；请恢复浏览器存储后重试。')
  }
  pendingInput.value = sent
  const current = () =>
    !pageDisposed &&
    actor === creationActor.value &&
    identity === auth.identityVersion &&
    pendingInput.value === sent
  let result: Awaited<ReturnType<typeof api.chat.submit>>
  try {
    result = await api.chat.submit(sent.body)
  } catch (cause) {
    // 与现有会话关闭入口一致，仅首次正式派发前拒绝释放输入；重试拒绝不证明首写未受理。
    if (!current()) return
    if (
      !sent.unknown &&
      cause instanceof HttpClientError &&
      [400, 401, 403, 404, 422].includes(cause.status) &&
      ['UNAUTHORIZED', 'FORBIDDEN', 'NOT_FOUND', 'VALIDATION_ERROR'].includes(cause.code ?? '')
    ) {
      sessionStorage.removeItem(inputKey(actor))
      pendingInput.value = undefined
    } else sent.unknown = true
    throw cause
  }
  if (!current()) return
  if (result.status === 'quota_exhausted') {
    if (!sent.unknown) {
      sessionStorage.removeItem(inputKey(actor))
      pendingInput.value = undefined
    }
    message.warning(result.message || '本月额度已用尽。')
    return
  }
  acceptInput(sent)
  await refreshHistory()
}
async function send() {
  if (pendingInput.value || deferredUpload.value) return
  await run(async () => {
    const text = input.value.trim()
    if (!text && !attachments.value.length && !mediaIds.value.length) return
    await ensureSession()
    await dispatchInput({
      unknown: false,
      draft: input.value,
      body: {
        session_id: sessionId.value,
        ...selectedTarget(),
        content: [
          ...(text ? [{ type: 'text' as const, text }] : []),
          ...mediaIds.value.map((media_id) => ({ type: 'image' as const, media_id }))
        ],
        client_message_id: crypto.randomUUID(),
        context: {
          end_user_id: endUserId.value,
          attachment_ids: attachments.value.map((file) => file.id)
        }
      }
    })
  })
}
async function retryInput() {
  if (!canRun.value) return
  const sent = pendingInput.value
  if (sent) await run(() => dispatchInput(sent))
}
async function inspectInput() {
  const sent = pendingInput.value,
    actor = creationActor.value,
    identity = auth.identityVersion
  if (!sent) return
  await run(async () => {
    const value = await api.chat.history(sent.body.session_id)
    if (value.session_id !== sent.body.session_id) throw Error('读取结果不属于原会话，请重新核对')
    if (
      pageDisposed ||
      actor !== creationActor.value ||
      identity !== auth.identityVersion ||
      pendingInput.value !== sent ||
      sessionId.value !== sent.body.session_id
    )
      return
    applyHistory(value)
    if (pendingInput.value === sent)
      message.warning(
        sent.body.hitl_response
          ? '已读取当前待办，尚无原回复的精确受理记录。请保留原等待并重试原回复核对。'
          : '尚未查到原消息的受理记录，请继续核对，或显式重试原请求。'
      )
  })
}
async function confirmSessionSwitch() {
  if (pendingSession.value || sessionStorageError.value)
    throw Error('会话创建结果尚未确认，请先核对原请求')
  if (pendingInput.value) throw Error('发送结果尚未确认，请先核对原消息受理状态')
  if (pendingSteering.value) throw Error('补充结果尚未确认，请先核对原补充状态')
  return (
    !hasSessionDraft() ||
    (await confirm({
      title: '切换会话',
      content: '当前有未发送的消息、回复、补充或附件，切换会话将丢弃这些内容。',
      confirmText: '丢弃并切换',
      cancelText: '继续编辑'
    }))
  )
}
function clearSessionView() {
  citationSelection.value = undefined
  controller?.abort()
  readEpoch++
  runId.value = ''
  sessionClosed.value = false
  messages.value = []
  history.value = undefined
  pendingItems.value = []
  pendingDrafts.value = {}
  pendingSelection.value = ''
  processes.value = []
  collaboration.value = undefined
  input.value = ''
  deferredUpload.value = undefined
  attachments.value = []
  mediaIds.value = []
  readError.value = ''
  connection.value = '未连接'
  goal.value = undefined
  artifacts.value = []
  devices.value = undefined
  resetBrowserView()
  objective.value = ''
  steerText.value = ''
  confirmedSteering.value = undefined
}
async function newSession() {
  cancelSessionRestore()
  const actor = creationActor.value,
    identity = auth.identityVersion,
    epoch = restoreEpoch
  await run(async () => {
    if (!(await confirmSessionSwitch())) return
    if (
      !actor ||
      pageDisposed ||
      actor !== creationActor.value ||
      identity !== auth.identityVersion ||
      epoch !== restoreEpoch
    )
      return
    rememberSession()
    clearSessionView()
    sessionId.value = ''
    selectedSession.value = ''
  })
}
async function loadSession(target: string) {
  cancelSessionRestore()
  if (!target || target === sessionId.value) return
  const actor = creationActor.value,
    identity = auth.identityVersion,
    epoch = restoreEpoch
  const current = () =>
    !!actor &&
    !pageDisposed &&
    actor === creationActor.value &&
    identity === auth.identityVersion &&
    epoch === restoreEpoch
  await run(async () => {
    if (!(await confirmSessionSwitch())) return
    if (!current()) return
    citationSelection.value = undefined
    // 新会话读取成功后一次切换，失败时保留当前身份、内容及未发送输入。
    const [value, detail] = await Promise.all([
      api.chat.history(target),
      api.chat.sessionDetail(target)
    ])
    if (!current()) return
    if (value.session_id !== target || detail.session.session_id !== target)
      throw Error('会话读取结果不匹配')
    clearSessionView()
    sessionId.value = target
    selectedSession.value = target
    rememberSession(target)
    endUserId.value = detail.end_user_id ?? ''
    targetKey.value = detail.session.team_id
      ? `team:${detail.session.team_id}`
      : detail.target_agent_id
        ? `agent:${detail.target_agent_id}`
        : ''
    sessionClosed.value = detail.closed
    applyHistory(value)
    observe()
  })
}
async function restoreRecentSession() {
  const actor = creationActor.value,
    identity = auth.identityVersion
  if (
    !actor ||
    !can('foundation.sessions.read') ||
    sessionId.value ||
    pendingSession.value ||
    pendingUploads.value.length ||
    sessionStorageError.value ||
    hasSessionDraft() ||
    userOpen.value
  )
    return
  let target: string | null
  try {
    target = sessionStorage.getItem(recentSessionKey(actor))
    if (trialContext) {
      const marker = window.history.state?.noeWiseTrialSession
      if (
        marker?.actor !== actor ||
        marker?.trialContext !== trialContext ||
        marker?.session !== target
      )
        return
    }
  } catch {
    return
  }
  if (!target) return
  const epoch = ++restoreEpoch
  restoringSession.value = true
  restoreSessionError.value = false
  const current = () =>
    !pageDisposed &&
    epoch === restoreEpoch &&
    creationActor.value === actor &&
    auth.identityVersion === identity &&
    !sessionId.value &&
    !pendingSession.value &&
    !pendingUploads.value.length &&
    !sessionStorageError.value &&
    !hasSessionDraft() &&
    !userOpen.value
  try {
    const [value, detail] = await Promise.all([
      api.chat.history(target),
      api.chat.sessionDetail(target)
    ])
    if (!current()) return
    if (value.session_id !== target || detail.session.session_id !== target)
      throw Error('会话读取结果不匹配')
    clearSessionView()
    sessionId.value = target
    selectedSession.value = target
    endUserId.value = detail.end_user_id ?? ''
    targetKey.value = detail.session.team_id
      ? `team:${detail.session.team_id}`
      : detail.target_agent_id
        ? `agent:${detail.target_agent_id}`
        : ''
    sessionClosed.value = detail.closed
    applyHistory(value)
    observe()
  } catch (cause) {
    if (cause instanceof HttpClientError) cause.claimFeedback()
    if (current()) restoreSessionError.value = true
  } finally {
    if (epoch === restoreEpoch) restoringSession.value = false
  }
}
async function refreshSessions(scope: string = sessionScope.value) {
  requestedSessionScope = scope
  await sessionsTask.run(async () => {
    if (scope === 'selected' && (!endUserId.value || !targetKey.value))
      throw Error('请先选择终端用户及智能体或团队，再筛选历史会话')
    const target = scope === 'selected' ? selectedTarget() : undefined
    const nextSessions = await api.chat.sessions(
      target
        ? {
            endUserId: endUserId.value,
            agentId: target.agent_id,
            teamId: target.team_id
          }
        : {}
    )
    sessions.value = nextSessions
    sessionScope.value = scope
  })
}
async function readSessionState() {
  await run(async () => {
    const detail = await api.chat.sessionDetail(sessionId.value)
    sessionClosed.value = detail.closed
    if (detail.closed) pendingCloses.value.delete(sessionId.value)
  })
}
async function closeSession() {
  if (!canCancel.value || sessionReadOnly.value) return
  await run(async () => {
    if (
      !(await confirm({
        title: '关闭会话',
        content:
          '关闭当前会话后不可继续对话，历史记录和审计保留。关闭不会撤销已执行的操作，也不代替停止运行。',
        confirmText: '关闭会话'
      }))
    )
      return
    pendingCloses.value.add(sessionId.value)
    try {
      await api.chat.closeSession(sessionId.value)
    } catch (cause) {
      // 此关闭入口的认证、授权、归属与请求校验拒绝发生在写入前；仅接受正式错误契约。
      if (
        cause instanceof HttpClientError &&
        ['UNAUTHORIZED', 'FORBIDDEN', 'NOT_FOUND', 'VALIDATION_ERROR'].includes(cause.code ?? '')
      )
        pendingCloses.value.delete(sessionId.value)
      throw cause
    }
    const detail = await api.chat.sessionDetail(sessionId.value)
    sessionClosed.value = detail.closed
    if (detail.closed) pendingCloses.value.delete(sessionId.value)
  })
}
async function older() {
  await run(async () => {
    const current = history.value
    if (!current) return
    const previous = await api.chat.history(sessionId.value, current.next_before_message_id)
    if (history.value?.session_id !== previous.session_id) return
    messages.value = mergeHistoryMessages(messages.value, previous.messages, true)
    history.value = {
      ...history.value,
      messages: messages.value,
      has_more: previous.has_more,
      next_before_message_id: previous.next_before_message_id
    }
  })
}
async function stop(targetRun?: string) {
  if (!canCancel.value) return
  await run(async () => {
    await api.chat.cancelRun(sessionId.value, targetRun ?? runId.value)
    await refreshHistory()
  })
}
async function respond(action?: string | Event) {
  if (readOnly.value) return
  await run(async () => {
    const value = pending.value
    if (!value) return
    const draftKey = pendingKey.value
    let response: ChatHitlResponse
    if (value.interaction_type === 'plan_review') {
      response = {
        ...value,
        plan_review: {
          decision: action === 'approve' ? 'approve' : 'keep_planning',
          feedback: pendingText.value
        }
      }
    } else if (value.interaction_type === 'user_input') {
      if (!pendingText.value.trim()) throw Error('请先填写补充信息。')
      if (
        typeof value.run_revision !== 'string' ||
        !/^[1-9][0-9]*$/.test(value.run_revision) ||
        !value.tool_wait?.action_id ||
        !/^[1-9][0-9]*$/.test(value.tool_wait.generation)
      )
        throw Error('当前补充待办已失效，请重新读取会话，并按当前待办填写。')
      response = {
        execution_id: value.execution_id,
        run_revision: value.run_revision,
        human_input: { wait: { ...value.tool_wait }, text: pendingText.value }
      }
    } else if (value.pending_ref)
      response = {
        execution_id: value.execution_id,
        pending_ref: value.pending_ref,
        run_revision: value.run_revision,
        checkpoint_revision: value.checkpoint_revision,
        response: pendingText.value
      }
    else {
      if (
        typeof value.run_revision !== 'string' ||
        !/^[1-9][0-9]*$/.test(value.run_revision) ||
        !value.tool_wait?.action_id ||
        !/^[1-9][0-9]*$/.test(value.tool_wait.generation) ||
        !value.workflow_execution_id ||
        !value.suspend_ref ||
        !value.execution_revision ||
        !/^[1-9][0-9]*$/.test(value.execution_revision) ||
        !value.resume_action_key
      )
        throw Error('当前工作流待办已失效，请重新读取会话，并按当前待办填写。')
      response = {
        execution_id: value.execution_id,
        run_revision: value.run_revision,
        workflow_input: {
          wait: { ...value.tool_wait },
          execution_ref: value.workflow_execution_id,
          suspend_ref: value.suspend_ref,
          execution_revision: value.execution_revision,
          action_key: value.resume_action_key,
          response: {
            schema_id: 'application/json',
            canonical_json: JSON.stringify({ action: 'confirm', response: pendingText.value })
          }
        }
      }
    }
    await dispatchInput({
      unknown: false,
      draft: pendingText.value,
      replyDraftKey: draftKey,
      body: {
        session_id: sessionId.value,
        ...selectedTarget(),
        client_message_id: crypto.randomUUID(),
        hitl_response: response,
        context: { end_user_id: endUserId.value }
      }
    })
  })
}
async function submitHitl(response: ChatHitlResponse) {
  if (readOnly.value) return
  await run(async () => {
    // ToolInputForm已构造独立wait与canonical_json结果，不持有可变表单值引用。
    await dispatchInput({
      unknown: false,
      draft: '',
      body: {
        session_id: sessionId.value,
        ...selectedTarget(),
        client_message_id: crypto.randomUUID(),
        hitl_response: response,
        context: { end_user_id: endUserId.value }
      }
    })
  })
}
/** 迟到受理回执只移除原准确等待，不能消耗同一 Run 的新代次表单。 */
function samePendingResponse(
  item: ChatHistoryResponse['pending_interactions'][number],
  response: ChatHitlResponse
) {
  if ('tool_input' in response)
    return (
      'tool_wait' in item &&
      item.run_revision === response.run_revision &&
      item.tool_wait?.action_id === response.tool_input.wait.action_id &&
      item.tool_wait?.generation === response.tool_input.wait.generation
    )
  if ('human_input' in response)
    return (
      'tool_wait' in item &&
      item.run_revision === response.run_revision &&
      item.tool_wait?.action_id === response.human_input.wait.action_id &&
      item.tool_wait?.generation === response.human_input.wait.generation
    )
  if ('workflow_input' in response)
    return (
      'tool_wait' in item &&
      item.run_revision === response.run_revision &&
      item.tool_wait?.action_id === response.workflow_input.wait.action_id &&
      item.tool_wait?.generation === response.workflow_input.wait.generation &&
      item.workflow_execution_id === response.workflow_input.execution_ref &&
      item.suspend_ref === response.workflow_input.suspend_ref &&
      item.execution_revision === response.workflow_input.execution_revision &&
      item.resume_action_key === response.workflow_input.action_key
    )
  return (
    'checkpoint_revision' in response && item.checkpoint_revision === response.checkpoint_revision
  )
}
const attachmentUpload = ref<UploadInstance>()
async function uploadSelected(file: File, kind: 'file' | 'image') {
  await run(async () => {
    const actor = creationActor.value,
      identity = auth.identityVersion
    try {
      await ensureSession()
    } catch (reason) {
      if (!pageDisposed && creationActor.value === actor && auth.identityVersion === identity)
        deferredUpload.value = { file, kind }
      throw reason
    }
    if (
      pendingUploads.value.some(
        (item) => item.session === sessionId.value && item.kind === kind && item.name === file.name
      )
    )
      throw Error('该文件的原上传结果尚未确认，请先核对，不要重新上传。')
    const operation: PendingUpload = {
      session: sessionId.value,
      uploadId: crypto.randomUUID(),
      name: file.name,
      kind
    }
    try {
      savePendingUploads(actor, [...pendingUploads.value, operation])
    } catch {
      deferredUpload.value = { file, kind }
      throw Error('无法保存上传记录，文件尚未发送；请恢复浏览器存储后继续。')
    }
    deferredUpload.value = undefined
    if (kind === 'file') {
      let attachment: Awaited<ReturnType<typeof api.chat.uploadAttachment>>
      try {
        attachment = await api.chat.uploadAttachment(operation.session, operation.uploadId, file)
      } catch (cause) {
        if (
          cause instanceof HttpClientError &&
          cause.code === 'SESSION_ATTACHMENT_UPLOAD_REJECTED' &&
          !pageDisposed &&
          creationActor.value === actor &&
          auth.identityVersion === identity
        )
          savePendingUploads(
            actor,
            pendingUploads.value.filter((item) => item !== operation)
          )
        throw cause
      }
      if (pageDisposed || creationActor.value !== actor || auth.identityVersion !== identity) return
      if (attachment?.id !== operation.uploadId || !attachment.name)
        throw Error('上传回执不完整，请核对原上传')
      savePendingUploads(
        actor,
        pendingUploads.value.filter((item) => item !== operation)
      )
      attachments.value.push(attachment)
    } else {
      let media: Awaited<ReturnType<typeof api.chat.uploadMedia>>
      try {
        media = await api.chat.uploadMedia(operation.session, operation.uploadId, file)
      } catch (cause) {
        if (
          cause instanceof HttpClientError &&
          cause.code === 'SESSION_MEDIA_UPLOAD_REJECTED' &&
          !pageDisposed &&
          creationActor.value === actor &&
          auth.identityVersion === identity
        )
          savePendingUploads(
            actor,
            pendingUploads.value.filter((item) => item !== operation)
          )
        throw cause
      }
      if (pageDisposed || creationActor.value !== actor || auth.identityVersion !== identity) return
      if (
        !media?.media_id ||
        !media.mime_type ||
        !Number.isSafeInteger(media.byte_length) ||
        media.byte_length <= 0 ||
        !Number.isSafeInteger(media.width) ||
        media.width <= 0 ||
        !Number.isSafeInteger(media.height) ||
        media.height <= 0
      )
        throw Error('上传回执不完整，请核对原上传')
      savePendingUploads(
        actor,
        pendingUploads.value.filter((item) => item !== operation)
      )
      mediaIds.value.push(media.media_id)
    }
  })
}
function savePendingUploads(actor: string, values: PendingUpload[]) {
  if (values.length) sessionStorage.setItem(uploadKey(actor), JSON.stringify(values))
  else sessionStorage.removeItem(uploadKey(actor))
  pendingUploads.value = values
}
async function inspectUpload(operation: PendingUpload) {
  const actor = creationActor.value,
    identity = auth.identityVersion
  if (
    !operation ||
    !actor ||
    sessionId.value !== operation.session ||
    !can('foundation.sessions.read')
  )
    return
  await run(async () => {
    if (operation.kind === 'file') {
      const items = await api.chat.attachments(operation.session)
      if (
        pageDisposed ||
        creationActor.value !== actor ||
        auth.identityVersion !== identity ||
        !pendingUploads.value.includes(operation)
      )
        return
      const found = items.find((item) => item.id === operation.uploadId)
      if (!found || typeof found.name !== 'string' || !found.name) {
        message.warning('尚未查到原上传，结果仍未确认。')
        return
      }
      savePendingUploads(
        actor,
        pendingUploads.value.filter((item) => item !== operation)
      )
      if (!attachments.value.some((item) => item.id === found.id)) attachments.value.push(found)
    } else {
      const result = await api.chat.mediaUploadReceipt(operation.session, operation.uploadId)
      if (
        pageDisposed ||
        creationActor.value !== actor ||
        auth.identityVersion !== identity ||
        !pendingUploads.value.includes(operation)
      )
        return
      if (
        result.status !== 'FOUND' ||
        !result.media?.media_id ||
        !result.media.mime_type ||
        !Number.isSafeInteger(result.media.byte_length) ||
        result.media.byte_length <= 0 ||
        !Number.isSafeInteger(result.media.width) ||
        result.media.width <= 0 ||
        !Number.isSafeInteger(result.media.height) ||
        result.media.height <= 0
      ) {
        message.warning('尚未查到原上传，结果仍未确认。')
        return
      }
      savePendingUploads(
        actor,
        pendingUploads.value.filter((item) => item !== operation)
      )
      if (!mediaIds.value.includes(result.media.media_id))
        mediaIds.value.push(result.media.media_id)
    }
    message.success('原上传已确认，已加入当前消息。')
  })
}
async function continueUpload() {
  if (
    !deferredUpload.value ||
    readOnly.value ||
    (!sessionId.value && (!canCreate.value || !endUserId.value || !targetKey.value))
  )
    return
  const { file, kind } = deferredUpload.value
  await uploadSelected(file, kind)
}
async function uploadAttachment(selection: UploadFile) {
  const file = selection.raw
  attachmentUpload.value?.clearFiles()
  if (file && !deferredUpload.value) await uploadSelected(file, 'file')
}
const imageUpload = ref<UploadInstance>()
async function uploadImage(selection: UploadFile) {
  const file = selection.raw
  imageUpload.value?.clearFiles()
  if (file && !deferredUpload.value) await uploadSelected(file, 'image')
}
async function setPlan(value: unknown) {
  if (readOnly.value) return
  await run(async () => {
    collaboration.value = await api.chat.setPlanMode(sessionId.value, !!value)
  })
}
function applySteering(value: ChatSteeringState, sent: FrozenSteering) {
  const accepted =
    value.run_id === sent.run
      ? value.items.find((item) => item.command_id === sent.command && item.text === sent.text)
      : undefined
  if (!accepted) return false
  confirmedSteering.value = { request: sent, item: accepted, accepting: value.accepting }
  if (pendingSteering.value === sent) {
    pendingSteering.value = undefined
    if (steerText.value === sent.text) steerText.value = ''
  }
  return true
}
async function steer(retry = false) {
  if (
    !canRun.value ||
    busy.value ||
    (!retry && (readOnly.value || !runId.value || !steerText.value.trim()))
  )
    return
  const sent = retry
    ? pendingSteering.value
    : {
        session: sessionId.value,
        run: runId.value,
        command: crypto.randomUUID(),
        text: steerText.value,
        unknown: false
      }
  if (!sent) return
  pendingSteering.value = sent
  if (!retry) confirmedSteering.value = undefined
  await run(async () => {
    try {
      const value = await api.chat.steer(sent.session, sent.run, sent.command, sent.text)
      if (!applySteering(value, sent)) {
        sent.unknown = true
        throw Error('尚未核实原补充，请核对补充状态或重试原补充。')
      }
    } catch (cause) {
      // 只有首次请求绑定校验能证明未写；回读失败和 UNKNOWN 后的拒绝均保留原身份。
      if (
        !sent.unknown &&
        cause instanceof HttpClientError &&
        cause.status === 400 &&
        cause.code === 'VALIDATION_ERROR'
      )
        pendingSteering.value = undefined
      else sent.unknown = true
      throw cause
    }
  })
}
async function querySteering() {
  const sent = pendingSteering.value ?? confirmedSteering.value?.request
  if (!sent || busy.value) return
  await run(async () => {
    const value = await api.chat.steering(sent.session, sent.run)
    if (!applySteering(value, sent))
      throw Error('尚未查到原补充的精确记录，请保留原补充并继续核对。')
  })
}
function appendSteeringToMessage() {
  const receipt = confirmedSteering.value
  if (
    !receipt ||
    receipt.accepting ||
    receipt.item.status === 'CONSUMED' ||
    readOnly.value ||
    busy.value
  )
    return
  input.value = [input.value, receipt.item.text].filter(Boolean).join('\n\n')
}
const objective = ref(''),
  maxRounds = ref(CHAT_GOAL_DEFAULT_MAX_ROUNDS),
  goal = ref<ChatGoalStatus>(),
  artifacts = ref<ChatArtifact[]>([]),
  devices = ref<BrowserDevices>()
const browserTasks = ref<BrowserTask[]>([]),
  browserPanelActive = ref(false),
  browserBusy = ref(false),
  browserError = ref(''),
  browserFeedback = ref(''),
  browserPairPending = ref(false),
  browserPairUnknown = ref(false)
let browserEpoch = 0,
  browserAbort = new AbortController()

/** 独立设备配对的未知标记绑定准确站点和会话，不能在刷新或重挂载后重复签发。 */
function browserPairKey(session: string) {
  return `noewise:device-pair:${JSON.stringify([window.location.origin, { session_id: session }])}`
}

/** 会话或面板离开时停止观察，保留已经发出的配对事实，清空可能属于旧会话的展示。 */
function resetBrowserView() {
  browserEpoch++
  browserAbort.abort()
  browserAbort = new AbortController()
  browserBusy.value = false
  browserPairPending.value = false
  browserPairUnknown.value =
    !!sessionId.value && sessionStorage.getItem(browserPairKey(sessionId.value)) === 'sent'
  browserTasks.value = []
  devices.value = undefined
  browserError.value = ''
  browserFeedback.value = ''
}

watch(
  () => [sessionId.value, browserPanelActive.value] as const,
  ([session, active]) => {
    resetBrowserView()
    if (session && active) void loadDevices()
  }
)

/** 所有读写捕获原会话及观察代次；迟到结果不能覆盖新会话，也不能清除新操作的busy。 */
async function browserOperation(
  action: (session: string, signal: AbortSignal, current: () => boolean) => Promise<void>
) {
  const session = sessionId.value,
    version = browserEpoch,
    signal = browserAbort.signal
  if (!session || browserBusy.value) return
  const current = () => !signal.aborted && version === browserEpoch && session === sessionId.value
  browserBusy.value = true
  browserError.value = ''
  browserFeedback.value = ''
  try {
    await action(session, signal, current)
  } catch (cause) {
    if (cause instanceof HttpClientError) cause.claimFeedback()
    if (current()) browserError.value = cause instanceof Error ? cause.message : '浏览器操作未完成'
  } finally {
    if (current()) browserBusy.value = false
  }
}

/** GET刷新不重发浏览器动作，图片身份继续使用各自回执返回的会话。 */
async function readBrowserSnapshot(session: string, signal: AbortSignal, current: () => boolean) {
  const selector = { session_id: session }
  const [value, tasks] = await Promise.all([
    api.chat.browserDevices(selector, signal),
    api.chat.browserTasks(selector, signal)
  ])
  if (!current()) return
  devices.value = value
  browserTasks.value = tasks
}

/** 动态回执只读取明确字段，不把缺失或未知清理状态解释为成功。 */
function browserTaskError(item: BrowserTask) {
  const result = item.receipt.result
  return result !== null &&
    typeof result === 'object' &&
    'error' in result &&
    typeof result.error === 'string'
    ? result.error
    : ''
}
function browserTaskCleanup(item: BrowserTask) {
  const result = item.receipt.result
  return result !== null &&
    typeof result === 'object' &&
    'cleanup_complete' in result &&
    typeof result.cleanup_complete === 'boolean'
    ? result.cleanup_complete
      ? '已确认清理完成'
      : '尚未确认清理完成'
    : ''
}
async function refreshPanels(tab?: string | number) {
  browserPanelActive.value = tab === 'browser'
  if (browserPanelActive.value) return
  if (!sessionId.value) return
  await run(async () => {
    ;[goal.value, artifacts.value, collaboration.value] = await Promise.all([
      api.chat.goals(sessionId.value),
      api.chat.artifacts(sessionId.value),
      api.chat.collaboration(sessionId.value)
    ])
  })
}
async function createGoal() {
  if (readOnly.value) return
  await run(async () => {
    if (!objective.value.trim()) throw Error('请输入目标')
    goal.value = await api.chat.createGoal(
      sessionId.value,
      crypto.randomUUID(),
      objective.value,
      maxRounds.value
    )
    objective.value = ''
  })
}
async function controlGoal(op: 'pause' | 'resume') {
  if (readOnly.value) return
  await run(async () => {
    if (!goal.value?.goal) return
    goal.value = await (op === 'pause' ? api.chat.pauseGoal : api.chat.resumeGoal)(
      sessionId.value,
      crypto.randomUUID(),
      goal.value.goal.revision
    )
  })
}
async function download(revision: string, file: string) {
  await run(async () => {
    const ticket = await api.chat.downloadTicket(revision, file)
    const a = document.createElement('a')
    a.href = ticket.url
    a.rel = 'noopener'
    a.click()
  })
}
const previewOpen = ref(false),
  previewUrl = ref(''),
  previewText = ref('')
async function preview(revision: string, file: string) {
  await run(async () => {
    const ticket = await api.chat.previewTicket(revision, file)
    previewUrl.value = ''
    previewText.value = ''
    if (ticket.kind === 'html') {
      const origin = artifactPreviewOrigin(import.meta.env.VITE_ARTIFACT_PREVIEW_ORIGIN)
      if (!isArtifactPreviewUrlAllowed(ticket.url, origin, window.location.origin))
        throw Error('HTML 预览需配置独立预览域名，请先使用下载。')
      previewUrl.value = ticket.url
    } else {
      const response = await fetch(ticket.url, { credentials: 'omit' })
      if (!response.ok) throw Error('无法读取预览内容')
      previewText.value = await response.text()
    }
    previewOpen.value = true
  })
}
async function loadDevices() {
  await browserOperation(readBrowserSnapshot)
}

/** 独立配对只交接给受信适配扩展，不创建连接等待或恢复任何Run；未知写入不得重发。 */
async function pairBrowser() {
  const session = sessionId.value,
    version = browserEpoch,
    signal = browserAbort.signal
  if (!session || browserBusy.value || browserPairPending.value || browserPairUnknown.value) return
  const current = () => !signal.aborted && version === browserEpoch && session === sessionId.value
  let ownsBusy = true
  browserBusy.value = true
  browserPairPending.value = true
  browserError.value = ''
  browserFeedback.value = ''
  try {
    const probe = await browserBridge('probe', {}, signal)
    if (!probe.ok) throw new Error(connectionBridgeMessage(probe.code, 'device'))
    if (!current()) return
    browserPairUnknown.value = true
    sessionStorage.setItem(browserPairKey(session), 'sent')
    const result = await api.chat.browserPair({ session_id: session })
    if (!current()) return
    browserBusy.value = false
    ownsBusy = false
    browserFeedback.value = '请打开插件确认配对。配对只建立设备连接，不会恢复任何任务。'
    const response = await browserBridge(
      'pair',
      {
        pairing_url: result.pairing_url,
        identity_scope: JSON.stringify({ session_id: session })
      },
      signal
    )
    if (!current()) return
    if (response.ok || response.code === 'user_rejected' || response.code === 'site_not_trusted') {
      browserPairUnknown.value = false
      sessionStorage.removeItem(browserPairKey(session))
    }
    if (response.ok) {
      await loadDevices()
      if (current())
        browserFeedback.value = '插件已确认配对，请刷新查看真实连接状态，可按需设为默认设备。'
    } else browserFeedback.value = connectionBridgeMessage(response.code, 'device')
  } catch (cause) {
    if (cause instanceof HttpClientError) cause.claimFeedback()
    if (current())
      browserError.value =
        cause instanceof Error ? cause.message : '配对结果尚未确认，请查看插件并刷新设备列表。'
  } finally {
    if (current()) {
      if (ownsBusy) browserBusy.value = false
      browserPairPending.value = false
    }
  }
}
async function selectDevice(id: string) {
  if (browserPairPending.value) return
  await browserOperation(async (session, _signal, current) => {
    const value = await api.chat.browserSelect({ session_id: session }, id)
    if (current()) devices.value = value
  })
}
async function revokeDevice(id: string) {
  const session = sessionId.value,
    version = browserEpoch
  if (browserBusy.value || browserPairPending.value) return
  if (await confirm({ title: '解除浏览器绑定', content: '确认撤销此浏览器的设备授权？' })) {
    if (session !== sessionId.value || version !== browserEpoch) return
    await browserOperation(async (selected, signal, current) => {
      await api.chat.browserRevoke({ session_id: selected }, id)
      if (current()) await readBrowserSnapshot(selected, signal, current)
    })
  }
}
async function cancelBrowser(invocation: string) {
  if (browserPairPending.value) return
  await browserOperation(async (session, signal, current) => {
    const receipt = await api.chat.browserCancel({ session_id: session }, invocation)
    if (!current()) return
    browserTasks.value = browserTasks.value.map((item) =>
      item.receipt.invocation === invocation ? { ...item, receipt } : item
    )
    browserFeedback.value = '已收到取消回执；是否清理完成以服务返回的任务状态与回执为准。'
    await readBrowserSnapshot(session, signal, current)
  })
}
const userOpen = ref(false),
  userName = ref(''),
  { requestClose: closeUser } = useDrawerClose({
    open: () => userOpen.value,
    busy: () => busy.value,
    dirty: () => userOpen.value && !!userName.value,
    close: () => {
      userOpen.value = false
      userName.value = ''
    }
  })
async function createUser() {
  if (!canCreate.value || usersTask.busy.value || sessionsTask.busy.value) return
  await run(async () => {
    if (!userName.value.trim()) throw Error('请输入用户名称')
    const user = await api.chat.createEndUser(userName.value)
    endUsers.value.push(user)
    if (!sessionId.value) endUserId.value = user.id
    userName.value = ''
    userOpen.value = false
  })
}

const formTipId = useId()
</script>
<style scoped>
.session-setup {
  padding-bottom: 16px;
  border-bottom: 1px solid var(--admin-border);
}
.pending-summary {
  color: var(--admin-text);
  font-weight: 600;
  padding: 12px 0 0;
}
.message-heading {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
}
.message-heading small {
  margin-left: auto;
  color: var(--admin-muted);
}
.secondary-panel {
  margin: 16px 0;
  padding: 12px 16px;
  border: 1px solid var(--admin-border);
  border-radius: 8px;
}
.secondary-panel summary {
  cursor: pointer;
  min-height: 24px;
  line-height: 24px;
}
.debug-message {
  overflow-wrap: anywhere;
}

.debug-messages {
  max-height: 52vh;
  min-height: 220px;
  overflow: auto;
  padding: 8px;
  background: var(--el-fill-color-light);
  border-radius: 10px;
}
.debug-message {
  margin: 10px 0;
}
.debug-composer {
  margin-top: 14px;
}
.debug-composer .toolbar {
  margin-top: 12px;
}
.pending-card {
  margin-top: 14px;
  border-color: var(--el-color-warning);
}
.browser-tasks {
  margin-top: 24px;
}
.browser-task {
  margin: 12px 0;
}
.browser-task p {
  overflow-wrap: anywhere;
}
.browser-task :deep(.el-image) {
  margin: 12px 0;
}
</style>
