# 输入与校验契约

执行入口为 [generate_configuration.py](../scripts/generate_configuration.py)。运行时固定依赖及输出文件见 [manifest](../manifest.json)。

| 字段 | 约束与含义 |
|---|---|
| `format` | 必须是 `json` 或 `yaml`，决定生成文件格式，与源文件格式独立 |
| `document` | 配置对象；与 `document_file` 二选一 |
| `document_file` | 显式挂载的 `.json`、`.yaml` 或 `.yml` 文件绝对路径；配置根为对象或映射 |
| `schema_file` | 必填，显式挂载的 JSON/YAML Schema 文件绝对路径 |
| `schema_source.kind` | `target_system` 表示已核实的目标系统正式契约，`user_defined` 表示用户约束或草案 |
| `schema_source.description` | 必填非空字符串，说明来源和适用范围；不得放入凭据 |
| `schema_source.reference` | 正式契约必填非空字符串，记录可追溯版本、文档或文件出处；仅为记录，不会获取资源 |
| `bundle_zip` | 可选布尔值，默认 `false`；仅用户明确要求 ZIP 时设为 `true` |

拒绝未知控制字段；`required_keys` 不再接受，由 JSON Schema 的 `required`、`type` 等约束代替。JSON/YAML 配置只接受 JSON 数据类型，拒绝重复键、非字符串映射键、YAML 合并键、循环引用、NaN/Infinity 和未引用的 YAML 日期对象。单个文件上限 10 MiB，嵌套上限 64 层；不会自动将字符串转为数字或补充 Schema 的 `default`。

输入必须在 `/input/data` 或 `/input/artifacts/<artifact_id>` 内，禁止父目录路径和符号链接越界。控制文件需要且只能有一个 `configuration.json`，Schema 与源配置始终按完整路径读取；不会扫描示例或猜测同名来源。

Schema 根必须为对象，`$schema` 必须指定 Draft 7、2019-09 或 2020-12。支持库在对应 Draft 下的结构约束、组合约束及内部片段引用。`$ref`、`$dynamicRef`、`$recursiveRef` 只能引用当前文件内的 `#` 片段，未解析的引用和循环失败关闭；Schema `$id` 是标识，绝不触发网络获取。包含多个文件的正式 Schema 需由来源方提供单文件版本或经授权预先合并，脚本不自动加载其他文件。

`format` 只校验 `date`、`email`、`ipv4`、`ipv6`、`regex`、`uuid`；其他 `format` 明确拒绝。`email` 使用 jsonschema 的语法检查，不证明邮箱存在。自定义非标准关键字遵循 JSON Schema 注释语义，不代表该业务规则已执行；依赖目标系统专用关键字时不能将结果宣称为完整目标系统校验。

失败返回 `valid: false`、稳定 `code` 和安全说明；实例错误包含 RFC 6901 `instance_path`、`schema_path`、`keyword`，不会包含配置值、Schema 枚举值或原始解析异常。根路径为空字符串，数组使用索引，`~` 和 `/` 正确转义；最多报告 20 条，超过时 `errors_truncated: true`。CLI 失败退出码为 `2`，只有全部校验通过后才写候选文件。成功返回 `schema_dialect`、`schema_source`、`validation_scope: provided_schema`、`applied: false`。该结果不验证来源声明真伪、真实连接、权限或部署效果。

校验库采用 [python-jsonschema](https://github.com/python-jsonschema/jsonschema) 4.25.1，许可 MIT，保留上游 [许可来源](https://github.com/python-jsonschema/jsonschema/blob/v4.25.1/COPYING)。本包没有复制其源码，也不依赖动态联网或 Shell。
