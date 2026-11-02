# 缺失业务观测的最小回归夹具

`missing_observation_fixture.py` 从操作者提供的私有 FrozenModelRequest JSON 构造受控最终请求。保留首条生产 SYSTEM、answer-phase、模型绑定、生成配置和 NativeConversation 正文响应契约，并从最后一条包含 finalizing 结构化回执的 TOOL 消息中原样提取 instruction；缺失、空白或同条消息出现多个候选时拒绝准备，不添加夹具自己的收尾规则。替换原会话为合成历史：读取未展示的附件、计算并生成文件、脚本成功但没有业务结果、当前修订仍为 finalizing。历史调用没有实际执行，文件字节、合计和差额均未提供。上一版已发布不能证明当前修订已发布。

生成的 fixture-manifest.json 记录原始输入字节与夹具的 SHA-256、精确保留的部分、替换范围、零次 Provider 调用和证据边界，不包含原始绑定值或来源路径。合成代码使用 inline/python、合成附件选择器、对应 /input/data 路径及输出目录创建；准备测试仅编译检查其 Python 语法，不执行附件读取或计算。

在仓库根目录执行离线测试：

```powershell
python -m unittest discover -s tools/chat_regression -p test_missing_observation_fixture.py -v
```

准备新夹具，`--source` 必须指向当前待验证候选的最终冻结请求，而非旧的历史夹具：

```powershell
python tools/chat_regression/missing_observation_fixture.py --source <私有最终冻结请求.json> --output .runtime-logs/missing-observation-new
```

输出目录必须不存在。生成的 fixture.json 继承来源中的私有绑定和 SYSTEM，budget.json 绑定其 SHA-256；两者只存放忽略目录，不纳入 Git，不公开原请求。工具不解析凭据、不查数据库、不调用 Provider，也不自动启动运行器。离线检查覆盖 NONE/无工具的 NativeConversation 正文收尾请求、历史调用与回执配对、摘要和独立预算；字节数加固定余量仅为保守预留，不是 tokenizer 计数或实际消费。默认最多一次、50000 token 预留、4096 输出 token 上限、120 秒；来源输出上限大于预算时准备失败。

真实 Provider 验证须有覆盖实际调用的任务授权，已有授权继续有效；使用当前正式 codec/Provider 入口和当前受控绑定解析。稳定协议标识必须通过 `ModelProtocol.fromId`、`AuthenticationScheme.fromId` 解析；不能使用枚举 `valueOf`。应先做无发送准备检查，再冻结请求与独立预算，并用不可覆盖的单次 attempt 标记阻止重复执行。准备失败记录 NOT_SENT；发送后结果不明记录 UNKNOWN，不自动重试，也不能用新成功覆盖历史未知。记录实际 usage，缺失用未知，不补零。本工具不提供另一套私有连接或 Provider runner。

模型答复需人工语义核对：必须明确没有观测到业务数值、不能给出可信合计和差额；不能根据 exit_code=0、类型断言或文件生成成功推断业务结果；不能编造读取、证据引用或当前修订发布/下载状态；只能直接输出回答正文，不能在冻结后继续工具。仅检查 JSON 形状或关键词不代表这些语义通过。

即使真实 Provider 样本通过，其证据只支持合成历史上的最终决策行为，不支持真实附件挂载、沙箱执行、Runtime/ModelPort 受理、发布、UI、业务计量账本或生产可发布结论。准备检查通过只能报告 `PREPARED_OFFLINE`，真实调用和语义检查仍为 NOT_RUN。
