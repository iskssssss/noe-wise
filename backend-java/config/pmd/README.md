# 阿里 Java 规约的 Java 21 静态门禁

本项目以《Java 开发手册（黄山版）》为主要审查基准。数据库实际使用 PostgreSQL / pgvector；MySQL 专属引擎、字符集和函数规则不直接迁移到本项目。

执行 `./gradlew alibabaStaticCheck`（Windows 使用 `./gradlew.bat`）。普通 `build` / `check` 也执行生产 PMD 检查；根 `check` 增加规则正反例与 SQL 显式投影门禁。PMD 是独立分析配置，不进入应用或 SDK 的运行依赖。

## 来源与覆盖边界

- [Alibaba P3C 官方仓库](https://github.com/alibaba/p3c/tree/6c59c8c36ecd8722c712d5685b8c3822c1c8b030)和该提交中的[黄山版手册](https://github.com/alibaba/p3c/blob/6c59c8c36ecd8722c712d5685b8c3822c1c8b030/Java开发手册(黄山版).pdf)。
- 官方 `p3c-pmd:2.1.1` 依赖 PMD 6.15.0，不能可靠解析本项目的 Java 21 record、模式匹配等语法。官方规则全量执行的解析错误必须单列，不能记为通过。
- 本门禁使用版本目录固定的 PMD 7.28.0，执行 `alibaba-java21.xml` 中 **48 条标准规则和 1 条项目 XPath 规则**。它是黄山版正确性要求的可执行子集，不宣称复刻全部官方 P3C 规则。
- 评估全分类时，PMD 7.28.0 的 `NonThreadSafeSingleton` 在模式匹配代码上触发 `Empty node stream`。该规则未纳入本门禁；单例共享状态、锁、缓存和初始化仍需结合源码及并发测试审查。其他规则照常分析这些文件，没有整文件排除或历史告警基线。
- 生产源码按各 Gradle Module 的实际编译 classpath 分别分析。测试源码由编译、JUnit 与专门的规则正反例验证；`pmdTest` 不套用生产规约。

## 规则映射

| 黄山版主题 | 执行检查 | 仍需源码和行为证据的部分 |
| --- | --- | --- |
| 金额与 OOP | BigDecimal 浮点构造、equals/hashCode 配对、字符串引用比较、包装类构造、空值比较 | 金额精度/舍入、业务等值语义、可变对象作为集合键 |
| 集合 | toArray 类型、集合元素类型、空集合判断、双括号初始化 | 循环查询、快照隔离、业务去重与顺序 |
| 并发 | Executors 资源策略隐藏、直接 Thread.run、双检锁、静态非安全 formatter、notify/notifyAll | 有界排队、拒绝与停机、租约/CAS、ThreadLocal 清理、分布式原子性 |
| 流程控制 | 空检查错误、隐式 switch 穿透、重复分支、finally return、无条件 if | 终态优先级、故障分类、幂等与重试边界 |
| 异常日志 | printStackTrace、错误异常日志调用、catch 变量重赋值、重复 logger | 敏感内容、必要上下文、安全栈、异常是否真正被吞掉 |
| 可重复性 | 明确字符集、大小写转换 Locale、日期解析 Locale、JDK API 使用 | 外部协议、时区与持久化时间语义 |
| 数据访问 | 生产 Mapper XML 和 Java JDBC SQL 字面量中的 `SELECT *` / `alias.*` | 租户归属、RLS、索引、事务、topK 前授权过滤、执行计划 |
| 安全 | 固定加密 IV | 密钥来源、鉴权、防重放、路径与出网边界 |

`COUNT(*)` 不是全列投影。PL/pgSQL 的 `%ROWTYPE` 事实校验、数据库函数和视图属于数据库内部契约，由数据库测试审查，未伪装成 ORM 扫描覆盖。

## 防止门禁失效

`verifyAlibabaRuleFixtures` 同时执行正例与负例。正例必须无违规；负例必须恰好触发线程池工厂、BigDecimal 浮点构造、equals(null) 和默认 Locale 四类告警。缺失规则、规则执行错误或额外告警会使验证失败。负例任务允许产出告警报告，最终验证任务仍严格判定期望集合。

PMD XML/HTML 报告位于各模块 `build/reports/pmd/`，规则夹具报告位于根构建的同目录。处理错误和违规均不能作为静态检查通过。风格提示如作者标签、每个 Service 配 `Impl`、每个 HashMap 改 ConcurrentHashMap 不机械执行；需要按实际职责、线程归属和维护收益决定。
