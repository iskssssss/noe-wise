# Java 代码组织与类型容器规范

本文是[工程规范](engineering-standards.md)的代码组织专题，定义宿主 Java 模块内的包、类职责与类型容器。对象语义归[Java 类型与边界映射](engineering-standards.md#java-类型与边界映射)，字段与更新语义归[数据库模型与字段规范](../../nw-database/references/database-modeling-standards.md)，注释归[代码注释](engineering-standards.md#代码注释)。

## 1. 适用边界与组织原则

采用“业务能力优先，内部按需分层”和“同一资源的类型按使用边界分别集中”。保持已批准的 Gradle 模块、领域归属和依赖方向；本规范不按数据库表新建业务模块，也不因目录调整改变业务行为。数据库表决定持久化映射，跨表查询、业务用例与公开 API 各有自己的契约。

规则优先级沿用工程规范。Runtime 的 SDK 交付、公开类型及根包、façade 与 Port/SPI 边界继续遵循 [Harness 02](../../../../harness-design/02-微内核插件架构实现思路.md)和 [Harness 03 §7.5–§7.6](../../../../harness-design/03-核心对象模型与状态机.md#75-agent-runtime-sdk-façade)，不按宿主容器规则统一嵌套或移动。供应商生成代码按所属生成流程维护。

本文表达新代码及后续明确模块改造的目标；目录和类型示例均不是当前实现清单。存量差异归[实施索引](../../../../docs/02-架构与设计/11-未实现功能清单.md#23-宿主代码组织与类型容器)，发布规范不证明现有代码已完成迁移。

## 2. 按业务能力组织目录

在所属模块内先按业务资源与能力归包。例如 Agent 的管理、模板、发布、挂载和部署各有明确归属，不把不同能力重新集中到模块级 `service`、`component`、`dto` 大目录。能力拆分依据是独立职责、变化原因和依赖关系，不以表数或文件数为门槛。

| 包或位置 | 职责与放置内容 |
|---|---|
| 宿主入口模块的 `<业务>.web` | Controller、HTTP 请求与响应、协议转换；机构和平台独有类型分别归各自入口模块 |
| 所属业务模块的 `<能力>.application` | 应用服务、用例编排、对象授权、事务协调与幂等处理 |
| `<能力>.contract` | 供其他模块调用的命令、查询条件与结果；不暴露数据库行、Entity/DO 或 HTTP 框架类型 |
| `<能力>.domain` | 具有业务不变量、状态迁移或领域行为的对象、值对象和策略 |
| `<能力>.persistence` | 独立 DO、数据库查询行、MyBatis Mapper、必要 Repository 与类型处理器 |
| `<能力>.integration` | 外部客户端、适配器与供应商协议转换，不接管领域状态和授权决策 |
| `<能力>.config` | 所属能力的配置绑定和装配，不承载业务流程 |

只建立实际存在的职责目录。单一职责且没有上述独立边界的小能力允许平铺；出现 HTTP、持久化或跨模块契约时建立相应子包。`domain` 不能成为所有数据对象的新收纳目录。测试在所属模块的 `src/test` 中按生产能力与包结构对应组织，跨模块夹具遵循工程规范。

公开应用入口可以是 `application` 中明确声明为对外契约的具体 Service；`contract` 集中相关公开数据类型，不要求把所有可调用类移入该包。公开入口及其参数、结果的类型闭包必须符合模块边界，Java `public`、Spring Bean 或所在包名都不自动授予跨模块使用权。

调用方只依赖所属模块明确公开的应用入口及契约类型，不直接使用其 Mapper、DO、Repository 实现或内部执行状态。HTTP 类型归入口边界，应用命令与结果归权威业务模块；业务授权与状态规则覆盖 HTTP、后台任务和内部入口，不靠移动 Controller 或重命名类取得隔离。

### 2.1 共享能力的归属

`skillforge-core-shared` 内部同样按业务能力归包。“两个模块都需要”不能单独成为迁入 shared 的理由：单领域类型由所属领域公开契约提供；共享能力需要稳定职责、明确消费者和不形成循环的依赖方向。只供平台使用的模型留在平台模块，机构专属 HTTP 类型留在机构入口。

不建立无领域边界的全局 `Models`、`Types`、`CommonDTO` 或业务工具容器。真正共享的传输契约按所属传输能力管理，不由其中一个调用方的 Controller 私有类型承担，也不把完整业务流程下沉到 common/shared。

## 3. 服务、组件与数据访问职责

| 命名 | 责任与使用条件 |
|---|---|
| `XxxService` | 表达业务用例或稳定业务能力，声明授权、事务及副作用边界；不把继承框架的通用 CRUD 服务作为跨模块业务入口 |
| `XxxRepository` | 封装确有价值的存储语义、聚合读写或存储适配；简单访问可由应用服务直接调用本模块 Mapper，不机械增加转发层 |
| `XxxMapper` | 专指 MyBatis 数据访问；SQL 仍维护在所属 Mapper XML 中 |
| `XxxAssembler` / `XxxConverter` | 前者组装面向消费方的投影，后者转换不同边界表示；不承担授权决策或状态迁移 |
| `XxxValidator` / `XxxPolicy` | 分别表达合法性校验与领域策略，归所属业务能力 |
| `XxxResolver` | 按已定义规则解析身份、版本或目标，不隐式扩大选择与回退权限 |
| `XxxScheduler` / `XxxWorker` | 分别表达调度与任务执行，沿用所属租约、幂等和恢复契约 |

Spring `@Component` 只是装配标记，不定义业务层级，也不要求建立统一 `component` 包或将所有独立职责命名为 `XxxComponent`。类名必须让调用者理解其具体责任；职责没有变化时，不通过多套近义名称建立重复入口。

接口只在真实契约、替换或依赖反转需要时建立，不强制配对 `Service/ServiceImpl`，也不为跨模块调用额外套一层空 Facade。权限、事务与状态迁移不能因拆类而失去原子边界，Repository 和辅助组件不得擅自提交调用方事务；数据访问细则继续归[数据库与迁移](engineering-standards.md#数据库与迁移)。

## 4. 按边界组织类型容器

### 4.1 默认命名

同一资源、同一边界的多个简单数据载体默认集中。以 Agent 管理为例：

| 类型类别 | 组织示例 | 所属位置 |
|---|---|---|
| HTTP 请求 | `AgentRequests.Create`、`AgentRequests.Update`、`AgentRequests.PageQuery` | 入口的 `web` |
| HTTP 响应 | `AgentResponses.Detail`、`AgentResponses.Item`、`AgentResponses.Page` | 入口的 `web` |
| 应用写入命令 | `AgentCommands.Create`、`AgentCommands.Update` | 所属能力的 `contract` |
| 应用查询与结果 | 存在类型族时用 `AgentQueries`、`AgentResults` | 所属能力的 `contract` |
| 持久化实体 | 独立的 `AgentDO`，与所属 Mapper 按同一能力管理 | `persistence` |
| 独立领域概念 | 独立的 `AgentStatus` 等类型 | `domain`，公开使用时符合契约闭包要求 |

嵌套名通过外层资源与边界表达上下文，不在内部重复全部前缀；引用含义不清时保留 `AgentRequests.Create` 这样的限定写法。单个类型无需专门创建容器；公共状态或值对象由所属领域定义一次，不在每个容器复制同名类型。

### 4.2 容器的边界

- 外层使用带私有构造器的 `final` 类，内部使用静态嵌套类或 record；容器不保存业务状态、不注册为 Spring Bean、不承载服务逻辑。容器只提供类型组织，不形成新的权威数据来源。
- record 适合不可变数据载体，集合等可变成员仍需遵守防御复制要求。不能因改为 record 或共用构造器而把未传与 NULL 合并、丢失精度或改变校验；部分更新继续采用[字段存在性规则](../../nw-database/references/database-modeling-standards.md#6-部分更新的字段语义)。
- Entity/DO、请求、响应、应用命令分别表达自己的字段范围和可变性；不通过继承实体或通用 `BaseDTO` 共享全部字段，不把创建人、租户等可信信息作为普通请求字段批量复制。
- 已有 `XxxContracts` 可承载边界明确的一组协议类型，但不能混入持久化行、内部执行状态或无关能力；`Types`、`Models` 不作为归属不明时的默认容器名称。历史名称本身不构成例外。
- 类型形成独立业务含义、被不同能力复用，或拥有独立状态和行为时，提取为顶层类型；抽取后仍归原权威领域，不自动进入 shared。公共 SDK 类型遵循所属契约，不为减少文件数改为容器成员。
- 容器出现多个独立能力时按能力拆分，例如 Agent 管理、发布与部署分别组织。不设统一行数或嵌套类型数量门槛，不因都关联 `agents` 表就合并所有类型。
- 持久化 DO 保持独立；只供所属数据访问使用的查询投影可按同一边界聚合，但不能作为跨模块结果直接返回。查询结果与数据库行即使字段暂时相同，也需明确各自契约和变化原因。

### 4.3 注释、序列化与转换

容器、嵌套类型和字段按[代码注释](engineering-standards.md#代码注释)补充非直观契约；外层已经表达的资源与边界不在每个嵌套成员重复，成员特有的约束不能遗漏。

对外嵌套类型使用明确且唯一的 OpenAPI schema 名称，例如 `AgentRequests.Create` 对应 `AgentCreateRequest`。内部短名可能重复，不能假定生成器自动拼上外层名称；实际改造必须核对生成 schema、引用及客户端类型。Java 包名和容器名不应泄漏为新的 JSON 包装层，字段、错误路径、ID 字符串及精确数值仍按原领域和通用契约表达。

转换显式处理字段存在性、可信身份、精度与可变性；依赖注入得到的身份不得从 DTO 反向覆盖。嵌套请求类不自动获得外层的 Jackson 命名或 Bean Validation 行为，需要核对实际嵌套类型及字段上的声明。没有语义差异的对象不为层数机械复制；不同边界或写权限不能以结构相似为由共用可变对象。

## 5. Agent 管理的目标组织示例

以下展示一个能力从 HTTP、应用契约到存储的完整组织关系，不是现有文件清单，也不新增接口或规定完整字段集合。现有 API、授权和发布契约仍决定具体字段与行为；目录名中的 `management` 表示此示例的管理能力。

```text
skillforge-institution-service/src/main/java/com/skillforge/institution/
└── agent/management/web/
    ├── AgentController.java
    ├── AgentRequests.java          # Create / Update / PageQuery
    ├── AgentResponses.java         # Detail / Item / Page
    └── AgentWebConverter.java      # HTTP 与应用契约的边界转换

skillforge-core-agent/src/main/java/com/skillforge/core/agent/
└── management/
    ├── application/
    │   └── AgentManagementService.java
    ├── contract/
    │   ├── AgentCommands.java      # Create / Update
    │   ├── AgentQueries.java       # 按管理用例组织查询条件
    │   └── AgentResults.java       # 应用查询与修改结果
    ├── domain/
    │   └── AgentStatus.java        # 复用所属领域的唯一状态定义
    └── persistence/
        ├── AgentDO.java
        └── AgentMapper.java
```

示例中的 `AgentManagementService` 是明确公开的应用入口，参数与结果使用所属契约类型。平台独有入口归平台模块的对应 `web` 包；模板、发布、挂载与部署是同领域的其他能力，各自按需要组织，不加入管理容器。此树只列管理能力，不能据此删除实际需要的其他职责；不存在的类型族和转换职责不照表新建。

| 边界 | 示例中的处理 |
|---|---|
| HTTP → 应用 | Controller 解析 `AgentRequests` 并执行入口校验；经显式转换生成 `AgentCommands` 或 `AgentQueries`，可信主体单独来自认证上下文 |
| 应用 → 存储 | 应用服务执行对象授权、状态与事务协调，经本模块 `AgentMapper` 访问 `AgentDO`；需要领域行为时使用所属 domain 类型 |
| 存储 → 应用结果 | 应用层按用例构造 `AgentResults`，不向入口模块返回 DO 或 Mapper 行 |
| 应用结果 → HTTP | 入口转换为 `AgentResponses`，Controller 按既有 `ApiResponse<T>` 和错误契约输出；分页等通用要求引用工程规范 |
| 后台任务或其他模块 | 调用同一公开应用入口并建立可信上下文，不借 HTTP DTO 或直接访问 Mapper 绕过业务规则 |

Mapper XML 仍位于所属模块的 `src/main/resources/mapper/`，namespace、结果类型与扫描配置随实际包调整同步。对应测试位于两个模块的 `src/test/java` 下，使用相应生产包；此组织示例不要求生成新测试框架或创建生产源码中的测试专用入口。

## 6. 后续改造与验收

实际代码重组按明确业务模块实施，同步直接消费者；不只移动文件，也不因结构改造顺带改变 HTTP 路径、字段、状态码、数据表或 Runtime ABI。需要改变领域契约时按所属任务单独明确范围。已有公开方法若泄漏 DO，应改为受控命令与结果并同步消费者，不能仅重命名包后宣称完成。

实际搬迁按涉及的运行入口核对引用与可见性、Spring/MyBatis 装配、序列化、校验或 OpenAPI/客户端，只检查受到影响的机制。授权、事务与字段存在性变化在对应业务边界验证；编译通过不代替这些证据。验证范围按[评审与验证规范](../../nw-verification/references/candidate-driven-delivery.md#测试路由)选择，不把上述机制变为每次重组必跑的全套检查。

纯规范交付检查链接、差量格式及下列代表性结论，实施记录明确区分文档验收与代码迁移完成：

| 场景 | 规则结论 |
|---|---|
| 同一 Agent 的创建、更新请求 | 可归同一个 `AgentRequests` 容器 |
| AgentDO 与创建请求 | 分别归持久化和 HTTP 边界 |
| 管理请求与内部冻结执行状态 | 分开归属，不因同属 Agent 就合并 |
| 单一职责仅有一个方法或类型 | 不创建完整分层或空容器；实际存在的边界仍遵循 §2 |
| 两个模块需要同一领域类型 | 从权威领域公开契约取得，不自动迁入 shared |
| 对象转换类命名为 Mapper | 按转换职责使用 Assembler/Converter |
| Runtime 已有公开值对象 | 遵循 SDK 契约，不统一嵌套 |
| 两个容器都有名为 Create 的对外类型 | 使用不同且明确的 schema 名称，核对生成引用 |
