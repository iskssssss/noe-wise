---
name: code-delivery
description: 从明确文件清单逐项生成源码和可选补丁；仅按明确要求打包 ZIP。
license: Apache-2.0
compatibility: Python 3.12
metadata:
  version: 1.1.5
  runtime: python-stdlib
---

# 代码文件与打包

按用户目标确定文件结构，保留明确的相对路径和完整内容。通常逐项提供原始源码文件，仅在需要补丁或 ZIP 时生成对应文件。修改已有项目时根据实际原件定向修改，不用示例内容替代真实实现。

## 本包打包器

入口为 `scripts/package_delivery.py`，读取唯一的 `delivery.json`，输入见 [文件清单示例](examples/delivery.json)。脚本只根据清单写出文件，不读取代码仓库、不运行项目命令，也不自行验证业务行为。

- `files` 提供相对路径与内容；路径不能绝对或回退目录。
- `verification` 只记录真实已有证据，不能把打包完成写成编译或测试通过。
- `patch` 非空时生成 `change.patch`。
- `bundle_zip: true` 生成 `<package_name>.zip`；`package_name` 是不含 `.zip` 的安全单文件名，省略时为 `source-package`。例如 `hello-service.zip` 对应 `package_name: "hello-service"`。

输入须明确且唯一，不从包内示例推断用户数据。脚本不依赖第三方库。

## 检查

核对实际文件清单、目录结构与内容，确认补丁适用于真实基准，ZIP 内文件名与所需结构一致。编译、测试和运行结论以实际执行结果为依据；未执行的检查应如实说明。打包器成功只说明文件已按输入写出。