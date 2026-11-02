"""使用显式挂载的 JSON Schema 校验配置，全部通过后生成候选文件。"""
import json
import math
from pathlib import Path
import sys
from zipfile import ZIP_DEFLATED, ZipFile

from jsonschema import Draft7Validator, Draft201909Validator, Draft202012Validator, FormatChecker
from jsonschema.exceptions import SchemaError
from referencing import Registry
from referencing.exceptions import NoSuchResource, Unresolvable
import yaml

INPUT_DATA = Path("/input/data")
INPUT_ARTIFACTS = Path("/input/artifacts")
OUTPUT = Path("/workspace/output")
MAX_INPUT_BYTES = 10 * 1024 * 1024
MAX_DEPTH = 64
MAX_ERRORS = 20
SUPPORTED_FORMATS = ("date", "email", "ipv4", "ipv6", "regex", "uuid")
VALIDATORS = {
    "http://json-schema.org/draft-07/schema": Draft7Validator,
    "https://json-schema.org/draft-07/schema": Draft7Validator,
    "https://json-schema.org/draft/2019-09/schema": Draft201909Validator,
    "https://json-schema.org/draft/2020-12/schema": Draft202012Validator,
}


class ConfigurationError(ValueError):
    """安全的校验结果不包含配置值、Schema 枚举值或原始解析异常。"""

    def __init__(self, code, message, errors=None, **details):
        self.report = {"valid": False, "code": code, "message": message, **details}
        if errors is not None:
            self.report["errors"] = errors
        super().__init__(json.dumps(self.report, ensure_ascii=False))


def fail(code, message):
    raise ConfigurationError(code, message)


def pointer(parts):
    return "".join("/" + str(part).replace("~", "~0").replace("/", "~1") for part in parts)


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            fail("duplicate_key", "JSON/YAML 不允许重复字段")
        result[key] = value
    return result


class UniqueSafeLoader(yaml.SafeLoader):
    pass


def unique_mapping(loader, node, deep=False):
    # YAML 合并、非字符串键和重复键会改变配置语义，必须在校验前拒绝。
    pairs = []
    for key_node, value_node in node.value:
        if key_node.tag == "tag:yaml.org,2002:merge":
            fail("yaml_merge", "YAML 配置不支持合并键")
        key = loader.construct_object(key_node, deep=deep)
        if not isinstance(key, str):
            fail("non_json_value", "配置字段名必须是字符串")
        pairs.append((key, loader.construct_object(value_node, deep=deep)))
    return unique_object(pairs)


UniqueSafeLoader.add_constructor(yaml.resolver.BaseResolver.DEFAULT_MAPPING_TAG, unique_mapping)


def check_json_value(value, depth=0, ancestors=None):
    if depth > MAX_DEPTH:
        fail("input_depth", "JSON/YAML 嵌套超过 64 层")
    if value is None or isinstance(value, (str, bool, int)):
        return
    if isinstance(value, float):
        if not math.isfinite(value):
            fail("non_json_value", "配置只允许有限数值")
        return
    if not isinstance(value, (dict, list)):
        fail("non_json_value", "配置必须使用 JSON 数据类型；日期请用引号包裹")
    ancestors = set() if ancestors is None else ancestors
    if id(value) in ancestors:
        fail("cyclic_input", "YAML 不允许循环引用")
    ancestors.add(id(value))
    if isinstance(value, dict) and any(not isinstance(key, str) for key in value):
        fail("non_json_value", "配置字段名必须是字符串")
    for child in (value.values() if isinstance(value, dict) else value):
        check_json_value(child, depth + 1, ancestors)
    ancestors.remove(id(value))


def read_document(path):
    if path.stat().st_size > MAX_INPUT_BYTES:
        fail("input_size", "单个输入文件超过 10 MiB")
    try:
        source = path.read_text(encoding="utf-8-sig")
        if path.suffix.lower() == ".json":
            def invalid_constant(_value):
                fail("non_json_value", "JSON 不允许 NaN 或 Infinity")
            result = json.loads(source, object_pairs_hook=unique_object, parse_constant=invalid_constant)
        elif path.suffix.lower() in {".yaml", ".yml"}:
            result = yaml.load(source, Loader=UniqueSafeLoader)
        else:
            fail("input_extension", "配置和 Schema 文件仅支持 JSON/YAML")
    except ConfigurationError:
        raise
    except (UnicodeError, json.JSONDecodeError, yaml.YAMLError, RecursionError, ValueError):
        fail("input_parse", "JSON/YAML 输入无法解析；不会回显原始内容")
    check_json_value(result)
    return result


def mounted_file(value):
    if not isinstance(value, str) or not value:
        fail("input_path", "必须明确指定受管输入文件的绝对路径")
    path = Path(value)
    if not path.is_absolute() or ".." in path.parts:
        fail("input_path", "文件路径必须位于受管 /input 挂载内")
    direct = path.is_relative_to(INPUT_DATA)
    artifact = path.is_relative_to(INPUT_ARTIFACTS) and len(path.relative_to(INPUT_ARTIFACTS).parts) >= 2
    if not direct and not artifact:
        fail("input_path", "文件路径必须位于 /input/data 或 /input/artifacts/<artifact_id> 挂载内")
    if not path.is_file():
        fail("input_missing", "显式挂载的输入文件不存在")
    resolved = path.resolve()
    base = INPUT_DATA.resolve() if direct else INPUT_ARTIFACTS.resolve() / path.relative_to(INPUT_ARTIFACTS).parts[0]
    if not resolved.is_relative_to(base):
        fail("input_path", "输入文件不能通过符号链接离开所属挂载")
    return resolved


def locate_input():
    candidates = []
    direct = INPUT_DATA / "configuration.json"
    if direct.is_file():
        candidates.append(direct)
    if INPUT_ARTIFACTS.is_dir():
        candidates.extend(directory / "configuration.json" for directory in sorted(INPUT_ARTIFACTS.iterdir())
                          if directory.is_dir() and (directory / "configuration.json").is_file())
    if len(candidates) != 1:
        fail("control_input", "需要且只能提供一个明确挂载的 configuration.json 控制文件")
    return mounted_file(str(candidates[0].absolute()))


def deny_retrieve(uri):
    # 即使 Schema 声明 $id，也绝不由库自动获取 HTTP、文件或其他外部资源。
    raise NoSuchResource(ref=uri)


def check_schema_policy(schema, parts=()):
    if isinstance(schema, dict):
        for key, value in schema.items():
            if key in {"$ref", "$dynamicRef", "$recursiveRef"} and (not isinstance(value, str) or not value.startswith("#")):
                raise ConfigurationError("external_schema_ref", "Schema 引用只能使用当前文件内的 # 片段",
                                         [{"schema_path": pointer(parts + (key,)), "keyword": key}])
            if key == "format" and value not in SUPPORTED_FORMATS:
                raise ConfigurationError("unsupported_format", "Schema format 仅支持 date/email/ipv4/ipv6/regex/uuid",
                                         [{"schema_path": pointer(parts + (key,)), "keyword": key}])
        # 只访问 Schema 位置，不能把 default/const/examples 中的配置值误当关键字。
        for key in ("$defs", "definitions", "properties", "patternProperties", "dependentSchemas", "dependencies"):
            children = schema.get(key)
            if isinstance(children, dict):
                for name, child in children.items():
                    if isinstance(child, (dict, bool)):
                        check_schema_policy(child, parts + (key, name))
        for key in ("additionalProperties", "unevaluatedProperties", "propertyNames", "contains", "additionalItems",
                    "unevaluatedItems", "not", "if", "then", "else", "contentSchema", "items"):
            child = schema.get(key)
            if isinstance(child, (dict, bool)):
                check_schema_policy(child, parts + (key,))
            elif key == "items" and isinstance(child, list):
                for index, item in enumerate(child):
                    check_schema_policy(item, parts + (key, index))
        for key in ("allOf", "anyOf", "oneOf", "prefixItems"):
            children = schema.get(key)
            if isinstance(children, list):
                for index, child in enumerate(children):
                    check_schema_policy(child, parts + (key, index))


def validate_configuration(document, schema, schema_source):
    if not isinstance(schema, dict):
        fail("schema_input", "Schema 根必须为对象并声明 $schema")
    dialect = schema.get("$schema")
    validator_class = VALIDATORS.get(dialect.rstrip("#") if isinstance(dialect, str) else None)
    if validator_class is None:
        fail("schema_dialect", "Schema 必须明确声明 Draft 7、2019-09 或 2020-12")
    try:
        validator_class.check_schema(schema)
    except SchemaError as error:
        raise ConfigurationError("schema_invalid", "Schema 自身未通过元 Schema 校验",
                                 [{"schema_path": pointer(error.absolute_path), "keyword": error.validator}]) from None
    check_schema_policy(schema)
    validator = validator_class(schema, registry=Registry(retrieve=deny_retrieve),
                                format_checker=FormatChecker(formats=SUPPORTED_FORMATS))
    errors = []
    try:
        for error in validator.iter_errors(document):
            errors.append({"instance_path": pointer(error.absolute_path), "schema_path": pointer(error.absolute_schema_path),
                           "keyword": error.validator, "message": "配置不满足此 Schema 约束"})
            if len(errors) > MAX_ERRORS:
                break
    except (Unresolvable, RecursionError):
        fail("schema_reference", "Schema 片段引用无法解析或存在循环；未获取任何外部资源")
    if errors:
        raise ConfigurationError("configuration_invalid", "配置未通过 Schema 校验；没有生成候选文件",
                                 errors[:MAX_ERRORS], errors_truncated=len(errors) > MAX_ERRORS,
                                 schema_source=schema_source)


def main():
    data = read_document(locate_input())
    if not isinstance(data, dict) or set(data) - {"format", "document", "document_file", "schema_file", "schema_source", "bundle_zip"}:
        fail("control_input", "控制文件包含无效或未知字段；required_keys 已由 Schema 替代")
    fmt, bundle_zip = data.get("format"), data.get("bundle_zip", False)
    if not isinstance(fmt, str) or fmt not in {"json", "yaml"} or not isinstance(bundle_zip, bool):
        fail("control_input", "format 必须为 json/yaml，bundle_zip 必须为布尔值")
    if ("document" in data) == ("document_file" in data):
        fail("document_input", "document 与 document_file 必须且只能提供一项")
    schema_source = data.get("schema_source")
    if not isinstance(schema_source, dict) or set(schema_source) - {"kind", "description", "reference"}:
        fail("schema_source", "必须记录 Schema 的来源")
    if not isinstance(schema_source.get("kind"), str) or schema_source["kind"] not in {"target_system", "user_defined"} or not isinstance(schema_source.get("description"), str) or not schema_source["description"].strip():
        fail("schema_source", "Schema 来源必须说明目标系统契约或用户自定义约束")
    if "reference" in schema_source and (not isinstance(schema_source["reference"], str) or not schema_source["reference"].strip()):
        fail("schema_source", "Schema 来源 reference 必须为非空字符串")
    if schema_source["kind"] == "target_system" and "reference" not in schema_source:
        fail("schema_source", "目标系统正式 Schema 必须提供可追溯的版本或文档 reference")
    document = data["document"] if "document" in data else read_document(mounted_file(data["document_file"]))
    if not isinstance(document, dict):
        fail("document_input", "配置根必须为 JSON 对象或 YAML 映射")
    check_json_value(document)
    schema = read_document(mounted_file(data.get("schema_file")))
    validate_configuration(document, schema, schema_source)
    OUTPUT.mkdir(parents=True, exist_ok=True)
    target = OUTPUT / ("configuration.json" if fmt == "json" else "configuration.yaml")
    if fmt == "json":
        text = json.dumps(document, ensure_ascii=False, indent=2, allow_nan=False) + "\n"
    else:
        text = yaml.safe_dump(document, allow_unicode=True, sort_keys=False)
    target.write_text(text, encoding="utf-8")
    bundle = None
    if bundle_zip:
        bundle = OUTPUT / "configuration.zip"
        with ZipFile(bundle, "w", compression=ZIP_DEFLATED) as archive:
            archive.write(target, arcname=target.name)
    report = {"valid": True, "format": fmt, "output": target.name, "bundle": bundle.name if bundle else None,
                      "schema_dialect": schema["$schema"], "schema_source": schema_source,
              "validation_scope": "provided_schema", "applied": False}
    (OUTPUT / "result.json").write_text(json.dumps({"results": [
        {"kind": "text", "format": "plain", "text": json.dumps(report, ensure_ascii=False)}
    ]}, ensure_ascii=False), encoding="utf-8")


def cli():
    """失败保持非零退出，安全错误通过宿主支持的 stderr 诊断通道返回。"""
    try:
        main()
    except ConfigurationError as error:
        print(json.dumps(error.report, ensure_ascii=False), file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(cli())
