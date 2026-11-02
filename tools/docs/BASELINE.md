# 文档检查与长度统计

[budgets.json](budgets.json) 提供非阻断长度参考值。篇幅不能代替信息准确性和完整性，也不是验收门槛。

统计以 `\S+` 非空白序列为单位，代码围栏计入，不代表汉字数或 Token 数；生成目录不参与手写文档长度统计。参考值按精确文件、最长目录匹配和默认值的顺序选择。

```bash
python -X utf8 -B tools/docs/check.py
git diff --check
```

检查覆盖链接、结构、Mermaid 和生成文件新鲜度。当前设计见 [Harness](../../harness-design/README.md)，文档规则见[文档规范](../../.agents/skills/nw-docs/references/documentation-standards.md)。检查通过不证明目标功能已经实现或通过真实业务验收。
