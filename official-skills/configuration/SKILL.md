---
name: configuration
description: 生成或读取 JSON/YAML 配置，按显式提供且可追溯的 JSON Schema 校验类型、枚举、范围、嵌套和额外字段，再逐项提供文件；不会应用配置。
license: Apache-2.0
compatibility: Python 3.12
metadata:
  version: 2.0.5
  runtime: PyYAML==6.0.2, jsonschema==4.25.1, attrs==25.3.0, jsonschema-specifications==2025.9.1, referencing==0.36.2, rpds-py==0.27.1, typing-extensions==4.16.0
---

# 配置生成与校验

先确认目标系统、配置格式、Schema 来源与适用版本。输出采用用户需要的 JSON 或 YAML，仅在需要打包时生成 ZIP。配置生成和校验不等于部署、应用配置或验证真实连接。

正式契约使用 `schema_source.kind: target_system`，附来源说明及可追溯版本或文档引用；模型自拟或用户提供的约束使用 `user_defined`。缺少正式 Schema 时可明确提供自定义草案，校验通过只说明满足该草案。不能从字段名、示例、历史配置或一次校验通过推断业务正确、权限正确或适合部署。

## 本包校验器

入口为 `scripts/generate_configuration.py`，读取唯一的控制文件 `configuration.json`，依赖由包清单声明。控制文件及 Schema 的完整约束见 [输入与校验契约](references/input-schema.md)。

控制文件使用 `document` 提供配置对象，或使用 `document_file` 指向实际配置文件，两者二选一；`schema_file` 必须指向实际提供的 JSON/YAML Schema。Schema 与源配置始终按明确路径读取，不从示例或同名文件猜测来源。

参考 [生成示例](examples/configuration.json)、[文件示例](examples/configuration-file.json) 与 [YAML 输入](examples/source.yaml)。[演示 Schema](examples/schema.json) 只说明格式，不属于任何正式业务系统。`reference` 记录来源，不会触发下载，也不证明来源声明真实。

支持 Draft 7、2019-09、2020-12；Schema 须声明 `$schema`。脚本先检查 Schema 本身，再校验配置，仅支持当前文件内 `#` 片段引用，不自动获取 HTTP、文件或相对文件引用。类型、枚举、数值范围、嵌套、必填和额外字段由 Schema 决定，未知 `format` 明确拒绝。

成功后输出 `configuration.json` 或 `configuration.yaml`，`bundle_zip: true` 另生成 ZIP。成功摘要包含 `validation_scope: provided_schema` 和 `applied: false`；校验错误包含 JSON Pointer 路径与关键字，不回显配置值。

## 结果判断

只有实际运行并通过本包入口，才能声称通过本包的校验。手工写结果或另一种校验方式不能冒充该证据；使用其他校验方式时说明实际工具和覆盖范围。失败应按真实诊断修正配置或 Schema，保留未通过状态，不能把已有输出文件当作通过证明。

本包不写入业务系统、不部署、不重启服务，不验证真实连接或密钥可用性。配置可能包含用户提供的敏感值，展示和交付时按任务要求保护这些内容。