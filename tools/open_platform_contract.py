"""Validate, diff, and publish the versioned Tenant Open Platform contract.

The checked-in OpenAPI document is the source of truth for the public v1
surface.  This tool deliberately keeps the generated SDK artifact small: it
contains the operation and header/scope metadata consumed by clients and can
be regenerated without a code generator runtime.  The high-level client in
``frontend-shared`` remains responsible for domain-friendly request methods.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import sys
from pathlib import Path
from typing import Any
from urllib.parse import urlparse
from urllib.request import Request, urlopen

try:
    import yaml
except ImportError as exc:  # pragma: no cover - exercised by CI setup failure
    raise SystemExit("PyYAML is required: python -m pip install pyyaml") from exc


ROOT = Path(__file__).resolve().parents[1]
DEFAULT_CONTRACT = ROOT / "backend-java/skillforge-institution-service/src/main/resources/openapi/open-platform-v1.yaml"
DEFAULT_GENERATED = ROOT / "frontend-shared/src/open-platform/generated/v1-contract.ts"
HTTP_METHODS = ("get", "post", "put", "patch", "delete", "head", "options", "trace")
OPEN_PLATFORM_PREFIX = "/open-platform"
FORBIDDEN_ROUTE_MARKERS = ("/tool/invoke", "/workflow/run", "/workflow/resume", "/skill/execute", "/checkpoint")
STANDARD_ERROR_STATUS_CODES = ("400", "401", "403", "404", "405", "406", "409", "413", "415", "422", "429", "500", "503")
JSON_MEDIA_TYPE_PREFIXES = ("application/json", "application/problem+json")
NON_ENVELOPE_MEDIA_MARKERS = (
    "text/event-stream",
    "application/octet-stream",
    "application/pdf",
    "application/zip",
)
# 二进制正文只承载文件；稳定上传元数据进入同样被 HMAC 覆盖的原始 query。
BINARY_UPLOAD_METADATA = {
    "/open-platform/sessions/attachments": {"sessionId", "uploadId", "filename"},
    "/open-platform/sessions/media": {"sessionId", "uploadId"},
}
REQUIRED_IDEMPOTENCY_ROUTES = {
    ("post", "/open-platform/groups/ensure"),
    ("post", "/open-platform/groups/close"),
    ("post", "/open-platform/groups/members/update"),
    ("post", "/open-platform/groups/agents/update"),
    ("post", "/open-platform/groups/messages"),
    ("post", "/open-platform/groups/memories/create"),
    ("post", "/open-platform/groups/memories/review"),
    ("post", "/open-platform/groups/memories/correct"),
    ("post", "/open-platform/groups/memories/forget"),
    ("post", "/open-platform/groups/memories/restore"),
    ("post", "/open-platform/groups/triggers"),
    ("post", "/open-platform/groups/runs/respond"),
    ("post", "/open-platform/groups/runs/commands"),
    ("post", "/open-platform/groups/controls"),
    ("post", "/open-platform/groups/controls/terminalize"),
    ("post", "/open-platform/groups/controls/release"),
    ("post", "/open-platform/groups/sender-endpoints/register"),
    ("post", "/open-platform/groups/sender-endpoints/retire"),
    ("post", "/open-platform/groups/sender-endpoints/ack"),
    ("post", "/open-platform/business-scopes/ensure"),
    ("post", "/open-platform/business-scopes/status"),
    ("post", "/open-platform/agent-deployments"),
    ("post", "/open-platform/agent-deployments/authorization"),
    ("put", "/open-platform/agent-deployments/bindings"),
    ("post", "/open-platform/control-operations"),
    ("post", "/open-platform/sessions/messages"),
    ("post", "/open-platform/sessions/actions"),
    ("post", "/open-platform/sessions/callbacks"),
    ("post", "/open-platform/sessions/commands"),
    ("post", "/open-platform/sessions/steering"),
}


def load_document(source: str | Path) -> dict[str, Any]:
    """Load JSON/YAML from a local file or an HTTP(S) URL."""
    source_text = str(source)
    parsed = urlparse(source_text)
    if parsed.scheme in {"http", "https"}:
        request = Request(source_text, headers={"Accept": "application/json, application/yaml, text/yaml"})
        with urlopen(request, timeout=20) as response:
            raw = response.read()
        name = parsed.path
    else:
        raw = Path(source_text).read_bytes()
        name = source_text
    if str(name).lower().endswith(".json") or raw.lstrip().startswith((b"{", b"[")):
        return json.loads(raw.decode("utf-8"))
    loaded = yaml.safe_load(raw.decode("utf-8"))
    if not isinstance(loaded, dict):
        raise ValueError(f"OpenAPI document must be an object: {source_text}")
    return loaded


def resolve_ref(document: dict[str, Any], value: Any) -> Any:
    """Resolve the local component references used by the v1 document."""
    if not isinstance(value, dict) or "$ref" not in value:
        return value
    ref = value["$ref"]
    if not isinstance(ref, str) or not ref.startswith("#/" ):
        return value
    current: Any = document
    for part in ref[2:].split("/"):
        current = current[part]
    return current


def _resolved_schema(document: dict[str, Any], value: Any, ref_stack: tuple[str, ...] = ()) -> Any:
    """Resolve local schema refs while keeping recursive refs finite."""
    if not isinstance(value, dict) or "$ref" not in value:
        return value
    ref = value.get("$ref")
    if not isinstance(ref, str) or not ref.startswith("#/"):
        return value
    if ref in ref_stack:
        return {"$ref": ref}
    resolved = resolve_ref(document, value)
    if resolved is value:
        return value
    if len(value) == 1:
        return _resolved_schema(document, resolved, ref_stack + (ref,))
    merged = dict(resolved) if isinstance(resolved, dict) else {}
    merged.update({key: child for key, child in value.items() if key != "$ref"})
    return _resolved_schema(document, merged, ref_stack + (ref,))


def _merge_object_shapes(shapes: list[dict[str, Any]]) -> dict[str, Any] | None:
    """Merge object branches so allOf and equivalent inline schemas compare equally."""
    if not shapes or not all(
        isinstance(shape, dict)
        and (shape.get("type") == "object" or "properties" in shape or "required" in shape)
        for shape in shapes
    ):
        return None
    merged: dict[str, Any] = {"type": "object"}
    properties: dict[str, Any] = {}
    required: set[str] = set()
    for shape in shapes:
        for name, property_shape in (shape.get("properties", {}) or {}).items():
            # Later allOf branches intentionally narrow generic envelope fields such
            # as data to the concrete payload schema.
            properties[name] = property_shape
        required.update(str(name) for name in shape.get("required", []) or [])
        for key, value in shape.items():
            if key not in {"type", "properties", "required"}:
                merged[key] = value
    if properties:
        merged["properties"] = {name: properties[name] for name in sorted(properties)}
    if required:
        merged["required"] = sorted(required)
    return merged


def _schema_shape(document: dict[str, Any], value: Any, ref_stack: tuple[str, ...] = ()) -> Any:
    """Return a stable structural representation of a response schema.

    Local refs are expanded with a recursion guard. Object-valued allOf branches are
    merged, and ``properties`` is treated as a property map rather than as a schema;
    this lets springdoc inline an equivalent schema without producing a false drift.
    Descriptions and examples are excluded, while wire-relevant flags such as
    readOnly/writeOnly remain part of the shape.
    """
    if isinstance(value, list):
        return [_schema_shape(document, child, ref_stack) for child in value]
    if not isinstance(value, dict):
        return value

    value = {key: child for key, child in value.items()
             if key not in {"description", "title", "example", "examples", "deprecated", "externalDocs", "xml"}
             and not key.startswith("x-")}
    # OpenAPI 3.0 nullable 与 3.1 的 null union 是同一约束；不能丢弃 null 分支。
    if value.get("nullable") is True:
        base = {key: child for key, child in value.items() if key != "nullable"}
        return _schema_shape(document, {"anyOf": [base, {"type": "null"}]}, ref_stack)
    if isinstance(value.get("type"), list):
        kinds = set(value["type"])
        base = {key: child for key, child in value.items() if key != "type"}
        if kinds == {"object", "array", "string", "number", "boolean", "null"} and not base:
            return {}
        return _schema_shape(document, {"anyOf": [{**base, "type": kind} for kind in sorted(kinds)]}, ref_stack)

    if value.get("type") == "null":
        # String/numeric/object-specific keywords do not constrain the null value.
        value = {key: child for key, child in value.items()
                 if key not in {"format", "minLength", "maxLength", "pattern", "properties",
                                "additionalProperties", "required", "items", "minimum", "maximum"}}

    ref = value.get("$ref")
    if isinstance(ref, str) and ref.startswith("#/"):
        if ref in ref_stack:
            return {"$ref": ref}
        resolved = resolve_ref(document, value)
        if len(value) == 1:
            return _schema_shape(document, resolved, ref_stack + (ref,))
        merged = dict(resolved) if isinstance(resolved, dict) else {}
        merged.update({key: child for key, child in value.items() if key != "$ref"})
        return _schema_shape(document, merged, ref_stack + (ref,))

    if isinstance(value.get("allOf"), list):
        base = {key: child for key, child in value.items() if key != "allOf"}
        branch_values = ([] if not base else [base]) + list(value["allOf"])
        branches = [_schema_shape(document, branch, ref_stack) for branch in branch_values]
        merged = _merge_object_shapes([branch for branch in branches if isinstance(branch, dict)])
        if merged is not None and all(isinstance(branch, dict) for branch in branches):
            return merged
        return {
            "allOf": sorted(
                branches,
                key=lambda branch: json.dumps(branch, sort_keys=True, ensure_ascii=False),
            )
        }

    ignored = {"description", "title", "example", "examples", "deprecated", "externalDocs", "xml"}
    result: dict[str, Any] = {}
    for key in sorted(value):
        if key in ignored or key.startswith("x-"):
            continue
        child = value[key]
        if key == "properties" and isinstance(child, dict):
            result[key] = {
                str(name): _schema_shape(document, property_schema, ref_stack)
                for name, property_schema in sorted(child.items(), key=lambda item: str(item[0]))
            }
        elif key == "required" and isinstance(child, list):
            result[key] = sorted(str(item) for item in child)
        elif key in {"anyOf", "oneOf"} and isinstance(child, list):
            branches = [_schema_shape(document, item, ref_stack) for item in child]
            # 唯一非 null 分支与 null 不相交，oneOf/anyOf 等价。
            normalized_key = "anyOf" if len(branches) == 2 and {"type": "null"} in branches else key
            result[normalized_key] = sorted(
                branches,
                key=lambda branch: json.dumps(branch, sort_keys=True, ensure_ascii=False),
            )
        elif key == "enum" and isinstance(child, list):
            result[key] = sorted(child, key=lambda item: json.dumps(item, sort_keys=True, ensure_ascii=False))
        elif key == "type" and isinstance(child, list):
            result[key] = sorted(str(item) for item in child)
        elif key == "additionalProperties" and (child is True or child == {}):
            result[key] = True
        elif key in {"items", "additionalProperties", "not", "contains", "propertyNames", "if", "then", "else"}:
            result[key] = _schema_shape(document, child, ref_stack)
        else:
            result[key] = _schema_shape(document, child, ref_stack)
    return result


def _is_json_media_type(media_type: str) -> bool:
    normalized = media_type.lower().split(";", 1)[0].strip()
    return normalized.startswith(JSON_MEDIA_TYPE_PREFIXES) or normalized.endswith("+json")


def _is_non_envelope_media_type(media_type: str) -> bool:
    normalized = media_type.lower().split(";", 1)[0].strip()
    return any(marker in normalized for marker in NON_ENVELOPE_MEDIA_MARKERS) or normalized.startswith(
        ("image/", "audio/", "video/", "multipart/")
    )


def _schema_declares_envelope(document: dict[str, Any], schema: Any, ref_stack: tuple[str, ...] = ()) -> bool:
    if not isinstance(schema, dict):
        return False
    ref = schema.get("$ref")
    if isinstance(ref, str) and ref.startswith("#/components/schemas/"):
        name = ref.rsplit("/", 1)[-1]
        if name == "ApiResponse" or name.startswith("ApiResponse"):
            return True
    if isinstance(schema.get("allOf"), list) and any(
        _schema_declares_envelope(document, item, ref_stack) for item in schema["allOf"]
    ):
        return True
    resolved = _resolved_schema(document, schema, ref_stack)
    if resolved is not schema and isinstance(resolved, dict):
        properties = resolved.get("properties", {})
        required = set(resolved.get("required", []) or [])
        return {"code", "message", "data", "trace_id"}.issubset(properties) and {
            "code", "message", "data", "trace_id"
        }.issubset(required)
    properties = schema.get("properties", {})
    required = set(schema.get("required", []) or [])
    return {"code", "message", "data", "trace_id"}.issubset(properties) and {
        "code", "message", "data", "trace_id"
    }.issubset(required)


def _response_shape(document: dict[str, Any], operation: dict[str, Any]) -> dict[str, Any]:
    responses = operation.get("responses", {}) or {}
    normalized: dict[str, Any] = {}
    for status, raw_response in responses.items():
        response = resolve_ref(document, raw_response)
        if not isinstance(response, dict):
            response = {}
        content = response.get("content", {}) or {}
        media_shapes: dict[str, Any] = {}
        for media_type, media in sorted(content.items()):
            media = media if isinstance(media, dict) else {}
            schema = media.get("schema")
            media_name = str(media_type).lower().split(";", 1)[0].strip()
            media_shapes[media_name] = {
                "nonEnvelope": _is_non_envelope_media_type(media_name),
                "schema": _schema_shape(document, schema) if schema is not None else None,
            }
        normalized[str(status)] = {
            "contentTypes": sorted(media_shapes),
            "media": media_shapes,
        }
    return {"statusCodes": sorted(normalized), "responses": normalized}


def _request_shape(document: dict[str, Any], operation: dict[str, Any]) -> dict[str, Any] | None:
    """Keep required bodies and typed wire schemas in runtime comparison."""
    if "requestBody" not in operation:
        return None
    body = resolve_ref(document, operation["requestBody"])
    if not isinstance(body, dict):
        return {"invalid": True}
    media_shapes = {}
    for media_type, media in sorted((body.get("content", {}) or {}).items()):
        media = media if isinstance(media, dict) else {}
        schema = media.get("schema")
        media_name = str(media_type).lower().split(";", 1)[0].strip()
        media_shapes[media_name] = _schema_shape(document, schema) if schema is not None else None
    return {"required": body.get("required") is True, "media": media_shapes}


def server_prefixes(document: dict[str, Any]) -> tuple[str, ...]:
    prefixes = {"/api/v1"}
    for server in document.get("servers", []) or []:
        if isinstance(server, dict) and isinstance(server.get("url"), str):
            path = urlparse(server["url"]).path.rstrip("/")
            if path:
                prefixes.add(path)
    return tuple(sorted(prefixes, key=len, reverse=True))


def normalize_runtime_path(path: str, document: dict[str, Any]) -> str:
    normalized = "/" + path.lstrip("/")
    for prefix in server_prefixes(document):
        if normalized == prefix:
            return "/"
        if normalized.startswith(prefix + "/"):
            return normalized[len(prefix):]
    return normalized


def operation_parameters(document: dict[str, Any], path_item: dict[str, Any], operation: dict[str, Any]) -> list[dict[str, Any]]:
    parameters: list[dict[str, Any]] = []
    for parameter in (path_item.get("parameters", []) or []) + (operation.get("parameters", []) or []):
        resolved = resolve_ref(document, parameter)
        if isinstance(resolved, dict):
            parameters.append(resolved)
    return parameters


def extract_operations(document: dict[str, Any], runtime: bool = False) -> dict[tuple[str, str], dict[str, Any]]:
    operations: dict[tuple[str, str], dict[str, Any]] = {}
    for raw_path, raw_item in (document.get("paths", {}) or {}).items():
        if not isinstance(raw_item, dict):
            continue
        path = normalize_runtime_path(raw_path, document) if runtime else raw_path
        if not path.startswith(OPEN_PLATFORM_PREFIX):
            continue
        for method in HTTP_METHODS:
            operation = raw_item.get(method)
            if not isinstance(operation, dict):
                continue
            params = operation_parameters(document, raw_item, operation)
            required_headers = sorted({
                str(parameter.get("name"))
                for parameter in params
                if parameter.get("in") == "header" and parameter.get("required") is True
            })
            operations[(method, path)] = {
                "method": method.upper(),
                "path": path,
                "requiredScope": operation.get("x-required-scope"),
                "requiredHeaders": required_headers,
                "requestBody": "requestBody" in operation,
                "requestShape": _request_shape(document, operation),
                "responseShape": _response_shape(document, operation),
            }
    return operations


def validate_contract(document: dict[str, Any]) -> list[str]:
    errors: list[str] = []
    if document.get("openapi") != "3.1.0":
        errors.append("contract must use OpenAPI 3.1.0")
    if document.get("info", {}).get("version") != "1.0.0":
        errors.append("contract info.version must be 1.0.0")
    if "servicePrincipalHmac" not in (document.get("components", {}).get("securitySchemes", {}) or {}):
        errors.append("servicePrincipalHmac security scheme is missing")

    def check_refs(value):
        if isinstance(value, dict):
            if "$ref" in value:
                try:
                    resolve_ref(document, value)
                except (KeyError, TypeError):
                    errors.append(f"unresolved reference: {value['$ref']}")
            for child in value.values():
                check_refs(child)
        elif isinstance(value, list):
            for child in value:
                check_refs(child)
    check_refs(document)
    if errors:
        return errors
    operations = extract_operations(document)
    if not operations:
        errors.append("contract does not declare any /open-platform operation")
    for (method, path), operation in operations.items():
        if not isinstance(operation["requiredScope"], str) or not operation["requiredScope"].strip():
            errors.append(f"{method.upper()} {path} has no x-required-scope")
        if any(marker in path for marker in FORBIDDEN_ROUTE_MARKERS):
            errors.append(f"forbidden direct execution route: {method.upper()} {path}")
        if "{" in path or "}" in path:
            errors.append(f"{method.upper()} {path} must use a fixed path")
        item = document["paths"][path]
        raw_operation = item[method]
        response_map = raw_operation.get("responses", {}) or {}
        missing_errors = [status for status in STANDARD_ERROR_STATUS_CODES if status not in response_map]
        if missing_errors:
            errors.append(
                f"{method.upper()} {path} is missing standard error responses: {', '.join(missing_errors)}"
            )
        for status, raw_response in response_map.items():
            response = resolve_ref(document, raw_response)
            if not isinstance(response, dict):
                errors.append(f"{method.upper()} {path} {status} has an invalid response")
                continue
            content = response.get("content", {}) or {}
            if str(status) in {"204", "304"}:
                if content:
                    errors.append(f"{method.upper()} {path} {status} must not have a response body")
                continue
            for media_type, media in content.items():
                if not _is_json_media_type(str(media_type)) or _is_non_envelope_media_type(str(media_type)):
                    continue
                schema = media.get("schema") if isinstance(media, dict) else None
                if not _schema_declares_envelope(document, schema):
                    errors.append(
                        f"{method.upper()} {path} {status} {media_type} must use ApiResponse envelope"
                    )
        for parameter in operation_parameters(document, item, raw_operation):
            if parameter.get("in") == "path":
                errors.append(f"{method.upper()} {path} must not declare path parameters")
            if method != "get" and parameter.get("in") == "query":
                binary_metadata = (method == "post" and path in BINARY_UPLOAD_METADATA
                                   and parameter.get("name") in BINARY_UPLOAD_METADATA[path]
                                   and set(raw_operation.get("requestBody", {}).get("content", {})) == {"application/octet-stream"})
                if not binary_metadata:
                    errors.append(f"{method.upper()} {path} must put write parameters in the request body")
        if (method, path) in REQUIRED_IDEMPOTENCY_ROUTES and "Idempotency-Key" not in operation["requiredHeaders"]:
            errors.append(f"{method.upper()} {path} must require Idempotency-Key")

    resource_types = ((document.get("components", {}).get("schemas", {}) or {}).get("ResourceType", {}) or {}).get("enum", [])
    expected_resource_types = ["agent", "workflow", "skill", "persona", "policy", "tool", "connector", "channel", "knowledge_base"]
    if resource_types != expected_resource_types:
        errors.append("ResourceType enum is not the stable v1 resource list")
    return errors


def operation_parameters_for_key(document: dict[str, Any], method: str, path: str, placeholder: str) -> list[dict[str, Any]]:
    path_item = (document.get("paths", {}) or {}).get(path, {})
    operation = path_item.get(method, {}) if isinstance(path_item, dict) else {}
    return [parameter for parameter in operation_parameters(document, path_item, operation)
            if parameter.get("name") == placeholder]


def _path_placeholders(path: str) -> list[str]:
    return [part[1:-1] for part in path.split("/") if part.startswith("{") and part.endswith("}")]


def diff_documents(expected: dict[str, Any], runtime: dict[str, Any]) -> list[str]:
    expected_ops = extract_operations(expected)
    actual_ops = extract_operations(runtime, runtime=True)
    errors: list[str] = []
    for key in sorted(set(expected_ops) - set(actual_ops)):
        errors.append(f"runtime is missing {key[0].upper()} {key[1]}")
    for key in sorted(set(actual_ops) - set(expected_ops)):
        errors.append(f"runtime exposes undocumented {key[0].upper()} {key[1]}")
    for key in sorted(set(expected_ops) & set(actual_ops)):
        expected_operation = expected_ops[key]
        actual_operation = actual_ops[key]
        actual_scope = actual_operation.get("requiredScope")
        if actual_scope != expected_operation["requiredScope"]:
            errors.append(
                f"scope mismatch for {key[0].upper()} {key[1]}: "
                f"contract={expected_operation['requiredScope']} runtime={actual_scope}"
            )
        if expected_operation["requiredHeaders"] != actual_operation["requiredHeaders"]:
            errors.append(
                f"required headers mismatch for {key[0].upper()} {key[1]}: "
                f"contract={expected_operation['requiredHeaders']} runtime={actual_operation['requiredHeaders']}"
            )
        if expected_operation["requestShape"] != actual_operation["requestShape"]:
            expected_request = hashlib.sha256(json.dumps(
                expected_operation["requestShape"], sort_keys=True, ensure_ascii=False
            ).encode("utf-8")).hexdigest()[:12]
            actual_request = hashlib.sha256(json.dumps(
                actual_operation["requestShape"], sort_keys=True, ensure_ascii=False
            ).encode("utf-8")).hexdigest()[:12]
            errors.append(
                f"request schema mismatch for {key[0].upper()} {key[1]}: "
                f"contract={expected_request} runtime={actual_request}"
            )
        expected_shape = expected_operation["responseShape"]
        actual_shape = actual_operation["responseShape"]
        expected_statuses = set(expected_shape["statusCodes"])
        actual_statuses = set(actual_shape["statusCodes"])
        if expected_statuses != actual_statuses:
            errors.append(
                f"response status mismatch for {key[0].upper()} {key[1]}: "
                f"contract={sorted(expected_statuses)} runtime={sorted(actual_statuses)}"
            )
        for status in sorted(expected_statuses & actual_statuses):
            expected_response = expected_shape["responses"][status]
            actual_response = actual_shape["responses"][status]
            expected_types = set(expected_response["contentTypes"])
            actual_types = set(actual_response["contentTypes"])
            if expected_types != actual_types:
                errors.append(
                    f"response content mismatch for {key[0].upper()} {key[1]} {status}: "
                    f"contract={sorted(expected_types)} runtime={sorted(actual_types)}"
                )
                continue
            for media_type in sorted(expected_types):
                expected_media = expected_response["media"][media_type]
                actual_media = actual_response["media"][media_type]
                if expected_media["nonEnvelope"] or actual_media["nonEnvelope"]:
                    continue
                if expected_media["schema"] != actual_media["schema"]:
                    expected_digest = hashlib.sha256(
                        json.dumps(expected_media["schema"], sort_keys=True, ensure_ascii=False).encode("utf-8")
                    ).hexdigest()[:12]
                    actual_digest = hashlib.sha256(
                        json.dumps(actual_media["schema"], sort_keys=True, ensure_ascii=False).encode("utf-8")
                    ).hexdigest()[:12]
                    errors.append(
                        f"response schema mismatch for {key[0].upper()} {key[1]} {status} {media_type}: "
                        f"contract={expected_digest} runtime={actual_digest}"
                    )
    return errors


def render_generated_sdk(document: dict[str, Any]) -> str:
    operations = extract_operations(document)
    rows = []
    for key in sorted(operations):
        operation = operations[key]
        rows.append(
            "  { method: %s, path: %s, requiredScope: %s, requiredHeaders: %s, requestBody: %s },"
            % (
                json.dumps(operation["method"]),
                json.dumps(operation["path"]),
                json.dumps(operation["requiredScope"]),
                json.dumps(operation["requiredHeaders"], separators=(",", ":")),
                "true" if operation["requestBody"] else "false",
            )
        )
    return """// Generated by tools/open_platform_contract.py; do not edit by hand.

export type OpenPlatformV1HttpMethod = "GET" | "POST" | "PUT" | "PATCH" | "DELETE" | "HEAD" | "OPTIONS" | "TRACE";

export interface OpenPlatformV1Operation {
  readonly method: OpenPlatformV1HttpMethod;
  readonly path: string;
  readonly requiredScope: string;
  readonly requiredHeaders: readonly string[];
  readonly requestBody: boolean;
}

export const OPEN_PLATFORM_V1_VERSION = "1.0.0" as const;

export const OPEN_PLATFORM_V1_OPERATIONS: readonly OpenPlatformV1Operation[] = [
%s
] as const;
""" % "\n".join(rows)


def write_generated(contract: Path, output: Path, check: bool) -> int:
    generated = render_generated_sdk(load_document(contract))
    if check:
        if not output.exists() or output.read_text(encoding="utf-8") != generated:
            print(f"generated SDK is stale: {output}", file=sys.stderr)
            return 1
        return 0
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(generated, encoding="utf-8", newline="\n")
    return 0


def publish_artifact(contract: Path, output_dir: Path) -> None:
    document = load_document(contract)
    output_dir.mkdir(parents=True, exist_ok=True)
    contract_bytes = contract.read_bytes()
    (output_dir / "open-platform-v1.yaml").write_bytes(contract_bytes)
    (output_dir / "open-platform-v1-contract.ts").write_text(
        render_generated_sdk(document), encoding="utf-8", newline="\n"
    )
    manifest = {
        "contract": "open-platform-v1",
        "version": document["info"]["version"],
        "sha256": hashlib.sha256(contract_bytes).hexdigest(),
        "operationCount": len(extract_operations(document)),
    }
    (output_dir / "manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n"
    )


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)

    check = subparsers.add_parser("check", help="validate the checked-in contract")
    check.add_argument("--contract", default=str(DEFAULT_CONTRACT))

    diff = subparsers.add_parser("diff", help="compare the contract to a runtime OpenAPI JSON/YAML document")
    diff.add_argument("--contract", default=str(DEFAULT_CONTRACT))
    diff.add_argument("--runtime", required=True, help="runtime OpenAPI file or URL")

    generate = subparsers.add_parser("generate", help="generate the TypeScript contract metadata")
    generate.add_argument("--contract", default=str(DEFAULT_CONTRACT))
    generate.add_argument("--output", default=str(DEFAULT_GENERATED))
    generate.add_argument("--check", action="store_true", help="fail if the generated file is stale")

    publish = subparsers.add_parser("publish", help="assemble a versioned SDK contract artifact")
    publish.add_argument("--contract", default=str(DEFAULT_CONTRACT))
    publish.add_argument("--output-dir", required=True)
    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    if args.command == "check":
        errors = validate_contract(load_document(args.contract))
        if errors:
            print("\n".join(errors), file=sys.stderr)
            return 1
        print("Open Platform v1 contract is valid")
        return 0
    if args.command == "diff":
        errors = diff_documents(load_document(args.contract), load_document(args.runtime))
        if errors:
            print("\n".join(errors), file=sys.stderr)
            return 1
        print("Open Platform v1 runtime diff is clean")
        return 0
    if args.command == "generate":
        return write_generated(Path(args.contract), Path(args.output), args.check)
    if args.command == "publish":
        publish_artifact(Path(args.contract), Path(args.output_dir))
        print(f"published Open Platform v1 artifact to {args.output_dir}")
        return 0
    raise AssertionError(f"unhandled command: {args.command}")


if __name__ == "__main__":
    raise SystemExit(main())
