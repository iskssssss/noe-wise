# 全项目验收工具

本目录提供受控环境的配置冻结、预算、真实业务/行为场景采集及原回执结算。运行前先准备独立环境与真实来源/配置绑定的 preflight；默认命令不授权付费调用或覆盖已有结果，未知派发不得通过重跑消除。

## Product 数据集

Product 场景必须显式提供已冻结的数据集目录与预期 fingerprint，没有历史目录回退，也不自动生成或重放数据。配置通过当前进程环境提供，并在免费 preflight 与付费 runner 中保持一致：

```powershell
$env:FULL_TEST_PRODUCT_DATASET = '<已冻结数据集的绝对路径>'
$env:FULL_TEST_PRODUCT_FINGERPRINT = '<独立核对的64位小写SHA-256 fingerprint>'
```

目录至少包含由正式冻结入口生成的 `dataset.json`、`candidates.json`、`validation.json`；存在生成回执或派生回执时一并保留。使用 `tools/chat_regression/behavior_dataset.py` 的 `load_dataset` 验证格式、来源与内容绑定，并核对调用者给定的 fingerprint。输入变化导致 preflight 来源摘要变化，必须重新核实。

`live_behavior_preflight.py` 在任何认证或网络调用之前校验数据集；`live_behavior.py --suite product` 在读取环境和登录之前校验，数据复制后再次核对冻结摘要。`--settle-only` 仅恢复原回执，不要求提供新的数据集，也不产生新模型派发。实际执行仍需要已准备的隔离环境、当前 `--ready-receipt` 与预算，不能仅靠以上变量启动生产或付费测试。

数据集与生成证据保存在私有且被Git忽略的独立目录，不提交真实对话或客户材料。通用行为工具用法见[聊天回归指南](../chat_regression/README.md)。
