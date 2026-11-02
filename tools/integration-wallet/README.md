# 独立模拟钱包

[wallet_service.py](wallet_service.py) 提供独立进程、独立 SQLite 的模拟钱包。它只接收已计价的累计结算目标，不读取适配器数据库，不重新计算供应商成本或客户价格。真实 SkillForge 用量由上游公开事件交付；本服务不接入真实钱包、支付或生产数据。字段及顺序语义见[钱包结算交付契约](../../docs/02-架构与设计/14-用量与账单设计.md#钱包结算交付契约目标)。

## 启动

复制 [config.example.json](config.example.json) 到被忽略的 `config.local.json`，将 `execution_tenant_id` 换为真实执行机构的十进制字符串，声明账户币种、初始余额和透支限额。账户身份由 `wallet_account_id/currency` 唯一确定；每个服务凭据通过 `token_env` 引用进程环境，固定 `caller_service_id` 及允许的账户集合，令牌值不写入配置。

在已提供 `SKILLFORGE_WALLET_SERVICE_TOKEN` 环境变量的终端运行：

```powershell
python -X utf8 -B tools/integration-wallet/wallet_service.py --config tools/integration-wallet/config.local.json --state tools/integration-wallet/.state/wallet.sqlite3 --port 8100
```

服务只监听 `127.0.0.1`，输出 `{"event":"wallet_ready","port":8100,"policy":"bounded_overdraft","simulated":true}`。测试可用 `--port 0` 让系统分配端口，并读取实际 `port`。首次配置开户，后续同配置重启只读取原账户及余额，不充值；已有账户的初始余额、币种、机构、名称或透支限额变更会拒绝启动。另一实验应使用明确的新状态文件，历史账本保留。

## HTTP 与结算

所有接口要求 `Authorization: Bearer <受控环境令牌>`。请求使用 JSON；响应沿宿主同形 envelope，包含 `code/message/data/trace_id`。金额使用精确分的十进制字符串，等价 `numeric(38,12)`，不接收浮点金额；独立钱包运算使用 80 位 Decimal 上下文。未知字段、重复 JSON 字段、非法指令闭包和超出请求预算会被拒绝。

| 接口 | 行为 |
|---|---|
| `POST /api/v1/settlements` | `Idempotency-Key` 必须等于指令中的 `instruction_id`；接收契约规定的固定指令及可选 `reconciles_rejection_id` |
| `GET /api/v1/settlements/detail?instruction_id=…` | 当前有账户权限的服务查询原回执；无回执返回 `404 NOT_FOUND`，不证明并发派发永不受理 |
| `GET /api/v1/accounts` | 仅列当前服务获准的账户 |
| `GET /api/v1/accounts/detail?wallet_account_id=…&currency=CNY` | 返回精确账户的机构、币种、初始余额、当前余额、透支限额及策略 |
| `POST /api/v1/admissions` | 按稳定 `admission_id` 及业务根检查当前后付费准入，幂等键等于 `admission_id` |
| `POST /api/v1/admissions/release` | 使用原准入完整字段结束该准入；不退款、不冲回实际费用 |

结算对象按全局 `object_id` 以及执行机构、钱包账户、币种、计量对象的唯一经济身份保护；`instruction_id` 和对象内序号也全局去重。不同获准服务读取或提交同一指令，返回同一回执；`caller_service_id` 只保留首次审计身份，不产生第二个收费域。用另一个对象 ID 表示同一经济对象会返回 409。钱包重新规范化参数并计算指纹，不信任调用方自报指纹；同键异参、同序异指令及改变既有对象范围返回 409。

钱包按连续序号和紧邻前序提交。前序未终结、尚未到达或跳号时返回 `202` 和 `waiting_predecessor`，保存原等待身份但不写永久拒绝、不改变余额或净扣款；原身份在前序完成后可以继续处理。成功和确定业务拒绝返回 `200`，并保留原终结回执。当前实际差额始终为累计应收目标减已确认净扣款；目标 100 成功后修订到 80，差额为 -20，余额返还 20；100 被拒后目标 80，差额仍为待扣 80。拒绝推进终结序号，但余额及已确认净额保持不变，不被当成已付款。

余额、对象净额、序号、账本和回执在钱包自己的事务提交。响应丢失后查询原指令，或按此钱包的原身份、原参数幂等恢复；不换新指令盲扣。适配器的真实用量去重、价格冻结、指令持久化及网络派发由适配器负责，不能把钱包幂等泛化为 Runtime 可以重放未知业务工具。

## 有界透支后付费准入

唯一启用策略为 `bounded_overdraft`。准入请求包含 `admission_id/execution_tenant_id/wallet_account_id/currency/business_root_ref/policy`，其中 `policy` 必须等于 `bounded_overdraft`。钱包原子检查当前余额是否严格大于负透支限额，并保存该次准入结果；同身份重读原结果，不重新作决定。响应明确 `reserved_amount="0"`。

这是后付费准入，不预留未计量费用：多个请求可以在相同可用余额下获得准入，已发生费用仍完整进入后续结算。实际扣款在钱包事务内串行校验，任何账户余额都不能低于负透支限额；超限指令产生原身份的确定拒绝，差额保留给适配器对账。`release` 仅标记业务根结束，不增加余额；它不保证已准入业务一定付清。不存在隐藏预占、自动充值或未经明确授权的重新结算。

## 定向验证

```powershell
python -X utf8 -B -m unittest discover -s tools/integration-wallet -p test_wallet_service.py -v
```

测试使用短生命周期真实 HTTP 和独立临时 SQLite，覆盖双服务经济去重、账户授权、序号与前序、拒绝及累计冲正、精确金额、并发、同配置重启、事务提交前回滚与提交后丢响应，以及无预占准入。`fault(stage,result)` 只供测试工厂注入，`before_commit` 在事务提交前调用，`after_commit` 在真实提交后调用；异常不会把已提交状态改写为拒绝。独立进程被杀后的恢复及适配器与钱包分别重启由组合验证负责，此模块通过不表示完整 GI-10 已验收。
