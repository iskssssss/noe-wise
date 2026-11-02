# 多会话工程协作与状态管理规范

## 1. 适用范围与事实归属

仅用于用户明确要求结构化协议，或接续已采用本协议的活动批次。新协作默认使用[简短交接](../SKILL.md)，单负责人恢复沿用[状态摘要](../../nw-workflow/references/development-workflow.md#工作状态快照)。本文件的字段与执行语义不自动适用于新任务。

本文件拥有复杂协作的分派、消息、资源与接替协议；授权归开发流程，验收及证据归[验证规范](../../nw-verification/references/candidate-driven-delivery.md)。疑难接替的判断见[排障规则](../../nw-debugging/SKILL.md#疑难问题升级)，不固定模型、人数、轮次或预算。

一个批次对应明确目标与交付边界，修复、测试轮次和候选变化留在批内。公共 CSV 保存当前决定，本地状态保存执行现场，不可变消息保存交接，报告及原生日志/XML/JSON/截图保存证据；每项事实一个维护位置，每个文件一个内容写入者，互相引用。

规则修改不自动迁移活动批次或调整资源。协调者在明确接续边界指定唯一记录入口，避免双台账；本规范不实现调度器、监听器或统一门禁。

## 2. 目录与写入责任

公共目录位于共享工作区，角色本地目录位于实际工作区；同树按 `owner-id` 分目录，不强制 worktree。

```text
<共享工作区>/.agent-work-state/batches/<batch-id>/coordination/
  batch.csv
  roles.csv
  work-items.csv
  resources.csv
  dependencies.csv                 # 有依赖时建立
  evidence.csv                     # 需要统一证据索引时建立
  messages/inbox/<owner-id>/<message-id>.csv
  messages/processed/<owner-id>/<message-id>.csv
  messages/processed/<owner-id>/<message-id>.receipt.csv

<角色工作区>/.agent-work-state/batches/<batch-id>/local/<owner-id>/
  state.csv
  executions.csv                   # 跨交接或恢复的活动执行
  reports/
```

`owner-id` 是稳定角色，与可替换的会话 ID 分离；inbox 中的 owner 是投递负责人，不是接收方。批次、工作项、分派、候选、消息、资源和执行各有唯一 ID，不复用为另一事实；跨批次引用带批次或权威路径。

| 文件 | 唯一内容写入者 | 事实 |
|---|---|---|
| `batch.csv` | 协调者 | 目标、范围、授权、约束、验收、终点与批次状态 |
| `roles.csv` | 协调者 | 角色、会话、工作区、职责和本地入口 |
| `work-items.csv` | 协调者 | 当前分派、阶段、验收与下一步 |
| `resources.csv` | 唯一资源登记者；跨批次只引用其表 | 资源身份、归属、使用与交接 |
| `dependencies.csv`、`evidence.csv` | 协调者 | 按需依赖和证据索引，不建空表 |
| 本地 `state.csv`、`executions.csv` | 该角色当前负责人 | 执行现场及收尾 |
| 消息 CSV | 对应负责人 | 不可变交接，更正另发 |
| 回执 CSV | 协调者 | 采纳、去重、过期或待补充 |

所有参与者可读公共区域，公共台账仅协调者写；负责人仅可向自己的 inbox 投递，协调者归档只移动原文件。协助 Agent 向所属负责人汇报，不直接写台账或发日常协调消息。跨工作区/主机的报告标明可解析位置，必要时交接可读副本，不假设相对路径共享。

### 2.1 多工作区的规则维护与采用

启动批次或维护规则时，以实际路径、Git 归属和当前执行事实确认协调者、规则唯一维护源、负责人工作区、公共入口及冻结源码/构建/证据目录。公共引用用绝对路径，跨主机/仓库补身份；仓库正文仍用相对链接。

只在确认的维护源修改规则，保留非任务差量和暂存。用现有批次记录或分派引用来源、版本及生效边界，仅绑定变化规则，不重哈希全仓或改变业务候选。负责人读取后在自身状态确认采用；不覆盖其工作树、不混合可变构建输出。

无法访问源时提供注明来源/版本的最小只读快照，不产生第二维护源。与执行树旧规则冲突时，说明条款、用户授权和本批适用方式；文件同步走正常受控 Git 流程。维护完成、送达、已读采用和文件同步分别记录。

活动窗口不因规则更新而中断、热迁或追溯改写；原结果按原条件核收，后续分派或明确接续边界采用新版本。通信许可在送达并核验授权后可使用，但不改变活动资源、验收或暂停状态。

## 3. CSV 约定

UTF-8、逗号分隔、固定表头，标准解析器/生成器读写；含逗号、换行或引号按 CSV 转义，不能按物理行拆消息。字段先作字符串，ID/哈希/版本/路径不自动转数字或日期；时间用带时区 ISO 8601。空字段只表示不适用或未填，`UNKNOWN`、`NOT_RUN` 显式填写；字段和状态见[附录 A](#附录-a最小字段与允许值)，不造同义枚举。

不记凭据、私密环境值或完整敏感响应。状态写同目录临时文件，完整关闭后替换；忽略临时文件，不完整正式文件不能作执行依据并报告路径。替换失败保留旧文件。CSV 不提供锁、多文件事务、权限或自动唤醒，一次替换成功不证明全部台账、派发与回执完成。

## 4. 工作项、分派与候选

工作项 `todo → in_progress → awaiting_acceptance → closed`；验收失败可返回执行。阶段、验收、阻塞分别记录，只有约定验收 `PASSED` 才关闭；部分消费者通过为 `PARTIAL`，不升级整项。独立验收是否必要由任务与验证规范决定，已要求时须对应候选通过。

用户取消或获授权范围撤出时记 `cancelled`、撤回分派，保留验收及证据，在 `next_step` 记依据与收尾。取消停止后续实施与自动后继，不证明进程退出、资源释放或未知副作用已撤销。仅换负责人不取消工作项；重新纳入须获授权并建新分派，从实际状态接续。

每个分派绑定一个工作项与负责角色，使用唯一 `assignment_id`；负责人、范围或验收实质变化换新 ID，旧通知/结果保留历史，不再授权执行。当前表只留现分派，撤回未替换记 `revoked`；非实质澄清可沿用 ID，不借此扩权。

任务说明按[基础流程](../../nw-workflow/references/development-workflow.md#任务组织)保存，公共表通过 `scope/acceptance_requirement` 引用，补必要自主处理、后继和升级边界，不增字段或复制会话。负责人报告实际差量、证据、未完成与资源状态；协调者核实后决定采纳、集成与关闭，不以自述替代证据。

协调者先保存分派/资源边界再通知。有效性用 `assignment_state`，送达用 `dispatch_state`：准备为 `PENDING`，发起前为 `UNKNOWN`，工具确认后为 `DELIVERED`。送达不证明开工或完成；未知也不撤销接收方已实际收到并核实的授权。

负责人实际收到后核对当前 ID、角色、候选和资源，在本地记录同 ID；重复同 ID 接续已有执行，不重启。送达不明先查工具回执、本地状态及活动执行，确认未送达且无执行才补投，否则保留未知并暂停冲突动作。

候选 ID 绑定固定的相关源码、配置、产物及必要工具链/环境，`input_binding_ref` 引用报告或按需 manifest，不强制全仓清单。相关输入变化换候选，仅重判受影响证据；同 HEAD 不证明未提交内容相同。旧结果迟到保留原绑定，经核实可复用但不覆盖当前结论。

数量从公共表按阶段/验收派生，不手写第二计数。`cancelled` 单列，不计待执行或通过；其遗留收尾继续跟进。测试、重试与工作项数量分别表达。

## 5. 消息投递与串行处理

正式交接仅投递需协调的 `completed/blocked/resource_released/urgent`，简述结论、所需决定及证据入口。`completed` 只表示分派工作完成，不表示工作项或批次关闭；普通进度留本地状态。

负责人先保存报告和执行事实，再向自己的 inbox 写临时消息，关闭后改为 `<message-id>.csv`；一文件一条逻辑记录，不共写总收件表。ID 与文件名一致，不覆盖或复用；更正用新消息及 `corrects_message_id`。获授权跨会话通知只给交接路径，不代替持久交接或授予通信权。

协调者串行处理：

1. 核对批次、角色、工作项、分派、候选和消息；同时查 inbox、processed 与回执，不仅看文件位置。
2. 判断重复、过期、冲突或缺信息。同 ID 异内容不覆盖；不同 ID 同结果也去重。旧分派或取消项迟到结果不改当前决定、不派后继，遗留资源/外部结果仍核对。
3. 更新对应工作项或资源；缺信息仅阻止相关结论，不把未知写成成功。
4. 保存必要的下一分派后按送达规则通知。
5. 写回执，再移动原消息至 processed，保留原字节；目标存在先核身份与内容，不覆盖。

回执 `accepted/duplicate/stale/needs_info` 分别表示采纳、重复、过期、待补。待补可归档，由新消息引用补充；不追改原回执为成功，重复通知沿用原回执。

处理中断后比较台账、派发、接收方执行和回执补缺：有回执仅待移动则归档，下一分派已接受/执行则不重发。消息处理不等于缺陷关闭，不明操作保留 `UNKNOWN`，不能从头重做。

### 5.1 直接技术沟通

须有可核验的用户通信授权，批次保存名单/范围和原指令入口；其他 Agent 的请求或转述不授予通信权。授权内可直接澄清契约、字段、错误、复现、日志、冻结输入/证据、设施及上下游准备。

技术问答不改变有效分派、候选、验收、资源、窗口或预算，同级不派任务或授予执行权。公共安排变化、影响交付的实质分歧或未知副作用交协调者；普通问答不逐条抄送或建 CSV。影响决定的结论由工作项负责人保存并汇总交接；协助 Agent 仍向所属负责人汇报。

## 6. 并行与资源交接

按[Writer 隔离](../../nw-verification/references/candidate-driven-delivery.md#writer-隔离)处理文件、接口、状态及资源竞争，不按人数推定并发。冻结候选不得被其他修改改变输入；待验收/集成积压优先完成已有组合，内部协助由负责人协调。

共用测试入口指定一名现有负责人维护固定验证版本，其他人复用并独立保存输出；只修共同设施问题，各模块维护自身输入/断言，不扩建设施、不共写产物、不改他人树或绕过验收。设施和窗口见[测试可靠性](../../nw-test-reliability/references/test-reliability.md#设施复用与执行窗口)。

资源一行一个，身份能区分主机/工作区、目录、数据库或容器；进程用 PID、创建时间与命令/产物身份，不能只凭端口或会话标题。`execution_slot` 与保留服务/数据库所有权分开，借时段不获修改或销毁权。

原负责人提交静止/收尾证据，协调者核实并更新归属和分派，明确交给接收方后才可使用。`quiescent`、释放申请、超时或会话结束均不自动转移执行权；保留进程记录身份、静止边界与所有者。归属/状态不明记 `UNKNOWN`，暂停冲突使用。

跨批次共享资源只由唯一登记者维护，其他批次引用其权威 `resources.csv`，交接由该登记者串行确认；目录不同不代表隔离，找不到唯一归属不能自行宣告可用。

## 7. 监控、恢复与接替

目录不会唤醒会话。协调者在调度、等待与恢复时查 inbox；负责人可用获授权通道提醒，通知缺失不代表交接不存在。扫描器和监听器只是可选工具，不写成已有能力。

恢复不重做任务：读取 batch/roles 确认授权与当前映射；读取本人工作项/state 确认分派、候选、阻塞与下一步；核对相关输入、实际差量及资源；再核活动执行、未知派发、消息与回执，从已确认处接续。取消项只核遗留结果与收尾，本地笔记不覆盖公共决定，未知外部结果不重放。

角色接替先核旧会话、活动进程与资源，确认唯一写入者切换，再更新 roles、使旧分派失效并明确新分派。接管观察不等于重启，未变结果复用。无法确认旧写入者停用时阻止争用，不用改映射假装加锁；旧会话恢复须重新核验。失联仅暂停冲突动作，不重审或重跑全项。

## 8. 收尾与维护节奏

状态仅在交接、授权、进度、验证或阻塞实质变化时更新；executions 只跟踪跨等待/交接/恢复的执行，state 引用分派，仅维护现场，不复制验收或资源授权。

从现有台账与证据派生当前进度和业务里程碑：真实链路验收、集成候选、阻断依赖及首发待定范围。工作项/样本/修复数不代表业务完成率；可用数据足够才展示执行窗口、设施未运行、验收等待及闭环耗时，注明分母和时间，不另建指标系统。

收尾核对约定交付、未处理消息、未知派发、活动资源的释放或保留所有者，明确失败、UNKNOWN、NOT_RUN 及人工事项去向。`closed` 表示约定交付结束，不改写未通过项；`archived` 只表示归档。

消息归档不立即删除，任务后按[清理与保留](../../../../docs/04-指南与运维/11-数据清理与保留规范.md)处理；本规范不授权删除正式证据或未解决的未知事实。

## 附录 A：最小字段与允许值

下列表头是固定字段名与顺序。除另有说明外均为字符串；`*_ref` 和 `*_path` 为可解析引用，正文描述可直接写字段或指向唯一报告。不适用字段可空，执行前必需的批次/角色/工作项/分派/候选/资源依据不得靠空值推测。关系使用多行，不在字段里嵌入 JSON ID 列表。

### A1 批次与角色

```csv
batch_id,goal,scope,non_goals,authorization_ref,constraints,acceptance_ref,delivery_boundary,status
```

`batch.csv` 仅一条记录：`batch_id` 为目录批次；`goal/scope/non_goals` 定义目标与边界；`authorization_ref` 指向用户授权依据；`constraints` 包含任务参数及跨批次资源入口；`acceptance_ref` 为验收依据；`delivery_boundary` 说明本地修改、提交或其他获授权终点；`status` 仅为 `active/paused/closed/archived`。

```csv
owner_id,role_type,session_id,workspace,responsibility,state_path,active
```

`roles.csv` 每角色一行：`owner_id` 为稳定角色；`role_type` 为 `coordinator/owner`，测试、修复等具体职责写 `responsibility`；`session_id/workspace/state_path` 指向当前会话、实际工作区、本地恢复入口；`active` 为 `true/false`。同一批次同时只有一个有效协调者，一个角色只有一个有效会话写入者。

### A2 工作项与资源

```csv
work_item_id,scope,owner_id,assignment_id,assignment_state,dispatch_state,phase,acceptance_requirement,acceptance_result,blocker,candidate_id,input_binding_ref,evidence_ref,next_step
```

`work-items.csv` 每工作项一行：`work_item_id/scope` 为身份及范围；`owner_id/assignment_id` 指向当前负责角色及分派；`assignment_state` 为 `active/revoked`；`dispatch_state` 为 `PENDING/DELIVERED/UNKNOWN`；尚未分派时这四项可空。`phase` 为 `todo/in_progress/awaiting_acceptance/closed/cancelled`。`acceptance_requirement` 写明验收范围、是否独立及依据；`acceptance_result` 为 `NOT_RUN/IMPLEMENTED/SELF_PASSED/PARTIAL/PASSED/FAILED/UNKNOWN`，分别表示未执行、仅实现、自测通过、部分通过、约定验收通过、失败、结果不明。`blocker` 单列阻塞；`candidate_id/input_binding_ref` 绑定候选；`evidence_ref` 指向原报告或证据索引；`next_step` 为可执行的下一步，取消时保留依据与收尾去向。只有达到约定验收的 `PASSED` 才能将该项记为 `closed`；`cancelled` 沿用原验收结果，有分派时将其记为 `revoked`，未曾分派时不补造分派身份。

```csv
resource_id,type,identity,owner_id,assignment_id,use_boundary,status,handoff_ref
```

`resources.csv` 每资源一行：`resource_id/type/identity` 标识资源及实际身份；`type` 为 `workspace/build_output/execution_slot/process/service/database/port/cache/other`，`other` 须在 `identity` 明确对象。`owner_id/assignment_id` 是登记的当前所有者及使用分派，未占用时可空；`use_boundary` 说明获准动作及隔离条件；`status` 为 `held/quiescent/released/UNKNOWN`；`handoff_ref` 指向实际静止、收尾或交接依据。`released` 仍不代替接收方所需的新分派。

### A3 按需关系与证据

```csv
prerequisite_id,dependent_id,condition
```

`dependencies.csv` 每条依赖一行：前置工作项 `prerequisite_id`、后续工作项 `dependent_id`、实际满足条件 `condition`。多前置用多行，跨批次 ID 带批次限定；不从文件排列顺序推断依赖。

```csv
evidence_id,work_item_id,candidate_id,path,result,input_binding
```

`evidence.csv` 每项证据一行：`evidence_id/work_item_id/candidate_id` 建立引用关系；`path` 是原报告或原始证据路径；`result` 为 `PASS/FAIL/PARTIAL/UNKNOWN/NOT_RUN`，只描述该证据范围；`input_binding` 引用必要输入版本、哈希、工具链或环境绑定。没有统一索引需要时，工作项直接引用既有报告即可。

### A4 本地执行现场

```csv
batch_id,owner_id,coordination_path,assignment_id,work_item_id,candidate_id,progress,pending_message_ref,blocker,next_step
```

`state.csv` 每个当前分派一行：前三项定位批次、角色及公共目录；`assignment_id/work_item_id/candidate_id` 引用公共身份，不授权执行；`progress` 描述实际执行现场；`pending_message_ref` 指向待交接消息或其目录，多消息不拼 ID 列表；`blocker/next_step` 是执行方观察与下一步，协调结论仍以公共台账为准。

```csv
execution_id,assignment_id,operation,process_identity,started_at,status,output_path,cleanup
```

`executions.csv` 每个需恢复识别的执行一行：`execution_id/assignment_id` 绑定执行及分派；`operation` 为不含秘密的命令或操作摘要；`process_identity` 记录主机及 PID/开始时间/命令或工具会话身份等必要事实，尚未确认时显式 `UNKNOWN`；`started_at` 为带时区时间；`status` 为 `running/exited/UNKNOWN`；`output_path` 指向原输出；`cleanup` 说明待收尾、实际退出依据或有主保留，不能只凭父命令退出推定子进程已静止。

### A5 消息与回执

```csv
message_id,batch_id,owner_id,assignment_id,work_item_id,type,candidate_id,summary,report_path,resource_state,requested_action,corrects_message_id,created_at
```

消息文件仅一条记录：前五项标识消息及来源，非工作项紧急事件可空 `work_item_id`；`type` 为 `completed/blocked/resource_released/urgent`；`candidate_id` 指向所报告输入；`summary/report_path` 为简短结论及已保存的详细交接；`resource_state` 为 `active/quiescent/UNKNOWN`，不涉及资源时空，仅报告观察而非转移所有权；`requested_action` 明确所需协调动作；`corrects_message_id` 仅在更正或补充时填原 ID；`created_at` 为带时区时间。若尚无分派或候选的紧急事件，对应字段可空，但不能据此申请执行。

```csv
message_id,assignment_id,disposition,reason,next_assignment_id,handled_by,handled_at
```

回执仅一条记录：`message_id/assignment_id` 指向原交接；`disposition` 为 `accepted/duplicate/stale/needs_info`；`reason` 说明处理依据；`next_assignment_id` 是已持久化的下一分派，无则空，不证明已送达或完成；`handled_by` 为协调角色 ID；`handled_at` 为带时区时间。

## 附录 B：最小交接示例

以下均为示意数据，不是实际运行记录，也不创建这些目录。路径按批次根解析。修复负责人已保存报告，投递 `messages/inbox/fix/msg-00000000000000000000000000000001.csv`；引号包围的摘要含中文、逗号、双引号和换行，仍只有一条逻辑记录，长 ID 的前导零必须保留。

```csv
message_id,batch_id,owner_id,assignment_id,work_item_id,type,candidate_id,summary,report_path,resource_state,requested_action,corrects_message_id,created_at
msg-00000000000000000000000000000001,B-demo,fix,A-01,W-01,completed,C-01,"保存,读取已修复；保留""中文""
自测通过，独立验收未执行","local/fix/reports/保存,读取.md",quiescent,安排独立验收,,2026-10-12T19:30:00+08:00
```

协调者采纳后创建分派 `A-02` 给 `verify`；此刻测试资源尚未交接，工作项仍待验收，不能因修复交接完成而关闭：

```csv
work_item_id,scope,owner_id,assignment_id,assignment_state,dispatch_state,phase,acceptance_requirement,acceptance_result,blocker,candidate_id,input_binding_ref,evidence_ref,next_step
W-01,保存后读取,verify,A-02,active,DELIVERED,awaiting_acceptance,原失败和直接回归需独立验收,SELF_PASSED,等待独立测试资源,C-01,local/fix/reports/inputs.md,"local/fix/reports/保存,读取.md",资源明确交接后复验
```

处理回执保存到 `messages/processed/fix/msg-00000000000000000000000000000001.receipt.csv`，然后归档原消息；重复通知沿用此回执。新负责人核对 `roles.csv`、该工作项与资源台账后，才能按 `A-02` 接续。

```csv
message_id,assignment_id,disposition,reason,next_assignment_id,handled_by,handled_at
msg-00000000000000000000000000000001,A-01,accepted,仅采纳修复及自测证据；工作项未关闭,A-02,coordinator,2026-10-12T19:31:00+08:00
```

## 附录 C：规则场景核对

核对规则的接受、拒绝与恢复结论，不把场景推演宣称为调度程序或真实业务测试已通过。

| 场景 | 应有结果 |
|---|---|
| 正常投递与处理 | 报告先保存；工作项、候选、证据、回执及下一分派对应一致，关闭另按验收要求判断 |
| 临时文件或不完整 CSV | 忽略临时文件；拒绝把不完整正式文件作为交接依据，保留路径并请求补充 |
| 重复消息或同 ID 异内容 | 已处理交接不重复派发；同 ID 内容冲突不覆盖，待澄清 |
| 旧分派或旧候选结果迟到 | 保留历史，不覆盖当前工作项；原资源收尾另按现登记核验 |
| 协调者处理中断 | 对照台账、送达状态、本地执行与回执补缺步骤；不明派发不直接重发 |
| 上下文压缩或角色接替 | 从公共 CSV 和本地状态恢复有效分派、输入、权限与下一步；旧会话不能靠旧笔记继续 |
| 进程未退出、释放通知已到 | 核实际身份、子进程及保留边界，未明确交接不得开始冲突执行 |
| 部分验收完成 | 保留各证据范围，工作项记部分通过，不升级整体关闭 |
| 取消工作项时仍有活动执行或未知副作用 | 阶段记取消、分派撤回、验收结果保留；待执行汇总排除该项，资源与结果继续核对，不能因取消宣告已释放或成功 |
| 已取消项收到迟到完成消息或恢复旧会话 | 保留证据并核实遗留结果与收尾，不改为通过、不派发后继、不恢复原实施 |
| 仅撤回分派以更换负责人 | 保持工作项实际阶段，新分派接续，不误记为工作项取消 |
| 单负责人短时协助与独立负责人接替 | 默认使用任务说明、汇报和状态摘要；仅已采用本协议的批次按台账交接 |
| 跨批次资源冲突 | 找到唯一资源登记入口；归属不明时阻止冲突使用，不因目录不同放行 |
| 中文、逗号、换行、双引号与长 ID | 标准 CSV 往返后字段内容、逻辑记录数及字符串类型保持 |

完成判断：接手者仅通过公共 CSV 和自己的本地状态及其证据引用，能确定当前任务、有效分派、资源归属、可信证据和下一步；消息重复、迟到或通知遗漏不直接改变执行权或完成结论。
