# Verification manifest 用法

普通任务使用摘要，恢复、交接或长期推进需要时维护轻量快照。仅在[评审与验证规范的启用条件](candidate-driven-delivery.md#启用条件)满足时使用本文的 manifest 工具；验证范围与评审要求仍由该规范维护。

## 机器状态

启用现有脚本时才使用 `.agent-work-state/<goal>.json` 的机器状态；保留脚本所需 goal、当前 Item、基线/分支/worktree、允许与排除路径、候选域、Gate 和证据指针，不手工删除必需字段以求精简。机器状态与日志由工具维护，人的恢复入口链接它们；不写凭据、敏感载荷或完整外部响应。

| 域 | 用途 |
|---|---|
| `source` | 源码、测试、构建输入与 ABI |
| `docs` | 当前范围 Markdown 和文档工具 |
| `integration` | schema、Mapper、事务/并发集成测试和依赖配置 |

每个文件归一个域；Gate 可引用多个域表达跨域依赖。工具按声明的域整体计算 key，不能声称支持逐文件精确增量；相关输入关系不明时扩大到最小可信范围，不另建依赖分析平台。

治理指纹用于发现规则变化，不能把无关规则文字更新当作所有业务 Gate 失效；实际验收要求改变时同步相关 Gate 定义与依赖。

## 命令与证据绑定

```text
python .agents/skills/nw-verification/scripts/verification_manifest.py init --state .agent-work-state/<goal>.json --goal <goal> --stack-item <item>
# 编辑 state：只声明当前必要 Gate、完整相关输入、工具链及输出；init 不预置评审或业务测试。
python .agents/skills/nw-verification/scripts/verification_manifest.py snapshot --state .agent-work-state/<goal>.json
python .agents/skills/nw-verification/scripts/verification_manifest.py run --state .agent-work-state/<goal>.json --manifest .agent-work-state/<item>.verification.json
python .agents/skills/nw-verification/scripts/verification_manifest.py check --state .agent-work-state/<goal>.json --manifest .agent-work-state/<item>.verification.json
```

从仓库根目录调用上述命令。`--stack-item` 是现有工具中的工作项标识，用于状态与证据绑定，不要求建立 PR Stack。Gate 的 `command` 是 argv 数组，不解析 `cd`、管道或重定向；命令工作目录固定为仓库根目录，`inputPaths`、`definitionFiles` 和报告路径也相对此目录。Windows Gradle 命令例如 `["backend-java/gradlew.bat", "-p", "backend-java", ":<module>:test", "--tests", "<pattern>"]`；其他平台使用 `./backend-java/gradlew`，同样传入 `-p backend-java`。模块、用例及工具链环境按实际任务填写。

### 最小文档 Gate 示例

以下仅演示对 `CLAUDE.md` 入口链接的受控检查，不证明全部文档或规则语义通过。普通文字任务仍使用直接检查；需要试用 manifest 时使用新的示例目录，不覆盖已有任务状态。

```text
python -X utf8 -B .agents/skills/nw-verification/scripts/verification_manifest.py init --state .agent-work-state/docs-entry-example/state.json --goal docs-entry-example --stack-item DOCS-ENTRY
```

初始化后，在仓库根目录运行以下 Python 片段填写当前示例所需字段，保留 init 生成的身份、基线、工具链与排除范围。输入包含被检查文件、链接目标及检查器的直接依赖；实际任务替换检查范围时同步调整这些输入。

```python
import json
from pathlib import Path

state_path = Path(".agent-work-state/docs-entry-example/state.json")
state = json.loads(state_path.read_text(encoding="utf-8"))
checker_files = ["tools/docs/verify_md_links.py", "tools/docs/markdown_util.py"]
inputs = ["CLAUDE.md", "AGENTS.md", ".agents/skills/nw-workflow/SKILL.md", *checker_files]
state["allowedPaths"] = inputs
state["domains"] = {
    "source": {"paths": []},
    "docs": {"paths": inputs},
    "integration": {"paths": []},
}
state["gates"] = {
    "entry-links": {
        "kind": "command", "version": 1, "required": True,
        "domains": ["docs"], "toolchains": ["python"],
        "inputPaths": inputs, "definitionFiles": checker_files,
        "command": [state["toolchains"][0]["command"][0], "-X", "utf8", "-B",
                    "tools/docs/verify_md_links.py", "CLAUDE.md"],
    }
}
state_path.write_text(json.dumps(state, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
```

```text
python -X utf8 -B .agents/skills/nw-verification/scripts/verification_manifest.py snapshot --state .agent-work-state/docs-entry-example/state.json
python -X utf8 -B .agents/skills/nw-verification/scripts/verification_manifest.py run --state .agent-work-state/docs-entry-example/state.json --manifest .agent-work-state/docs-entry-example/verification.json
python -X utf8 -B .agents/skills/nw-verification/scripts/verification_manifest.py check --state .agent-work-state/docs-entry-example/state.json --manifest .agent-work-state/docs-entry-example/verification.json
```

`check` 返回 `allReusable: true` 时，仅表示该示例的已声明 Gate 证据有效。命令日志由工具自动保存；业务测试需要的报告与测试统计继续按下面的声明与绑定规则配置。

### 声明输入

- Gate 冻结精确 argv、完整 `inputPaths`、`definitionFiles`、适用域/工具链、报告路径及可选 `junitPolicy`。评审确有必要才声明 review Gate，并向 run 提供 `--review-evidence <review.json>`；证据必须绑定预冻结 Gate key。
- 全局 `governanceFiles` 用于上下文变化检测；各 Gate 的同名字段只列实际影响该验证的规则，默认空，不因根规则文字变化使所有业务证据失效。验收要求变化时必须更新相关 Gate 定义、版本或规则依赖，不能据空列表忽略新要求。
- 规则或 Note 迁移后，恢复活跃状态时同步 `governanceFiles`、候选域和相关 Gate 路径；省略全局 `governanceFiles` 时，[验证脚本](../scripts/verification_manifest.py)从 Git 候选集合发现项目入口、脚本所在目录的同级技能 SKILL.md、references 下的 Markdown 与同名验证脚本，覆盖拆分后的规则集合。默认入口不存在无需补建，已跟踪删除仍进入指纹；显式配置/Gate 路径继续严格校验，工具位于仓库外时显式补充仓库规则。正文参与上下文检测，普通 Note 仅按任务归入 docs 域；历史缓存不批量改写，旧路径不作为复用依据。
- 工具链用 `environmentKeys` 声明相关环境：init 的 Python 模板包含 PYTHONHOME/PYTHONPATH；Java/Gradle 按实际使用声明 JAVA_HOME、JAVA_TOOL_OPTIONS、JDK_JAVA_OPTIONS、GRADLE_OPTS、GRADLE_USER_HOME 等。显式 `environment` 覆盖、版本输出及可执行文件身份也进入指纹；省略 `environmentKeys` 时使用脚本默认环境键，包含 JDK_JAVA_OPTIONS、不含 PATH；可执行文件解析结果及文件 hash 始终绑定。命令或其子进程依赖 PATH 查找其他工具时须显式声明 PATH。业务 Gate 涉及的外部配置/环境也须声明。共享契约、构建依赖或环境变化覆盖相关使用方。
- 工具链和 command Gate 可声明 `outputEncoding`（如 `gbk`），默认 UTF-8 并容错非法字节；声明参与工具链指纹或 Gate key。Git 路径协议继续使用 UTF-8，避免随终端区域设置漂移。

### 执行与评审绑定

- run 直接执行命令并保存日志、报告副本和 JUnit 实际统计，不接受手填成功。已有绑定证据有效就复用；没有可信证据时，声明的报告须由本次命令生成或刷新，必要时仅重跑对应任务，不把旧 XML 冒充本次执行。
- review 证据沿用 `name`、`gate`、`gateKey`、`kind: "review"`、`status: "pass"`，评审计数按下表校验。首次绑定、缓存复验和最终 check 使用相同判定；严重性不推导阻断性，是否阻断按当前目标与必要验收判断。

| 字段 | 契约 |
|---|---|
| `blockingFindings` | 必填非负整数，拒绝布尔值；只有 `0` 可通过，缺失或非法值均拒绝 |
| `findings` | 可选对象；非空白等级名称映射到非负整数，拒绝布尔计数；可为空对象，不限制等级集合，不决定 Gate 成败 |

`blockingFindings: 0` 可与 `findings: {"high": 2}` 并存；`blockingFindings: 1` 即使只有 low 建议或省略 findings 也不能通过。字段如实反映评审，不为取得通过而改写实际阻断结论。

### 复用与拒绝

- review Gate 身份自动包含 `reviewPolicyVersion: 2`；全局 `schemaVersion` 仍为 1，无关 command Gate 的身份计算保持不变。旧评审身份缺少策略版本或使用版本 1 时，仅该评审证据过期，不能由旧四级计数推导新策略的零阻断。
- 恢复先验证 checkpoint 内容 hash、状态身份、冻结 Gate 身份/key、证据绑定与原报告，再按当前 Gate key 排除过期评审。重新 snapshot 后提供新评审，仍有效的命令证据继续复用；旧评审不能直接通过 check。当前策略证据缺字段、非法或损坏必须报错，不能作为普通过期缓存静默跳过；不批量改写历史缓存。
- 相同候选和证据重复发布时，仅在比较是否重复时忽略 verifiedAt 与 snapshot.capturedAt，复用原 checkpoint 及时间；完整内容（含两个时间字段）继续参与 hash 校验，内容篡改仍拒绝。实际候选、上下文或证据变化才生成新 checkpoint。
- 每个通过 Gate 保存不可变 checkpoint；部分失败保留有效部分，下次 run 校验后跳过仍匹配的项。输入变化先 snapshot；全部 required Gate 覆盖且候选无漂移才发布 verified/lastGreen。check 只校验证据，不再执行同一批测试。
- 缺失/失败的必要 Gate、报告篡改、损坏 checkpoint、候选漂移和真实阻断仍拒绝验收。未启用 manifest 的可信验证可在摘要中复用，但不能手填入脚本冒充受控证据。
- manifest 是可丢弃本地缓存，持久结果按 Note 治理规范保存简短摘要。规则变更不追溯改写历史验收或批量重跑旧候选；恢复活跃任务时按实际影响更新当前记录。

## 调试日志

日志使用 Python 标准 `logging`，logger 名称为 `skillforge.verification_manifest`，可通过标准日志配置启用 DEBUG。调试信息及缓存命中写 stderr，CLI 的结构化结果保持 JSON stdout；命令参数不增加日志开关。

日志覆盖与安全要求归[工程规范](../../nw-engineering/references/engineering-standards.md#事务日志与可观测性)。现有工具包装器提供中文入口/出口、操作、追踪、结果与耗时，并在嵌套调用后恢复上下文；该实现不要求所有辅助方法继续逐次记录。调试日志不展开参数、返回值、异常载荷或完整证据；最终 CLI 错误边界记录一次仅含函数名和行号的安全栈，异常原因链保留。
