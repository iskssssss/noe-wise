# 将运行时抽取为 Java SDK：API、SPI、Adapter 与 Starter

前置阅读：[33 Agent Runtime 的微内核与插件边界](33-Agent-Runtime的微内核与插件边界.md)。

> 按需进阶：本课适合需要把报告助手嵌入多个 Java 服务的读者。后续课程不依赖 Java 或 SDK 实现细节。

当研究与报告助手准备交给多个团队使用时，团队不希望复制一套调度循环，也不愿被迫采用同一种数据库或消息框架。把运行时抽成 Java SDK 的难点，不在于拆分 Maven 模块，而在于决定哪些语义可以承诺给宿主，哪些必须留在内部。

沿用统一教学案例：助手读取 `source-a@v1` 和 `source-b@v1`，比较关键词匹配与语义检索，为内部技术团队生成报告。A 只有“支持关键词匹配、批次更新”的材料，没有延迟测量；B 只有“支持语义检索”的材料，没有更新周期或性能测量。SDK 要让不同宿主都得到同样的证据边界，并把报告发布留在用户审批之后。

## API 先固定调用者真正需要的内容

公共 API 应描述提交输入、读取 Run 状态、请求取消和消费结果。它应使用不可变值对象，例如 `RunId`、`ActionId`、`Input` 和 `Outcome`；调用者不应传入事务、数据库连接或 Web 请求对象。这样，报告助手能从 HTTP 控制器、定时任务或命令行被调用，而不改变核心语义。

下面是示意接口，不是完整工程：

```java
interface RuntimeClient {
  Receipt submit(Input input);
  Snapshot inspect(RunId runId);
  CancelResult cancel(RunId runId, String reason);
}
```

`Receipt` 只说明输入是否已受理，不能承诺报告已经生成；`Snapshot` 则让宿主区分运行中、等待审批、已完成和结果未知。把这层语义做窄，才不会让框架对象渗入每个调用点。

## SPI 留给外部差异

SPI 适合身份校验、动作账本、模型调用、工具执行、队列与持久化等宿主差异。每个 SPI 都应说明并发、超时、幂等与失败分类。比如资料检索适配器收到网络超时，不能把原始异常抛给内核；它要翻译为“可重试”或“结果未知”，让内核按统一规则推进报告。

依赖方向可保持简单：API 只放宿主调用所需的值对象和入口；SPI 放内核需要、由外部实现的端口，并只依赖 API 中的通用值；核心依赖 API 与 SPI；JDBC、HTTP 等适配器实现 SPI；Starter 负责组装核心与适配器。业务代码编译时只接触 API，启动模块才引入 Starter。这样替换资料库不会反向拖动状态机，独立消费者也不会为了一个 `RunId` 引入数据库驱动。

```text
业务代码 ───────────────> sdk-api
                            ^
                            │
sdk-spi ────────────────────┘
   ^                         ^
   │                         │
adapter-jdbc / adapter-http  sdk-core
             ^               ^
              \             /
                   starter
                      ^
                   应用配置
```

`sdk-api` 与 `sdk-spi` 都不依赖 Web、ORM 或依赖注入框架，核心不知道数据库和 HTTP 驱动；adapter 翻译外部协议，却不能反向让 API 暴露供应商类型。Starter 在装配层选择实现并暴露客户端。若控制器直接接收 adapter 的返回对象，替换资料库时仍会把供应商语义扩散到业务代码，SDK 只是换了目录。

## 用一个装配例子检查依赖方向

下面的 Java 片段只是模块装配示意，不是可直接运行的完整工程。`EvidenceStore` 属于 SPI，HTTP 实现属于 adapter，工厂方法属于 Starter；业务服务只拿到 `RuntimeClient`。

```java
// sdk-spi：不出现 Spring、HTTP 客户端或供应商 DTO
public interface EvidenceStore {
  EvidenceResult query(EvidenceQuery query, RequestContext context);
}

// adapter-http：把外部协议翻译为 SPI 结果
final class HttpEvidenceStore implements EvidenceStore {
  private final EvidenceHttpClient client;

  HttpEvidenceStore(EvidenceHttpClient client) { this.client = client; }

  public EvidenceResult query(EvidenceQuery query, RequestContext context) {
    return EvidenceResultMapper.from(client.search(query, context.deadline()));
  }
}

// starter：选择实现并完成组装
RuntimeClient runtimeClient(RuntimeProperties p, EvidenceStore evidenceStore) {
  return RuntimeFactory.create(p.runtimeOptions(), evidenceStore);
}
```

当报告助手查询 A 时，adapter 可以返回“命中关键词匹配与批次更新，延迟测量缺失”；查询 B 时返回“命中语义检索，更新周期与性能测量缺失”。它不能因为供应商返回 HTTP 200 就把空命中翻译成性能较差，也不能把供应商的异常类交给模型。内核记录 Action、Attempt 和 Observation，Starter 不替模型选择 A/B，更不授予发布权限。

## Starter 管装配，不替业务做决定

Starter 可以依据配置装配默认实现，检查必要端口，并在缺少身份或账本时拒绝启动。它不应偷偷创建团队、选择资料范围或给报告开发布权限。报告助手的审批规则属于业务策略，不是自动配置的默认值。

装配还应区分“启动可用”和“某项能力可激活”。例如基础运行时能启动，但资料检索 adapter 缺少团队配置时，依赖它的 Skill 或模板必须在激活时给出明确原因，不能等到 Run 中途抛出类加载或空指针错误。公开 API 的二进制兼容也不等于语义兼容：即使旧调用者能编译，若把 `unknown` 改为 `failed`，恢复和重试规则仍可能被破坏。

## 测试边界要能抓住框架泄漏

API 测试应在不启动框架容器的情况下实例化值对象并编译一个最小调用者；SPI 契约测试对每个 adapter 重放相同场景：有命中、空命中、明确拒绝、截止时间到达和结果未知。核心测试使用内存 SPI，验证相同输入得到相同状态迁移；Starter 测试只验证装配、配置缺失和条件激活，不重复证明整个 Agent Loop。

一个典型失败是给 `RunId` 加 ORM 注解，又让 `RuntimeClient.submit` 接收 Web 框架的上传对象。API 单元测试可能仍通过，但命令行宿主必须引入 ORM 与 Web 包，框架已泄漏。另一种失败是 HTTP adapter 把超时一律映射为 `failed`，测试若只覆盖成功响应就发现不了；加入结果未知的契约用例后，错误映射会直接暴露。

练习：为“团队自带资料库”的场景定义一个 `EvidenceStore` SPI，并画出它进入 Starter 的装配路径。写出查询输入、命中文档版本、空命中、拒绝和未知结果，避免让接口返回某个 ORM 实体。

预期结果：SPI 的输入含访问范围与截止时间；结果能表示空命中、明确拒绝和未知；核心负责记录 Attempt 与提交 Observation；替换资料库实现时不修改 `RuntimeClient`，缺失能力会在装配或激活阶段被明确拒绝。

上一课：[33 微内核](33-Agent-Runtime的微内核与插件边界.md)｜下一课：[35 Skill 发布](35-Skill编辑校验与不可变版本发布.md)｜学习路线：[博客目录](README.md)
