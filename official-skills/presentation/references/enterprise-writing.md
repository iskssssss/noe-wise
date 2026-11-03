# 企业演示内容组织

本文件包含经调整的 Anthropic internal-comms 材料，来源 commit 为 `683bc88e56f3e09ba94f7055977f3d3aa499f202`，适用 [Apache-2.0 许可证](upstream/internal-comms/LICENSE.txt)。本文使用现有 PPTX 页型；示例不授予外部工具访问或发送权限，也不要求逐项询问；发布仍需授权。

先确定当前受众、讲述目标与时间范围。每页一个主张，标题表达结论；复杂事实按内容拆页。已有资料足够时直接组织，缺失事实标“待确认”，示例值不写成业务成果。用户的机构模板、事实与格式要求优先。

| 演示任务 | 叙述顺序 | 现有 slide 映射 |
| --- | --- | --- |
| 周报、项目状态 | 总体进展 → 已完成 → 下一步 → 阻塞与协作 | title、bullets、two-column、summary；见 [内容示例](upstream/internal-comms/examples/3p-updates.md) |
| 管理层汇报 | 当前结论 → 关键事实与影响 → 风险 → 决策请求 | title、已有真实数据的 metrics/chart、table、summary；未知指标不创建数字图表 |
| 项目更新 | 目标 → 已交付证据 → 计划 → 问题与依赖 | section、bullets、two-column；计划与已完成分开表达 |
| FAQ 或内部宣讲 | 重要问题 → 答案 → 适用范围或来源 | 每问题一页 bullets 或相近问题的 two-column；见 [FAQ 示例](upstream/internal-comms/examples/faq-answers.md) |
| 事件复盘 | 影响 → 已知时间线 → 处置 → 原因状态 → 后续行动 | bullets、table、summary；待调查原因不得写成定论 |

每条关键事实来自当前输入或实际已读取的授权资料；保留已有来源日期与适用范围。判断附依据，未知写待确认，不能用专业排版掩盖未经核实的结论。行动页只写已知责任人与期限，不推断动作已经执行；信息不足时仍可形成带明确空缺的草稿。

参考 [周报演示输入](../examples/presentation-weekly-update.json) 将内容转换为 `schema_version: 2` 的支持页型，再按 [输入契约](input-schema.md) 和 [质量检查](quality-checks.md) 生成并渲染。写作模板不改变成果格式、权限或交付生命周期。
