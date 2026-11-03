# 通用 Skill 构包、发布与验收工具

本目录维护仓库根目录 `official-skills/` 中十个可独立发布的官方通用 Skill 的固定来源、确定性构包、依赖核验、独立真实模型验收及触发/效果评测。输入与支持范围以各包的 Skill、manifest 和参考文件为准；执行与成果生命周期沿用[代码执行与产物设计](../../docs/02-架构与设计/12-代码执行沙箱与产物交付.md)；构包与验收结果应以当前版本执行生成的报告为准。该组 Skill 补强的交付边界不包含 Git commit 或 push，构包、Skill 版本发布与 Git 交付分别记录。

| Skill | 精确版本 | 入口与依赖 |
|---|---|---|
| [document](../../official-skills/document/SKILL.md) | `2.1.7` | Python；Word 冻结 3 个完整依赖，新增企业内容组织参考 |
| [spreadsheet](../../official-skills/spreadsheet/SKILL.md) | `2.0.5` | Python；冻结 2 个完整依赖，公式重算需镜像中的 LibreOffice Calc |
| [presentation](../../official-skills/presentation/SKILL.md) | `2.1.9` | Node；冻结 19 个完整依赖，企业内容组织和十套受管主题 |
| [web-app](../../official-skills/web-app/SKILL.md) | `2.1.9` | Python HTML 无第三方依赖；React 备用 Node 入口冻结 6 个完整依赖，支持统一主题 |
| [code-delivery](../../official-skills/code-delivery/SKILL.md) | `1.1.6` | Python 标准库；明确文件清单的源码交付包装器 |
| [configuration](../../official-skills/configuration/SKILL.md) | `2.0.5` | Python；JSON/YAML 与 JSON Schema 校验，冻结 7 个完整依赖 |
| [document-read](../../official-skills/document-read/SKILL.md) | `1.0.4` | Python；不动态安装依赖，使用镜像固定 Docling、OCR 与本地模型 |
| [webapp-testing](../../official-skills/webapp-testing/SKILL.md) | `1.0.4` | Node；`playwright-core==1.62.1` 与镜像预装 Chromium，执行时断网 |
| [research](../../official-skills/research/SKILL.md) | `1.0.7` | 受管 `run_code` 保存 Tavily 免费 search/extract 原始 HTTP 响应，Python 标准库保留 corpus、回读正文与生成报告 |
| [mcp-builder](../../official-skills/mcp-builder/SKILL.md) | `1.0.6` | Python MCP SDK `1.26.0`；冻结 29 个完整依赖，真实 stdio 协议验证后交付源码 |

构包以 `SKILL.md` 的 `metadata.version` 作为 ZIP 文件名与官方发布版本；manifest 若含顶层 `version`，应与其对齐，该字段不选择 Runtime 入口。平台通用编辑器的 `version_key` 独立于包作者版本；官方构包发布工具显式沿用作者版本。

## 八个补强方向与事实源

| 方向 | 当前实现与契约导航 |
|---|---|
| 企业内容组织 | [文档写作](../../official-skills/document/references/enterprise-writing.md)、[演示写作](../../official-skills/presentation/references/enterprise-writing.md)，将事实、判断、计划、未知事项映射到现有生成器 |
| 主题一致性 | [演示主题](../../official-skills/presentation/references/theme-presets.md)、[网页主题](../../official-skills/web-app/references/theme-presets.md)，同名 preset 的十套包内资源 |
| 配置 Schema 校验 | [配置契约](../../official-skills/configuration/references/input-schema.md)，Schema 自身校验和配置结构校验 |
| 文档读取、结构化解析与 OCR | [读取契约](../../official-skills/document-read/references/input-contract.md)，明确附件/成果挂载、页范围、正文片段和本地模型 |
| 网页自动验收 | [网页检查契约](../../official-skills/webapp-testing/references/check-contract.md)，受控动作、断言、逐项状态和截图 |
| 搜索与多来源研究 | [研究证据契约](../../official-skills/research/references/research-contract.md)，程序保存原始 responses、规范化 corpus、按预算回读正文、来源引用与部分失败 |
| Skill 触发与效果评测 | [评测工具](evaluation/README.md)，冻结基线/候选 Agent 后比较实际触发、断言、用量与时间；不作为运行时 Skill 发布 |
| MCP 接口开发 | [MCP 开发契约](../../official-skills/mcp-builder/references/input-contract.md)，编译及官方 SDK initialize/list/call 验证；不替换平台 MCP Runtime |

配置必须用 `schema_file` 指向本次显式挂载的 JSON/YAML Schema，不支持内联 `schema` 字段。Schema 来源要区分目标系统正式契约与用户自定义约束，校验通过不等于业务正确或配置已应用。document-read 拒绝 Office 嵌入对象；含嵌入数据工作簿的原生图表 PPTX 也在拒绝范围内，不能将 presentation 生成的这类 PPTX 当作可读取原件验收。普通无嵌入对象 PPTX 的文字/表格读取、Office 公式缓存和 OCR 的限制以读取契约为准。

## 工具入口与运行前提

以下命令均在仓库根目录执行，Python 使用 `-X utf8 -B`。构包只需标准库；真实聊天验收需 `requests`。浏览器脚本通过前端依赖解析 `@playwright/test`，需要 Playwright Chromium。脚本不启动服务、不配置模型、不初始化开发机构，也不自动构建镜像；环境准备按[本地启动入口](../../docs/04-指南与运维/01-开发与部署指南.md#53-本地启动)、[沙箱运行指南](../../docs/04-指南与运维/09-代码执行与产物运行指南.md)与[部署说明](../../deploy/sandbox/README.md)。

| 入口 | 用途 |
|---|---|
| [package_skills.py](package_skills.py) | 校验固定来源、完整依赖、包文件索引并构建 ZIP；只有显式 `--apply` 调用平台发布 API |
| [verify_dependencies.py](verify_dependencies.py) | 比较真实 Runner 安装报告与依赖锁的完整集合、版本、下载地址和摘要 |
| [verify_skill_sandbox.py](verify_skill_sandbox.py) | 通过现有沙箱内部接口提交、查询、下载明确样例；允许显式替换输入和挂载原件 |
| [verify_strengthened_skills.py](verify_strengthened_skills.py) | 第二组十包的 original/candidate 独立验收 Agent、六类任务、原 Office 恢复及正式下载 |
| [evaluation/run_evaluation.py](evaluation/run_evaluation.py) | 已发布 Agent 的配置冻结、真实请求、采集、取消和比较报告 |
| [verify_research_live.py](verify_research_live.py) | Tavily 免费 search/extract 的真实供应商 HTTPS 检查；不代替平台 Agent/http_request 组合验收 |
| [verify_web_browser.mjs](verify_web_browser.mjs) | 标准 HTML/React 样例的断网交互、桌面/窄屏、键盘、日志和截图 |
| [verify_live_browser.mjs](verify_live_browser.mjs) | 第一组四包专用产品页面用例；默认目录和选择器不可直接用于第二组 |
| [verify_live_skills.py](verify_live_skills.py) | 第一组四包验收及第二组复用的受理/下载辅助；第二组从 strengthened 入口调用 |

模块回归入口包括 [配置](test_configuration_skill.py)、[文档读取](test_document_read_skill.py)、[网页检查](test_webapp_testing.mjs)及[真实沙箱检查](test_webapp_testing_sandbox.py)、[研究](test_research_skill.py)、[MCP 开发](test_mcp_builder_skill.py)、[评测](test_skill_evaluation.py)、[Python 内容/主题](test_content_theme.py)与[Node 内容/主题](test_content_theme.mjs)。已有[文档](test_document_skill.py)、[表格](test_spreadsheet_skill.py)、[演示](test_presentation_skill.mjs)、[网页](test_web_skill.py)回归继续使用，各脚本的实际参数以 `--help` 或源码入口为准，不要求每次变更全部执行。

## 固定来源与镜像依赖

[upstream-lock.json](upstream-lock.json) 保存实际采用文件的仓库、完整 commit、路径、SHA-256、许可和适配说明；构包校验本地固定文件，不从 `main` 下载新内容。上游资源与本平台执行/授权契约分开，Bash、动态安装、外部登录、额外确认和自动重试不能直接继承。

摘要字段分别记录原始来源与当前包：`raw_sha256` 保留原上游原始字节摘要；有平台改写的文件将原上游换行归一摘要保存在 `upstream_normalized_sha256`，`normalized_sha256` 则校验当前已核对的平台适配文件（按构包规则统一 LF）。外部仓库、commit 和 source_path 继续标识原始来源，不能用更新后的本地摘要冒充上游原文；source 的 `adaptation` 说明实际改写范围。未改写文件仍由原 `normalized_sha256` 校验。更新来源记录前须核对实际差量、所属契约与授权，不能对未知源码变化批量刷新摘要。

| 来源 | 固定 commit | 采用范围与许可 |
|---|---|---|
| [Anthropic Skills](https://github.com/anthropics/skills) | `683bc88e56f3e09ba94f7055977f3d3aa499f202` | frontend-design、web-artifacts-builder、internal-comms、theme-factory、webapp-testing 和 mcp-builder；采用目录 Apache-2.0 许可随相应包保留 |
| [Docling](https://github.com/docling-project/docling) | `3181d9fcbb8b7568ceba14b7ed5cd21f66b221e7` | 官方 Skill 与参考资料，MIT；执行入口独立适配为固定本地解析 |
| [Tavily Skills](https://github.com/tavily-ai/skills) | `778122e5f9c680f541eeceda5a5b36405eb7980c` | search/extract 参考与 MIT 许可，执行适配为受管 HTTP 和平台模型综合研究 |

Office 生成脚本为 灵知 自行实现。配置校验采用 jsonschema 库，精确版本与完整依赖由 [dependencies-lock.json](dependencies-lock.json)管理。该锁面向 `linux-x86_64`、Python `3.12` 与 Node `24`；manifest 的动态安装声明必须覆盖锁内完整依赖图，包括传递依赖，web-app 比较 React 备用入口。默认 HTML、research、code-delivery 不安装第三方包。

document-read 的 `dependencies: []` 表示不动态安装，其实际能力依赖[运行镜像](../../deploy/sandbox/runtimes/Dockerfile)、[固定 Docling wheels](../../deploy/sandbox/runtimes/docling-requirements.txt)、[TableFormer 附加 wheel](../../deploy/sandbox/runtimes/docling-extra-requirements.txt)和[模型锁](../../deploy/sandbox/runtimes/docling-models.lock.json)。镜像构建使用明确 wheel 地址及 hash，准备 Tesseract 简体中文/英语和固定 Heron/TableFormer 模型；运行时离线，不下载模型或调用远程解析。Node 镜像预装与 Playwright `1.62.1` 配套的 Chromium，执行时不安装浏览器。LibreOffice、Poppler 与 Noto CJK 同样是镜像能力，不能从包依赖为空推断环境已满足。

包文件清单由[官方包索引](../../official-skills/index.json)维护；测试、缓存、安装目录和模型权重不进入 Skill ZIP。升级来源、动态依赖或镜像时核对对应许可与差量，同步冻结输入和受影响验收，不把本机安装成功写成真实 Runner 或镜像验收通过。

## 确定性构包与显式发布

```powershell
python -X utf8 -B tools/skills/package_skills.py --output .agent-work-state/skill-strengthening/packages-check
```

不传 `--apply` 不调用平台 API。包路径、索引和大小先校验，文本统一 LF，ZIP 条目排序、时间戳与权限固定；同输入和同 Python/zlib 环境应产生同 ZIP SHA-256。报告含精确版本、包与文件摘要、依赖锁摘要。`--skill` 可重复传入选择单包；一次调用重写该目录的报告，纯构包检查使用独立目录，避免覆盖发布回执。

十包独立验收的 `setup` 要求 `--packages-report` 明确指定本批目录中同时含全部十包及发布记录的报告；单包报告或第一组四包报告不能替代。发布需授权目标平台，执行进程通过安全方式注入 `SKILLFORGE_PLATFORM_TOKEN`，不在命令、文档或报告写 token 值：

```powershell
python -X utf8 -B tools/skills/package_skills.py --output .agent-work-state/skill-strengthening/packages --apply --platform-url http://127.0.0.1:8100 --token-env SKILLFORGE_PLATFORM_TOKEN
```

平台启动时零预置 Skill：源码包不进入应用资源、不自动创建平台定义，也不默认挂载到 Agent。`--apply` 查询平台公共目录；缺少 `skill:<名称>` 时，通过正式 definitions API 创建 `SKILL` 定义，再执行草稿创建、更新、校验与发布。已有且唯一的同名平台定义直接复用，不创建重复定义。草稿校验后按 revision 发布，回读冻结正文。既有已发布版本只有完整文件集合一致、文本仅统一 LF 且其他文件保持原字节后内容完全一致时才能复用；回执用 `content_comparison: exact/utf8_lf` 区分原始字节相同与仅换行差异，并记录真实冻结字节的逐文件 `frozen_file_sha256`，不改写已发布字节。新发布仍要求回读正文与本次构包文件逐字节 `exact` 一致；同版本换行以外的内容或文件集合差异、其他编辑草稿都拒绝覆盖。内容变化必须使用新版本。发布通过 Catalog 推进该定义的当前默认版本；机构或 Agent 通过能力目录选择稳定定义，管理服务解析当前发布版本；已有挂载及已受理执行继续以各自的持久或冻结事实为准，不自动替换。`--apply` 不修改业务 Agent 挂载，不代表 Git commit、push、生产部署或真实用例通过。

创建定义、创建/更新草稿与发布均在写操作前由 journal 记录 `unknown`，收到响应后记录 `observed`。未知结果按原定义、版本及草稿查询核对，不删除 journal、换版本号或重发请求。平台发布、安装、模型执行、正式下载和视觉检查分别形成证据。

## 零预置发布与版本冻结验收

[SkillLifecycleHttpIntegrationTest](../../backend-java/skillforge-institution-service/src/test/java/com/skillforge/institution/integration/SkillLifecycleHttpIntegrationTest.java) 使用独立 PostgreSQL/Redis、正式管理与聊天 HTTP API 和本地确定性模型，覆盖空目录、在线编辑、ZIP 导入、校验失败、发布、固定版本挂载、运行中版本冻结、显式升级、停用与权限拒绝。提供本批独立沙箱的 `CODE_EXECUTION_SMOKE_ENDPOINT` / `CODE_EXECUTION_SMOKE_TOKEN` 时，同时执行冻结包的 Python 脚本并核对辅助资源；未提供时不作为真实沙箱执行证据。

[verify_skill_workbench_p0.mjs](verify_skill_workbench_p0.mjs) 通过两端真实页面完成创建、ZIP 导入、填写平台版本号、保存、校验、发布与回读。以私有 JSON 文件路径为唯一参数，配置 `isolated: true`、`evidenceDirectory`、`zipPath`、唯一 `runKey` 和隔离双宿主的 `harnessFile`；可复用 [InstitutionManagementBrowserHarnessTest](../../backend-java/skillforge-institution-service/src/test/java/com/skillforge/institution/integration/InstitutionManagementBrowserHarnessTest.java) 生成的 `ready.private.json`。账号仅在内存登录，脚本自行管理动态端口 Vite 与浏览器；不执行包内脚本。失败恢复设置 `resume: true` 并保留原报告与同一运行标识，已发布对象只回读，不重复发布。

[verify_skill_workbench_empty_ui.mjs](verify_skill_workbench_empty_ui.mjs) 单独检查空态、加载、错误、权限、编辑区留白和窄窗口布局；报告明确标为 `UI_MOCK_READ_ONLY`，不能替代上述真实发布或 Runtime 执行证据。模型夹具也不代表真实供应商的理解与生成效果。

版本冻结先运行 `PersistentCapabilityRegistryTest`、`CapabilityMountResolverTest` 和 `JdbcSessionToolCatalogTest`，确认精确挂载与准入查询后，再运行 HTTP 生命周期。程序 RPC 则先验证工具契约、计量归属与事务参与的局部回归，再运行 [ChatExecutionProgramE2ETest](../../backend-java/skillforge-institution-service/src/test/java/com/skillforge/institution/integration/ChatExecutionProgramE2ETest.java)。其主模型与交付要求用途模型分别配置；普通同步模板工具显式覆盖基类的交互测试替身，不能依赖隐含装配。

真实链路失败按首个偏离边界定位；持久 `FAILED` / `UNCERTAIN` 结束等待并保存有界执行事实，完整模型请求仅保存在本地证据目录。失败日志与后续成功样本分开保留，未知动作不重放。已有发布、生命周期或页面证据只有在相关代码、配置或夹具变化时才重跑。

## 真实沙箱执行与依赖核验

[verify_skill_sandbox.py](verify_skill_sandbox.py) 只访问 `--service-config` 指定的隔离loopback控制面，或由进程显式注入 `SKILL_SANDBOX_ENDPOINT` / `SKILL_SANDBOX_TOKEN`；不读取根环境文件或默认接管18200。必须提供 `--batch-id` 或 `FULL_TEST_BATCH`。每个新输入使用独立私有目录；同一未知任务恢复保持原目录、参数和 `task_id`：

```powershell
$skillEvidenceRoot = '<本批私有证据目录的绝对路径>'
$skillSandboxConfig = '<本批私有endpoint/token JSON绝对路径>'
$skillBatch = '<明确批次ID>'
python -X utf8 -B tools/skills/verify_skill_sandbox.py --service-config $skillSandboxConfig --batch-id $skillBatch --skill document --example examples/document-word.json --output "$skillEvidenceRoot/document-word" --wait 45
python -X utf8 -B tools/skills/verify_skill_sandbox.py --service-config $skillSandboxConfig --batch-id $skillBatch --skill presentation --example examples/presentation-weekly-update.json --output "$skillEvidenceRoot/presentation-weekly" --wait 45
python -X utf8 -B tools/skills/verify_skill_sandbox.py --service-config $skillSandboxConfig --batch-id $skillBatch --skill web-app --example examples/site-themed.json --output "$skillEvidenceRoot/themed-html" --wait 45
python -X utf8 -B tools/skills/verify_skill_sandbox.py --service-config $skillSandboxConfig --batch-id $skillBatch --skill mcp-builder --example examples/mcp-server.json --input-name mcp-server.json --output-format source --output-file mcp-server/server.py --output-file mcp-server/requirements.txt --output-file mcp-server/README.md --output "$skillEvidenceRoot/mcp" --wait 45
```

`--example` 必须在 manifest 声明，`--entry` 选择备用入口；`--input-name` 指定安全控制文件名，`--input-json` 显式替换样例输入，`--output-format` 选择 manifest 输出格式。`--artifact 安全目录/文件=本地文件` 可重复传入，挂载至 `/input/artifacts/`；控制文件内引用必须一致。每次重新挂载原件、Schema、HTML 等，不继承上次工作区，不把包内示例当用户数据。网页检查先根据真实 HTML 的 DOM 编写计划，读取原件的 PDF/PPTX 等也须符合各自拒绝范围。

例如 configuration 的示例引用 `/input/data/schema.json`，不能只传控制文件后假定 Schema 已挂载；通过 artifact 参数挂载时先用明确替换输入将 `schema_file` 改为对应 `/input/artifacts/<目录>/schema.json`，再执行。document-read 默认只回读正文，webapp-testing 默认回读逐项检查；STANDARD执行自动读取固定 `/workspace/output/result.json` 中的类型化结果，stdout 不作为模型已读正文或图片的通道。manifest 的 `expects_typed_results` 仅声明验收期望，不选择路径，也不进入执行请求；inspect分支同样要求真实类型化结果。

提交前保存批次、endpoint、输入指纹与任务 ID；`queued/running` 是活动状态，`--wait 0..50` 超时只表示原任务仍在运行；相同参数重跑查询原任务。只有严格类型化 `NOT_SUBMITTED` 才认定受理拒绝，普通HTTP/传输错误保存unknown，unknown不读取文件/日志、不重放。输入、脚本、依赖或输出变化使用新的独立目录，未知任务不能借换目录重复提交。脚本要求 `completed`、退出码 0 且无交付错误，并核对下载文件清单、大小和 hash；网页检查报告的 `failed/error` 仍表示验收未过，脚本退出成功或截图存在不等于业务和视觉通过。

动态依赖证据由真实安装容器生成，位于任务卷 `workspaces/<task_id>/evidence/dependencies.json`；服务固化其摘要，执行容器不挂载 evidence，详见 [TaskWorkspace](../../backend-java/skillforge-sandbox-service/src/main/java/com/skillforge/sandbox/execution/TaskWorkspace.java)、[Python Runner](../../deploy/sandbox/runtimes/python-entrypoint.sh)和[Node Runner](../../deploy/sandbox/runtimes/node-entrypoint.sh)。管理员按原任务 ID 只读导出并核对固化 SHA-256，再比较：

```powershell
python -X utf8 -B tools/skills/verify_dependencies.py --skill configuration --report '<真实Runner导出的dependencies.json绝对路径>'
```

不能使用 manifest、本机安装报告或另一任务证据代替。当前动态依赖锁是完整安装结果比较，不能描述为全部生产安装自动应用 npm `ci` 或 wheel hash 强制校验；Docling 镜像 wheel 安装另按其固定 hash 构建入口执行。

## 十包独立 Agent 与真实模型验收

[verify_strengthened_skills.py](verify_strengthened_skills.py) 复用[实时验收辅助](live_support.py)与受理/下载辅助。业务演示初始化入口已移除；系统 Skill 的验收使用调用者显式提供的本批隔离资源，不创建机构或模型。实际 `setup` 创建独立验收 Agent、必要 TEST 工具部署；`run` 提交聊天请求，模型调用消耗供应商额度。前置条件为本批 manifest 与 Docker 实际归属共同确认的隔离数据库、已有机构、终端用户、模型和已发布来源 Agent，以及十包精确发布报告。

通过 `SKILL_LIVE_FIXTURE` 指定 Git 忽略目录中的本地 JSON 文件，包含 `environment_manifest`（本批 `environment.json` 绝对路径）、`postgres_container_id`（manifest登记的完整容器ID）、真实 `database_name`、`origins`（`platform`、`institution`、`gateway` 三个本地服务 origin）、字符串 `tenant_id`、`end_user_id`、`model_config_id`、`source_agent_id`，及安全保存的 `platform_token`、`institution_token`。[环境归属守卫](live_environment.py)同时核对专属容器label/name、运行状态、真实数据库名、唯一回环映射与本批私有JDBC，以及三个READY服务的当前origin/port；数据库名字串不代表开发或隔离环境。服务按这些凭据执行权限检查；来源工具挂载只读取显式 `source_agent_id`，不按演示名称选择，不自动登录、不读取旧演示回执。夹具缺失或资源标识不完整时拒绝执行；未知请求保持原回执，不重发。

长时间验收的私有fixture还应保存真实登录响应的 `authentication.platform`、`authentication.institution`，各含 `refresh_token` 和由真实 `expires_in` 推导的Unix秒 `expires_at`。[续期辅助](live_auth.py)每请求读取当前fixture，提前30秒到期时对同fixture加独占锁，先登记unknown，再调用对应服务的真实refresh端点；完整成功响应才原子更新token。刷新未知、拒绝、锁占用或origin变化时停止原请求，401不重试聊天；没有续期metadata的fixture使用现有token，到期失败不会自行登录或换身份。聊天预算检查与续期在unknown聊天派发意图之前完成，避免把尚未发送的聊天计作供应商调用。

```powershell
$env:FULL_TEST_BATCH = '<本批次ID>'
$env:SKILL_LIVE_EVIDENCE = '<包含本批次ID目录段的本轮skills/candidate绝对路径>'
$env:SKILL_LIVE_PACKAGES_REPORT = '<本批次packages/package-report.json绝对路径>'
$env:LIVE_BUDGET_RESERVATION = '<主执行器签发的本轮skills预算receipt绝对路径>'
python -X utf8 -B tools/skills/verify_strengthened_skills.py setup --profile candidate
```

`--profile` 默认为 `original`；候选验收显式使用 `candidate`。`setup` 无 `--apply` 开关，直接创建并发布所选独立验收 Agent，冻结十个 Skill 和基础六工具，再追加 `http_request`、`get_current_time`、`read_image`；candidate 还挂载研究采集所需的 `run_code`。需要时创建受控 TEST 部署，自动记忆提取关闭，不改变原演示 Agent。

| Profile | 独立 Agent key | 挂载数 | 私有回执目录 |
|---|---|---|---|
| original | `skill-live-<batch>-strengthened-original` | 19：十 Skill、六基础工具、三追加工具 | 本批 `--evidence-root` / `SKILL_LIVE_EVIDENCE` |
| candidate | `skill-live-<batch>-strengthened-candidate` | 20：original 的工具集合加 `run_code` | 本批独立profile证据目录 |

`--batch-id`、`--evidence-root`、`--packages-report` 也可显式传入，CLI拒绝历史目录和不同批次混用。`batch.json` 绑定profile、Agent key与发布报告，已有范围不允许覆盖。首次写入之前核对主线程的 `LIVE_BUDGET_RESERVATION` 及其 `ledger_path` 累计三轮预留；每套件每轮只建立一份预留，多个profile只能使用同一预留范围内的子目录，不能通过换目录增加额度。每次聊天先保存unknown派发意图，累计实际输入数；原Run明确终态后读取 `/billing/records` 的真实 `PROVIDER_REPORTED` token流水进行结算，缺失、估算或unknown均不补零、不再发送新输入。单轮输入、tokens或时间到上限停止新发送，仍可查询与结算原调用。观察文件为预算receipt旁的 `<receipt名称>-observations.json`，主执行器据此更新累计ledger；工具不自行签发预算。并行写入同一预留被独占锁拒绝。

两个 Profile 的请求、挂载与证据分别保留；原 19 挂载 Agent 的自然任务、失败样本和取消记录不能被候选替换或删除。候选冻结最新精确版本，不能把候选结果回写成原样本成功。同名无拥有记录、外部修订、挂载差异或未知写结果均先对账，不接管或盲目重建。切换 Profile 是新的明确验收范围，不能用来重放受理未知的原请求。

| `--case` | 当前明确验收任务 |
|---|---|
| writing | 企业结构的 Word 周报，保留预算、日期与责任人待确认 |
| theme | botanical-garden 可编辑 PPTX，多版式与实际渲染 |
| configuration | 用户自定义 Schema 的真实结构校验、JSON 交付，不应用配置 |
| web-testing | 主题 HTML 生成后，读取真实成果并做受控离线交互验收 |
| research | Tavily 免费 search/extract、官方来源正文、逐条引用 Markdown 报告 |
| mcp | 生成 Python stdio 服务、真实编译和官方 ClientSession 正反向调用验证 |
| writing-recovery / theme-recovery | 仅 original：在原请求明确取消后，同 Session 核对已有真实候选，再交付原 Word/PPTX；不重新生成，不将 PDF、页图或 ZIP 加入正式表示 |
| image-rejection | 实际调用无效媒体标识，仅核对已知业务拒绝；不替换标识或宣称图片已查看 |

```powershell
python -X utf8 -B tools/skills/verify_strengthened_skills.py run --profile candidate --case configuration --wait 0
python -X utf8 -B tools/skills/verify_strengthened_skills.py run --profile candidate --case configuration --wait 0
python -X utf8 -B tools/skills/verify_strengthened_skills.py download --profile candidate --case configuration
```

`--wait` 为 0 至 50 秒；`run` 首次创建 Session 并提交请求，后续同样调用读取原请求对应历史。上例第二次 run 为原请求查询，应至少间隔 30 秒，实际成功终态后才执行 download。多个用例并行时采用低频 `--wait 0` 单次查询，避免多个长查询循环争用历史接口限流。创建或受理意图先落盘，未知结果先查询，不删除 receipt 或变更 client message ID 重发。正式下载检查 ready 修订、正式票据、文件大小、SHA-256 与 Office ZIP 完整性；下载候选不代替正式交付。文档读取与附件上传需另选符合契约的 PDF、扫描件及无嵌入对象 Office 文件，在真实 Agent 的明确附件/成果路径上验证，本 CLI 的六类任务不能代表已覆盖全部读取入口。

research 的原始响应/corpus 通过以下组合链路保留：受管 `run_code` 中的[采集程序](../../official-skills/research/references/collect-responses.mjs)使用当前可见 `http_request` 的真实原生函数名，原样保存响应 body 与 SHA-256；随后 Python 生成并显式挂载 corpus，公开预览存在剩余正文时按契约回读，报告从同一 corpus 与摘要校验生成。函数名原样取自当前完整原生工具定义，`tools.call` 使用同一名称；不能由摘要、历史配置或内部句柄推算。中间 responses/corpus 不作为正式报告表示，模型不复制或压缩 HTTP body 充当原始回执。没有 `run_code` 挂载时披露配置缺口；旧供应商 HTTP 样本不能代替新的 Agent/run_code/http_request 组合证据。当前组合验收结果、纠正样本和限制归所属 Note，不在本入口维护进度。

Office 恢复必须先确认原 Run 的真实终态和已生成候选，并由获授权的服务更新流程加载已验证的 Handler；恢复入口不自动重启服务。使用 `--profile original --case writing-recovery` 或 `theme-recovery` 只提交明确的同 Session 后续任务，通过 `list_artifacts` 核对已有文件与渲染结果。`artifact_id` 和页图文件路径不是 `media_id`，缺少真实受管媒体标识时不得调用 `read_image` 猜测身份，必须披露模型视觉未验证；独立查看实际页图的证据另行记录。

产品页面检查需指向第二组真实 Session、成果清单与正式文件，验证下载、修订和隔离预览。第一组 `verify_live_browser.mjs` 使用其固定证据目录、用例和选择器，不能原样当作第二组已完成。页图必须实际经受支持图片链路进入模型，才记录模型视觉审阅；自动截图、渲染成功、浏览器断言和独立人工看图分别报告，Office 客户端一致性也不由渲染结果推定。

## 触发与效果评测

完整流程见[评测说明](evaluation/README.md)。先准备真实已发布的基线/候选 Agent，固定相同模型与工具配置，并用实际标识替换[套件示例](evaluation/suite.example.json)的占位值。评测工具不创建/发布 Agent，不自动修改 Skill 描述、版本或挂载。

```powershell
python -X utf8 -B tools/skills/evaluation/run_evaluation.py freeze --suite '<真实suite.json>' --output .agent-work-state/skill-strengthening/evaluations/<独立批次>
python -X utf8 -B tools/skills/evaluation/run_evaluation.py submit --output '<同一证据目录>' --variant candidate --case positive --repeat 0
python -X utf8 -B tools/skills/evaluation/run_evaluation.py collect --output '<同一证据目录>' --variant candidate --case positive --repeat 0 --wait 45
python -X utf8 -B tools/skills/evaluation/run_evaluation.py report --output '<同一证据目录>'
```

机构凭据通过 `SKILL_EVAL_INSTITUTION_TOKEN` 注入，平台观测凭据通过可选 `SKILL_EVAL_PLATFORM_TOKEN` 注入；不写入 suite 或证据。`freeze` 绑定配置与输入，`submit` 提交真实请求，`collect` 只查原 Run，不自动回答 HITL；显式 `cancel` 取消原运行，不撤销外部副作用。未知受理先对账。只用机构凭据时触发与用量可能为 `UNKNOWN`，不能以模型自述推断激活或补零 Token；报告分别记录可观察断言、触发、时间和用量样本数量，小样本或未终态用例不夸大成总体效果结论。

## 私有证据与交付边界

构包、发布 journal、安装报告、执行 receipt、真实模型历史、正式下载、截图与失败快照保存在忽略的私有目录。README 只给入口和证据用途，不保存凭据、链接某次私有结果或宣称全部真实验收通过；具体完成项、失败项、复用项和未验证范围按 [Note 治理](../../.agents/skills/nw-docs/references/notes-governance.md)记录。

结构测试、真实 Runner、镜像解析/OCR、真实模型、Provider HTTP、正式 API 下载、产品页面与逐页视觉证据不能互相替代。输入冻结后按模块集中验证，符合条件的证据复用；新增来源或能力的真实验收未完成时明确保留缺口。Git commit/push、生产部署或业务 Agent 升级需要各自授权和对应交付记录。

```powershell
python -X utf8 -B tools/docs/verify_md_links.py tools/skills/README.md
```
