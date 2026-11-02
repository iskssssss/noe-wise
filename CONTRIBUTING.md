# 参与贡献

感谢你参与 NoeWise。请通过[项目 Issue](https://github.com/iskssssss/noe-wise/issues)讨论问题与需求，通过[Pull Request](https://github.com/iskssssss/noe-wise/pulls)提交改动。安全漏洞按[安全报告说明](SECURITY.md)私下报告。

## 开始修改

1. 阅读[README](README.md)和[文档目录](docs/README.md)，按[开发与部署指南](docs/04-指南与运维/01-开发与部署指南.md)准备环境。
2. 从当前 `main` 创建主题分支，一次改动聚焦一个可说明、可验证的目标。较大功能或架构变化先开 Issue 讨论方向。
3. 修改 Runtime 前阅读 [Harness](harness-design/README.md)，修改平台或 UI 前阅读对应设计。代码、接口与直接相关文档保持一致，保留现有包名和模块边界。
4. 运行与改动相匹配的检查，在 PR 中说明问题、最终行为、影响范围、验证结果及尚未验证的部分。

不要提交 `.env`、API Key、数据库备份、真实用户数据、私有部署地址、原始运行日志或构建缓存。示例使用虚构且可公开的数据，测试与付费外部服务分开。

## 验证

文档定向检查：

```powershell
python -X utf8 -B tools/docs/verify_md_links.py README.md docs harness-design
```

文档目录或生成来源发生较大变化时执行：

```powershell
python -X utf8 -B tools/docs/check.py
```

前端从仓库根目录运行 `corepack pnpm lint`、`corepack pnpm format:check`，或选择受影响模块的测试。后端在 `backend-java` 使用 Gradle Wrapper 执行受影响模块任务；Windows 使用 `.\gradlew.bat`，其它平台使用 `./gradlew`。测试路由见[测试与验收策略](docs/04-指南与运维/03-测试与验收策略.md)，无需为纯文档变更运行全套业务测试。

## 提交与 PR

提交标题采用 `type(scope): 中文摘要`，`scope` 可省略；常用类型为 `feat`、`fix`、`docs`、`refactor`、`test`、`build`、`ci` 和 `chore`。摘要说明实际变更；PR 说明改动原因、最终行为、兼容性影响和验证结果，不用测试数量替代结果解释。

请保留第三方版权、许可证和必要归属声明。提交贡献前，确认你有权提供这些内容，并同意原创贡献按项目的 [Apache-2.0](LICENSE) 许可证分发；第三方内容遵循其自身许可。
