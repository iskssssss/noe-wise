# Vue 共享层：把事件归并留在页面之外

前置阅读：[29 课](29-REST-SSE-Webhook与断线恢复.md)、[30 课](30-Agent聊天执行过程与历史重建.md)

同一研究助手有两个界面：研究者看到报告草稿和审批进度，管理者查看任务状态和资源用量。两个页面都需要连接事件流、归并运行状态、展示成果卡片。如果各复制一套逻辑，某次修复了重复事件，另一端可能仍然出现两张相同卡片。

本课是**按需进阶**，面向使用 Vue 的读者。跳过这一课，不影响下一课理解执行轨迹。这里借 Vue 说明共享边界，代码是语言示意，不是完整教学工程。

## 先共享语义，再考虑共享页面

适合共用的是协议类型、事件归并规则、连接恢复逻辑和稳定的展示模型。机构菜单、路由、审批入口布局及不同角色的操作流程，往往属于各自应用。

共享层不应直接导入某个应用的路由器或认证存储。应用通过接口注入取令牌、发请求和处理权限变化的能力，共享层只使用明确的契约。这样，两个前端可以各自组织页面，而不复制同一套恢复规则。

一种依赖方向是：底层客户端处理协议，状态层处理事实投影，组件层处理展示，应用层负责路由和业务装配。组件不应反过来在渲染过程中发起重试或批准请求。

## 让归并器成为纯函数

把状态归并从组件移出来，可以用小输入解释行为。下面的 TypeScript 示意只处理完整状态事件；它故意不承担真实系统里的鉴权、连接和缺口恢复：

```typescript
type RunState = "running" | "waiting" | "completed" | "cancelled";

interface RunView {
  id: string;
  version: number;
  state: RunState;
}

interface RunSnapshotEvent {
  runId: string;
  version: number;
  state: RunState;
}

function applySnapshot(current: RunView, event: RunSnapshotEvent): RunView {
  if (event.runId !== current.id || event.version <= current.version) {
    return current;
  }
  return { id: current.id, version: event.version, state: event.state };
}
```

这个函数可以忽略旧版本，因为事件包含完整状态。如果换成“追加一个步骤”这种差量事件，就需要额外的去重和顺序规则，不能直接套用它。抽象能否共享，取决于协议语义是否相同，不取决于函数看起来是否短。

## 组件只消费模型并发出意图

成果卡片接收产物状态与受控入口，向外发出预览或审批意图。下面是 Vue 单文件组件示意，展示最小的呈现边界：

```vue
<script setup lang="ts">
defineProps<{ title: string; canRequestApproval: boolean }>();
const emit = defineEmits<{ requestApproval: [] }>();
</script>

<template>
  <article>
    <h3>{{ title }}</h3>
    <button
      v-if="canRequestApproval"
      type="button"
      @click="emit('requestApproval')"
    >
      提交审批
    </button>
  </article>
</template>
```

canRequestApproval 用于交互提示，不能取代服务端授权。用户修改前端状态，后端仍必须检查身份、报告版本和状态。组件也不应在按钮点击后自行把产物改为 published；应用命令层受理后，按可靠查询或事件更新事实。

当多个组件观察同一 Run，应共享同一份状态投影，而不是每个组件创建一条独立连接。连接生命周期可以由 Session 页面或更高层管理，组件卸载只释放订阅，不误取消后端任务。

## 两端需要相似界面，不一定需要同一组件

研究者重视证据和草稿，管理员重视运行范围和异常。两端可以共用 RunView 与状态标签，却使用不同布局。强行共用一个带几十个开关的“大卡片”，通常把页面差异隐藏成条件分支，维护起来反而更难。

共享组件应围绕稳定交互，页面保留自己的组合方式。新增功能时可以先在一个页面明确需求，出现第二个相同语义的使用者后再抽取，避免把偶然相似当成长期契约。

共享层还要约定错误表达。断线可以显示重连状态，权限撤销需要清除或遮蔽受限内容，资源不存在与网络超时应分开。统一一个“操作失败”字符串会掩盖恢复方式的差别。

## 核对一次共享边界

练习：两个页面都收到 version=8 的 completed 快照，随后收到 version=7 的 waiting 快照。审批按钮被用户在浏览器中强行显示。说明共享层、组件和后端各自负责什么。

预期结果：归并器保留版本 8；组件显示完成状态，不负责重新解释业务历史；即便用户触发了非法命令，后端仍因状态或权限不符而拒绝。两端复用归并规则和协议，不需要共享各自的完整路由与页面。这才是共享层替我们减少的重复工作。

上一课：[30 课](30-Agent聊天执行过程与历史重建.md) · 下一课：[32 课](32-从运行事实构建执行轨迹与审计视图.md) · [学习路线](README.md)
