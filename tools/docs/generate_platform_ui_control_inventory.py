#!/usr/bin/env python3
"""Build the checked-in inventory of real platform-admin UI controls.

The parser is intentionally conservative: it records source constructs that are
present in Vue templates/scripts and reports missing bindings instead of filling
them from the target UI specification.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from html.parser import HTMLParser
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[2]
VIEWS = ROOT / "frontend-platform" / "src" / "views"
PLATFORM_VIEWS = VIEWS / "platform"
ROUTER = ROOT / "frontend-platform" / "src" / "router" / "index.ts"
OUTPUT = ROOT / "docs" / "08-数据与工具" / "platform-ui-control-inventory.json"

PAGE_SOURCES: dict[str, tuple[str, list[str]]] = {
    "P01": ("工作台", ["frontend-platform/src/views/platform/HomePage.vue"]),
    "P02": ("系统状态", ["frontend-platform/src/views/platform/DashboardPage.vue"]),
    "P03": ("机构目录与工作台入口", [
        "frontend-platform/src/views/platform/TenantsPage.vue",
        "frontend-shared/src/institution/InstitutionDirectory.vue",
    ]),
    "P04": ("机构工作台详情", [
        "frontend-platform/src/views/platform/TenantManagePage.vue",
        "frontend-platform/src/views/platform/InstitutionWorkspacePage.vue",
        "frontend-platform/src/views/platform/TenantSettingsPanel.vue",
        "frontend-admin/src/views/admin/InstitutionWorkspacePage.vue",
        "frontend-shared/src/institution/InstitutionWorkspace.vue",
    ]),
    "P05": ("模型费用账单", [
        "frontend-platform/src/views/platform/BillingPage.vue",
        "frontend-admin/src/views/admin/BillingRecordsPage.vue",
        "frontend-shared/src/components/ModelBillingDetails.vue",
    ]),
    "P06": ("运营管理", ["frontend-platform/src/views/platform/OperationsPage.vue"]),
    "P07": ("工作流审核", ["frontend-platform/src/views/platform/ReviewPage.vue"]),
    "P08": ("智能体模板列表", ["frontend-platform/src/views/platform/AgentTemplatesPage.vue"]),
    "P09": ("模板新建与详情", ["frontend-platform/src/views/platform/AgentTemplateDetailPage.vue"]),
    "P10": ("统一模型管理及维护", [
        "frontend-platform/src/views/platform/ModelsPage.vue",
        "frontend-platform/src/views/platform/ModelDetailPage.vue",
        "frontend-platform/src/views/platform/ModelTimePricingEditor.vue",
        "frontend-platform/src/views/platform/ModelTimePricingPresets.vue",
    ]),
    "P11": ("系统智能体列表", ["frontend-platform/src/views/platform/SystemAgentsPage.vue"]),
    "P12": ("系统智能体详情", ["frontend-platform/src/views/platform/SystemAgentDetailPage.vue"]),
    "P13": ("平台工具部署", ["frontend-platform/src/views/platform/SystemAgentToolsPage.vue"]),
    "P14": ("系统配置", ["frontend-platform/src/views/platform/SystemSettingsPage.vue"]),
    "P15": ("能力目录及详情", [
        "frontend-platform/src/views/platform/CapabilityCatalogPage.vue",
        "frontend-platform/src/views/platform/CapabilityDetailPage.vue",
    ]),
    "P16": ("技能维护列表", ["frontend-platform/src/views/platform/SkillWorkbenchListPage.vue"]),
    "P17": ("技能维护工作区", ["frontend-platform/src/views/platform/SkillWorkbenchPage.vue"]),
    "P18": ("登录页", ["frontend-platform/src/views/LoginPage.vue"]),
    "P19": ("403页面", ["frontend-platform/src/views/platform/ForbiddenPage.vue"]),
    "P20": ("404页面", ["frontend-platform/src/views/platform/NotFoundPage.vue"]),
    "P21": ("平台与机构会话观测及轨迹", [
        "frontend-platform/src/views/platform/SessionsPage.vue",
        "frontend-platform/src/views/platform/SessionDetailPage.vue",
        "frontend-platform/src/views/platform/TrajectoriesPage.vue",
        "frontend-platform/src/views/platform/TrajectoryDetailPage.vue",
        "frontend-admin/src/views/SessionTracePage.vue",
        "frontend-admin/src/views/TrajectoryDetailPage.vue",
        "frontend-admin/src/views/admin/TrajectoriesPage.vue",
        "frontend-shared/src/observation/ObservationTree.vue",
        "frontend-shared/src/trajectory-list/TrajectoryListView.vue",
    ]),
    "P22": ("平台管理身份", ["frontend-platform/src/views/platform/ManagementPrincipalsPage.vue"]),
    "P23": ("机构开通、映射与执行凭证", [
        "frontend-platform/src/views/platform/InstitutionOnboardingFlow.vue",
        "frontend-platform/src/views/platform/InstitutionIntegrationsPage.vue",
        "frontend-platform/src/views/platform/InstitutionCredentialsPage.vue",
    ]),
    "P24": ("平台直授与机构公共 Agent 部署", [
        "frontend-platform/src/views/platform/InstitutionAgentSetup.vue",
        "frontend-platform/src/views/platform/AgentGrantsPage.vue",
        "frontend-platform/src/views/platform/AgentDeploymentsPage.vue",
        "frontend-admin/src/views/admin/AgentDeploymentsPage.vue",
    ]),
    "P25": ("平台与机构用量", [
        "frontend-platform/src/views/platform/UsagePage.vue",
        "frontend-admin/src/views/admin/UsagePage.vue",
        "frontend-shared/src/integration/UsagePanel.vue",
    ]),
    "P26": ("组织与权限", ["frontend-shared/src/foundation/access/AccessPage.vue"]),
    "P27": ("账号安全", ["frontend-shared/src/foundation/security/SecuritySettingsPage.vue", "frontend-shared/src/foundation/security/SecurityChallenge.vue", "frontend-shared/src/foundation/security/AccountRecovery.vue"]),
    "P28": ("菜单、字典与配置", ["frontend-shared/src/foundation/catalog/CatalogPage.vue"]),
    "P29": ("公告与通知", ["frontend-shared/src/foundation/messaging/MessagingPage.vue"]),
    "P30": ("公共文件", ["frontend-shared/src/foundation/assets/FoundationFilesPage.vue"]),
    "P31": ("导入导出与管理任务", ["frontend-shared/src/foundation/assets/FoundationJobsPage.vue"]),
    "P32": ("日志与运维", ["frontend-shared/src/foundation/operations/OperationsPage.vue"]),
}

FOUNDATION_PAGE_IDS = {"access": "P26", "security": "P27", "catalog": "P28", "messaging": "P29", "assets": "P30", "jobs": "P31", "operations": "P32"}

SHARED_SOURCES = [
    "frontend-platform/src/views/platform/Layout.vue",
    "frontend-shared/src/admin/AdminLayout.vue",
    "frontend-platform/src/views/FoundationPage.vue",
    "frontend-shared/src/foundation/FoundationPage.vue",
]

TAG_RE = re.compile(
    r"<([A-Za-z][\w.-]*)(\s+(?:(?:[^\"'>]|\"[^\"]*\"|'[^']*')*))?/?>",
    re.S,
)
TEXT_RE = re.compile(r"\s+|\{\{.*?\}\}|<!--.*?-->", re.S)
I18N_RE = re.compile(r"(?:\$?t)\(\s*[\"']([^\"']+)[\"']")
EVENT_RE = re.compile(r"(?:@|v-on:)([\w:-]+)\s*=\s*[\"']([^\"']+)[\"']")
ATTR_RE = re.compile(r"([:\w@.-]+)(?:\s*=\s*(\"[^\"]*\"|'[^']*'|[^\s>]+))?")

INTERACTIVE_TAGS = {
    'a', 'button', 'form', 'input', 'label', 'option', 'select', 'textarea',
    'router-link', 'RouterLink', 'table', 'th', 'tr', 'td', 'nav',
    'ElButton', 'ElInput', 'ElInputNumber', 'ElSelect', 'ElSelectV2', 'ElOption',
    'ElCheckbox', 'ElSwitch', 'ElRadio', 'ElRadioGroup', 'ElDatePicker', 'ElUpload',
    'ElTabs', 'ElTabPane', 'ElDropdown', 'ElDropdownItem', 'ElPopover', 'ElTooltip',
    'ElForm', 'ElFormItem', 'ElTable', 'ElTableColumn', 'ElPagination', 'ElAlert',
    'ElSkeleton', 'ElEmpty', 'ElTag', 'ElCard', 'ElDrawer', 'ElDialog',
    'ElCollapse', 'ElCollapseItem', 'ElMenu', 'ElMenuItem', 'ElMenuItemGroup',
    'ElBreadcrumb', 'ElBreadcrumbItem', 'ElDescriptions', 'ElDescriptionsItem',
    'ElStatistic', 'ElProgress', 'ElImage',
    'ElDropdownMenu', 'ElCheckboxGroup', 'ElRadioButton', 'ElColorPicker', 'CodeEditor',
}
COMPONENT_TAG_ALIASES = {re.sub(r"(?<!^)(?=[A-Z])", "-", tag).lower(): tag
                         for tag in INTERACTIVE_TAGS if tag.startswith("El")}
TAG_BINDINGS = {tag: f'element-plus.{tag}' for tag in INTERACTIVE_TAGS if tag.startswith('El')}
SHARED_COMPONENTS = {path.stem: path.relative_to(ROOT).as_posix()
                     for folder in ('admin', 'components', 'foundation')
                     for path in (ROOT / 'frontend-shared/src' / folder).rglob('*.vue')}
INTERACTIVE_TAGS.update(SHARED_COMPONENTS)
TAG_BINDINGS.update({name: f'admin-domain.{name}' for name in SHARED_COMPONENTS})


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def route_records() -> list[dict[str, str]]:
    text = read(ROUTER)
    records: list[dict[str, str]] = []
    pattern = re.compile(
        r'\{\s*path:\s*["\']([^"\']*)["\'],\s*component:\s*\(\)\s*=>\s*import\(["\']([^"\']+)["\']\)'
    )
    for match in pattern.finditer(text):
        route = match.group(1)
        if route and not route.startswith("/"):
            route = "/platform/" + route
        component = match.group(2).replace("../", "frontend-platform/src/")
        page_id = FOUNDATION_PAGE_IDS.get(route.removeprefix("/platform/").removeprefix("foundation/")) if "foundation/" in route else None
        if page_id is None:
            page_id = next((key for key, (_, paths) in PAGE_SOURCES.items() if component in paths),
                           "shared-shell" if component.endswith("/Layout.vue") else "unmapped-page")
        records.append({"path": route or "/platform", "component": component, "page_id": page_id})
    return records


def attrs(text: str) -> dict[str, str]:
    result: dict[str, str] = {}
    for match in ATTR_RE.finditer(text):
        key = match.group(1)
        value = match.group(2) or "true"
        result[key] = value.strip("\"'")
    return result


def clean_label(text: str) -> str:
    value = TEXT_RE.sub(" ", text)
    value = re.sub(r"<[^>]+>", " ", value)
    value = re.sub(r"\s+", " ", value).strip(" \t|/·")
    return value[:160]


def source_link(path: str, line: int | None = None) -> str:
    return f"{path}:{line}" if line else path


def balanced_region(text: str, start: int, opening: str, closing: str) -> tuple[str, int, int] | None:
    """Return the balanced region beginning at an opening delimiter.

    Vue scripts contain nested arrays, objects, strings and template literals. A
    small lexical scanner is sufficient here and avoids treating a `]` inside a
    translated label or a comment as the end of a column/action list.
    """
    if start < 0 or start >= len(text) or text[start] != opening:
        return None
    depth = 0
    quote = ""
    escaped = False
    line_comment = False
    block_comment = False
    index = start
    while index < len(text):
        char = text[index]
        next_char = text[index + 1] if index + 1 < len(text) else ""
        if line_comment:
            if char == "\n":
                line_comment = False
            index += 1
            continue
        if block_comment:
            if char == "*" and next_char == "/":
                block_comment = False
                index += 2
            else:
                index += 1
            continue
        if quote:
            if escaped:
                escaped = False
            elif char == "\\":
                escaped = True
            elif char == quote:
                quote = ""
            index += 1
            continue
        if char == "/" and next_char == "/":
            line_comment = True
            index += 2
            continue
        if char == "/" and next_char == "*":
            block_comment = True
            index += 2
            continue
        if char in {"'", '"', "`"}:
            quote = char
            index += 1
            continue
        if char == opening:
            depth += 1
        elif char == closing:
            depth -= 1
            if depth == 0:
                return text[start + 1:index], start, index
        index += 1
    return None


def first_array_after(text: str, start: int) -> tuple[str, int, int] | None:
    opening = text.find("[", start)
    if opening < 0:
        return None
    return balanced_region(text, opening, "[", "]")


def first_nonempty_array_after(text: str, start: int) -> tuple[str, int, int] | None:
    """Skip TypeScript array type suffixes before the assigned literal."""
    cursor = start
    while cursor < len(text):
        array = first_array_after(text, cursor)
        if not array:
            return None
        if array[0].strip():
            return array
        cursor = array[2] + 1
    return None


def object_literals(array_text: str, absolute_start: int) -> list[tuple[str, int]]:
    """Find top-level object literals in an array, including conditional arrays."""
    result: list[tuple[str, int]] = []
    index = 0
    while index < len(array_text):
        if array_text[index] != "{":
            index += 1
            continue
        region = balanced_region(array_text, index, "{", "}")
        if not region:
            break
        body, begin, end = region
        result.append((body, absolute_start + begin))
        index = end + 1
    return result


def template_region(text: str) -> tuple[str, int]:
    # Vue 的具名插槽也使用 template，不能在第一个插槽闭合处截断页面。
    tokens = re.finditer(r"</?template(?:\s+(?:[^\"'>]|\"[^\"]*\"|'[^']*')*)?>", text)
    depth = 0
    start = None
    for token in tokens:
        if token.group().startswith("</"):
            depth -= 1
            if depth == 0 and start is not None:
                return text[start:token.start()], text[:start].count("\n") + 1
        else:
            if depth == 0:
                start = token.end()
            depth += 1
    return "", 0


class TemplateObjects(HTMLParser):
    """记录祖先条件和独立文字节点；不把模板实例数量当运行时对象数量。"""

    def __init__(self, region: str):
        super().__init__(convert_charrefs=False)
        self.region = region
        self.line_offsets = [0, *[match.end() for match in re.finditer("\n", region)]]
        self.stack: list[tuple[str, list[str]]] = []
        self.nodes: list[dict[str, Any]] = []
        self.feed(region)

    def conditions(self) -> list[str]:
        return [condition for _, conditions in self.stack for condition in conditions]

    def handle_starttag(self, tag, attributes):
        raw = self.get_starttag_text()
        match = TAG_RE.match(raw)
        if not match:
            return
        original, attr_text = match.group(1), match.group(2) or ""
        attributes = attrs(attr_text)
        own = [f"{key}={value}" for key, value in attributes.items()
               if key in {"v-if", "v-else-if", "v-else", "v-show", "v-permission", "v-for"} or key.startswith("#")]
        self.nodes.append({"tag": original, "attrs": attr_text, "line": self.getpos()[0],
                           "conditions": self.conditions() + own, "raw": raw,
                           "offset": self.line_offsets[self.getpos()[0] - 1] + self.getpos()[1]})
        if tag not in {"input", "img", "br", "hr", "meta", "link", "area", "base", "embed", "source", "wbr"}:
            self.stack.append((tag, own))

    def handle_startendtag(self, tag, attributes):
        depth = len(self.stack)
        self.handle_starttag(tag, attributes)
        del self.stack[depth:]

    def handle_endtag(self, tag):
        for index in range(len(self.stack) - 1, -1, -1):
            if self.stack[index][0] == tag:
                del self.stack[index:]
                break

    def handle_data(self, data):
        text = re.sub(r"\s+", " ", data).strip()
        if text:
            self.nodes.append({"tag": "#text", "line": self.getpos()[0], "text": text,
                               "conditions": self.conditions(), "parent": self.stack[-1][0] if self.stack else "root"})

    def handle_entityref(self, name):
        self.handle_data(f"&{name};")

    def handle_charref(self, name):
        self.handle_data(f"&#{name};")


def infer_spec_ids(tag: str, page_id: str, attrs_text: str) -> list[str]:
    ids = [page_id]
    if tag in {"ElInput", "ElInput", "ElInput", "ElInputNumber", "ElSelect", "ElSelectV2", "ElSelect", "ElDatePicker", "ElDatePicker", "ElDatePicker", "ElSwitch", "ElCheckbox", "ElRadioGroup", "ElUpload", "CodeEditor"}:
        ids += ["G31", "G32", "G33"]
    if tag in {"ElTable", "ElTableColumn", "table", "ElPagination", "th", "tr", "td"}:
        ids += ["F09", "F11", "F26", "F39"]
    if tag in {"ElDropdown", "ElDropdownMenu", "ElDropdownItem"}:
        ids += ["B14", "H25"]
    if tag in {"ElDrawer", "ElDialog", "ElMessageBox", "ElPopover"}:
        ids += ["H21", "H24", "H25", "H28"]
    if tag in {"ElSkeleton", "ElEmpty", "ElAlert"}:
        ids += ["E17", "F39", "J10"]
    if "v-if" in attrs_text or "v-else" in attrs_text or "v-permission" in attrs_text:
        ids += ["A18", "B20"]
    return list(dict.fromkeys(ids))


def label_for(tag: str, attrs_map: dict[str, str], following: str) -> tuple[str, str]:
    for key in ("aria-label", ":aria-label", "label", ":label", "title", ":title", "placeholder", ":placeholder"):
        if key in attrs_map:
            value = attrs_map[key]
            key_match = I18N_RE.search(value)
            if key_match:
                return key_match.group(1), "i18n"
            if value and value != "true":
                return value, "literal"
    closing = re.search(rf"</{re.escape(tag)}>", following, re.S)
    if closing:
        labels = I18N_RE.findall(following[: closing.start()])
        if labels:
            return labels[0], "i18n"
        literal = clean_label(following[: closing.start()])
        if literal:
            return literal, "literal"
    return "", "unbound"


def action_list(attrs_text: str) -> list[dict[str, str]]:
    actions: list[dict[str, str]] = []
    for key, expression in attrs(attrs_text).items():
        if key.startswith("@") or key.startswith("v-on:"):
            actions.append({"event": key.removeprefix("@").removeprefix("v-on:"), "expression": expression})
        elif key in {"to", ":to", "href", ":href", ":model-value", ":before-close"} or key.startswith("v-model"):
            actions.append({"event": key, "expression": expression})
    return actions


def outcome_for(actions: list[dict[str, str]], tag: str) -> str:
    expressions = " ".join(item["expression"] for item in actions)
    if "router.push" in expressions or "router.replace" in expressions or any(item["event"] in {"to", ":to"} for item in actions):
        return "route_navigation"
    if "retry" in expressions.lower():
        return "retry_read"
    if any(item["event"].split(".")[0] in {"submit", "click", "select"} for item in actions):
        return "page_handler_or_business_action"
    if "model" in tag.lower() or any(item["event"].startswith(("update:", "v-model", ":model-value")) for item in actions):
        return "local_state_or_field_update"
    return "display_or_state_binding"


def gaps_for(tag: str, label_kind: str, attrs_map: dict[str, str], actions: list[dict[str, str]], page_text: str) -> list[str]:
    gaps: list[str] = []
    if label_kind == "unbound" and tag not in {"tr", "td", "th", "table", "nav"}:
        gaps.append("label_binding_not_found_in_template")
    if label_kind == "literal":
        gaps.append("literal_label_requires_i18n_review")
    if tag in {"ElInput", "ElInput", "ElInput", "ElInputNumber", "ElSelect", "ElSelectV2", "ElSelect", "ElDatePicker", "ElDatePicker", "ElDatePicker", "ElUpload", "CodeEditor"}:
        if not any(key in attrs_map for key in ("min", ":min", "max", ":max", "step", ":step", "maxlength", ":maxlength", "accept")):
            gaps.append("field_constraints_not_bound_in_template")
        if "ElFormItem" not in page_text and tag not in {"ElInput"}:
            gaps.append("field_label_container_not_found_in_same_page")
    if tag in {"ElButton", "ElDropdown", "ElDropdownMenu", "ElDrawer", "ElDialog", "ElMessageBox"} and not actions:
        gaps.append("action_binding_not_found_in_template")
    if "v-permission" not in page_text and tag in {"ElButton", "ElButton", "ElDropdownItem"}:
        gaps.append("permission_binding_not_found_in_template")
    if "schema" in page_text.lower() or "field.type" in page_text:
        gaps.append("dynamic_schema_binding_requires_contract_review")
    return list(dict.fromkeys(gaps))


def api_refs(text: str) -> list[str]:
    return sorted(set(re.findall(r"\bapi\.[A-Za-z0-9_]+(?:\.[A-Za-z0-9_]+)*", text)))


def add_control(
    controls: list[dict[str, Any]],
    *,
    page_id: str,
    page_title: str,
    source_path: str,
    line: int,
    tag: str,
    attrs_text: str,
    snippet: str,
    ordinal: int,
    page_text: str,
    api_symbols: list[str],
) -> None:
    attrs_map = attrs(attrs_text)
    actions = action_list(attrs_text)
    label, label_kind = label_for(tag, attrs_map, snippet)
    visibility = [attrs_map[key] for key in ("v-if", "v-else-if", "v-else", "v-show", "v-permission") if key in attrs_map]
    enabled = attrs_map.get(":disabled", attrs_map.get("disabled", "not_declared"))
    control_type = re.sub(r"^Ui", "", tag).lower().replace("-", "_")
    if tag in {"input", "textarea", "select"}:
        control_type = f"native_{tag}"
    if tag in {"table", "th", "tr", "td"}:
        control_type = f"data_{tag}"
    if tag == "ElTableColumn":
        control_type = "table_column"
    object_id = f"{page_id}.{Path(source_path).stem}.{control_type}.{ordinal:03d}"
    source_components = [source_link(source_path, line)]
    source_components.extend(f"frontend-platform/src/api/index.ts#{symbol}" for symbol in api_symbols[:12])
    entry = {
        "object_id": object_id,
        "page_id": page_id,
        "page_title": page_title,
        "spec_ids": infer_spec_ids(tag, page_id, attrs_text),
        "spec_binding_status": "candidate_requires_review",
        "source_component": source_path,
        "source_symbol": tag,
        "source_line": line,
        "control_type": control_type,
        "label_key_or_text": label,
        "label_source": label_kind,
        "visibility": visibility or ["always"],
        "enabled": enabled,
        "repeat": attrs_map.get("v-for", ""),
        "action": actions,
        "outcome": outcome_for(actions, tag),
        "recovery": ["retry_handler_present"] if "retry" in attrs_text.lower() else [],
        "contract_sources": [
            "docs/03-规范与术语/06-后台逐控件规格.md",
            "docs/02-架构与设计/09-前端视觉与交互规范.md",
            "docs/02-架构与设计/08-前端共享层设计.md",
            "docs/02-架构与设计/13-前端组件库.md",
            *source_components,
        ],
        "binding_definition_ref": TAG_BINDINGS.get(tag, "native-or-page-template"),
        "verification": {"status": "not_executed", "evidence": [], "test_ids": []},
        "gaps": gaps_for(tag, label_kind, attrs_map, actions, page_text),
    }
    controls.append(entry)
    if tag == "th":
        # Native tables do not have a script `columns` array. Keep the native
        # header control above and add a column-level record so every visible
        # table column is independently addressable.
        column_entry = dict(entry)
        column_entry.update({
            "object_id": f"{page_id}.{Path(source_path).stem}.column.native.{ordinal:03d}",
            "spec_ids": [page_id, "F09", "F11", "F26", "F39"],
            "source_symbol": f"table_header[{ordinal}]",
            "control_type": "table_column",
            "visibility": ["column_configured"],
            "enabled": "column_read_only",
            "repeat": "one per rendered row",
            "action": [],
            "outcome": "table_cell_display",
            "binding_definition_ref": "native-table-column",
        })
        controls.append(column_entry)


def script_symbols(
    controls: list[dict[str, Any]],
    page_id: str,
    title: str,
    source_path: str,
    text: str,
    api_symbols: list[str],
    template: str,
) -> None:
    names: set[str] = set()
    function_names = {
        match.group(1)
        for match in re.finditer(r"\b(?:async\s+)?function\s+([A-Za-z_$][\w$]*)", text)
    }
    # A function is a control binding only when the template actually refers
    # to it. Lifecycle/data-loading helpers remain provenance in source files,
    # rather than becoming fictitious UI controls.
    for name in function_names:
        if re.search(rf"\b{re.escape(name)}\b", template):
            names.add(name)
    for match in re.finditer(r"\bconst\s+(columns|rowActions|actions|groupTabs|tabs|statusOptions|availableCapabilities)\s*=", text):
        names.add(match.group(1))
    for symbol in sorted(names):
        if not re.search(r"(?:rowActions|actions|columns|groupTabs|tabs|statusOptions|availableCapabilities|confirm|open|close|save|publish|retry|reset|load|fetch|create|update|delete|remove|toggle|run|show|copy|rollback|validate|select|switch|upload|download|rename|newFile|newFolder|add|apply|change|focus|submit|act|operate|test|debug|archive|deprecate)", symbol, re.I):
            continue
        symbol_match = re.search(rf"\b{re.escape(symbol)}\b", text)
        line = text[: symbol_match.start()].count("\n") + 1 if symbol_match else 1
        symbol_text = text[max(0, text.find(symbol) - 120): text.find(symbol) + 400]
        is_menu = bool(re.search(r"(?:rowActions|actions|menu|confirm)", symbol, re.I))
        control_type = "script_menu_or_action" if is_menu else "script_state_or_data_binding"
        controls.append({
            "object_id": f"{page_id}.{Path(source_path).stem}.script.{symbol}",
            "page_id": page_id,
            "page_title": title,
            "spec_ids": [page_id, "F26" if "action" in symbol.lower() else "G31"],
            "source_component": source_path,
            "source_symbol": symbol,
            "source_line": line,
            "control_type": control_type,
            "label_key_or_text": "",
            "label_source": "script_symbol",
            "visibility": ["script_or_runtime"],
            "enabled": "script_qualification_not_in_template",
            "repeat": "",
            "action": [{"event": "script", "expression": symbol}],
            "outcome": "script_handler_or_data_binding",
            "recovery": ["error_handling_in_page_script"] if "catch" in symbol_text else [],
            "contract_sources": [
                "docs/03-规范与术语/06-后台逐控件规格.md",
                "frontend-platform/src/api/index.ts",
                source_path,
                *[f"frontend-platform/src/api/index.ts#{symbol_ref}" for symbol_ref in api_symbols[:12]],
            ],
            "binding_definition_ref": "page-script-symbol",
            "verification": {"status": "not_executed", "evidence": [], "test_ids": []},
            "gaps": ["runtime_visibility_or_permission_requires_contract_review"],
        })


def menu_action_entries(page_id: str, title: str, source_path: str, text: str,
                        api_symbols: list[str]) -> list[dict[str, Any]]:
    """只登记传给当前 AdminLayout 的真实菜单数组，不恢复已删除的页签菜单。"""
    template, _ = template_region(text)
    if not re.search(r'<AdminLayout\b[^>]*:menus=["\']menus["\']', template):
        return []
    declaration = re.search(r"\bconst\s+menus\s*=", text)
    if not declaration:
        return []
    array = first_nonempty_array_after(text, declaration.end())
    if not array:
        return []
    entries: list[dict[str, Any]] = []
    for index, (body, offset) in enumerate(object_literals(array[0], array[1] + 1)):
        # 页面菜单可以是 JSON 对象；匹配实际 title/path，而不要求旧 action.id。
        def value(name: str) -> str:
            match = re.search(rf"[\"']?{name}[\"']?\s*:\s*([\"'])(.*?)\1", body)
            return match.group(2) if match else ""
        path, label = value("path"), value("title")
        if not path:
            continue
        entries.append({
            "object_id": f"{page_id}.{Path(source_path).stem}.menu.{index:03d}",
            "page_id": page_id, "page_title": title, "spec_ids": [page_id, "B14"],
            "source_component": source_path, "source_symbol": f"menus[{index}]",
            "source_line": text[:offset].count("\n") + 1, "control_type": "menu_action",
            "label_key_or_text": label, "label_source": "literal", "visibility": ["AdminLayout.menus"],
            "enabled": "route_authorization", "repeat": "one configured navigation entry",
            "action": [{"event": "route", "expression": path}], "outcome": "route_navigation",
            "recovery": [], "contract_sources": [source_path, SHARED_COMPONENTS["AdminLayout"]],
            "binding_definition_ref": "admin-domain.AdminLayout.menu",
            "verification": {"status": "not_executed", "evidence": [], "test_ids": []},
            "gaps": ["runtime_route_authorization_not_verified"],
        })
    return entries


def dynamic_schema_entries(page_id: str, title: str, paths: list[str], texts: list[str]) -> list[dict[str, Any]]:
    """仅记录模板真实存在的循环入口，动态实例及字段约束不凭规格补齐。"""
    entries: list[dict[str, Any]] = []
    for source_path, text in zip(paths, texts):
        region, first_line = template_region(text)
        for index, node in enumerate(TemplateObjects(region).nodes):
            if node["tag"] == "#text":
                continue
            attributes = attrs(node.get("attrs", ""))
            expression = attributes.get("v-for")
            if not expression:
                continue
            entries.append({
                "object_id": f"{page_id}.{Path(source_path).stem}.dynamic.{index:03d}",
                "page_id": page_id, "page_title": title, "spec_ids": [page_id],
                "source_component": source_path, "source_symbol": node["tag"],
                "source_line": first_line + node["line"] - 1, "control_type": "dynamic_template_entry",
                "label_key_or_text": attributes.get(":label", attributes.get("label", "")),
                "label_source": "template_binding", "visibility": node["conditions"],
                "enabled": attributes.get(":disabled", "inherited_or_not_declared"),
                "repeat": expression, "action": action_list(node.get("attrs", "")),
                "outcome": "runtime_collection_controls", "recovery": [],
                "contract_sources": [source_path, "docs/02-架构与设计/04-接口与事件契约.md"],
                "binding_definition_ref": "vue.v-for",
                "verification": {"status": "not_executed", "evidence": [], "test_ids": []},
                "gaps": ["runtime_instances_and_permissions_not_verified"],
            })
    return entries


def parse_file(page_id: str, title: str, source_path: str) -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
    path = ROOT / source_path
    text = read(path)
    region, first_line = template_region(text)
    api_symbols = api_refs(text)
    controls: list[dict[str, Any]] = []
    ordinal = 0
    template_nodes = TemplateObjects(region).nodes
    element_nodes = {node["offset"]: node for node in template_nodes if node["tag"] != "#text"}
    for match in TAG_RE.finditer(region):
        tag, attrs_text = match.group(1), match.group(2) or ""
        tag = COMPONENT_TAG_ALIASES.get(tag, tag)
        if tag not in INTERACTIVE_TAGS:
            continue
        ordinal += 1
        line = first_line + region[: match.start()].count("\n")
        snippet = "" if match.group().endswith("/>") else region[match.end(): match.end() + 600]
        before = len(controls)
        add_control(
            controls,
            page_id=page_id,
            page_title=title,
            source_path=source_path,
            line=line,
            tag=tag,
            attrs_text=attrs_text,
            snippet=snippet,
            ordinal=ordinal,
            page_text=text,
            api_symbols=api_symbols,
        )
        # 精确使用源码偏移，避免同一行的多个同类字段混用条件。
        node = element_nodes.get(match.start())
        for control in controls[before:]:
            control["visibility"] = (node["conditions"] or ["always_in_parent_rendered_slot"]) if node else ["template_context_requires_review"]
    icons: set[str] = {"component", "svg"}
    for imports in re.findall(r'import\s*\{([^}]+)\}\s*from\s*["\']lucide-vue-next["\']', text):
        icons.update(item.strip().split(" as ")[-1] for item in imports.split(","))
    for index, node in enumerate(template_nodes):
        if node["tag"] != "#text" and node["tag"] not in icons:
            continue
        is_text = node["tag"] == "#text"
        content = node.get("text", node.get("attrs", ""))
        keys = I18N_RE.findall(content)
        controls.append({
            "object_id": f"{page_id}.{Path(source_path).stem}.{'text' if is_text else 'icon'}.{index:03d}",
            "page_id": page_id, "page_title": title, "spec_ids": [page_id, "A01" if is_text else "B02"],
            "spec_binding_status": "candidate_requires_review", "source_component": source_path,
            "source_symbol": node["tag"], "source_line": first_line + node["line"] - 1,
            "control_type": "display_text" if is_text else "display_icon",
            "label_key_or_text": content, "label_source": "i18n" if keys else "template_expression_or_literal",
            "i18n_keys": keys, "visibility": node["conditions"] or ["always_in_parent_rendered_slot"],
            "enabled": "display_only_or_parent_action", "repeat": "inherits_parent_loop", "action": [],
            "outcome": "display", "recovery": [],
            "contract_sources": [source_path, "docs/02-架构与设计/09-前端视觉与交互规范.md"],
            "binding_definition_ref": "template-display-node",
            "verification": {"status": "not_executed", "evidence": [], "test_ids": []},
            "gaps": ["content_states_visual_and_accessibility_require_review"],
        })
    script_symbols(controls, page_id, title, source_path, text, api_symbols, region)
    controls.extend(menu_action_entries(page_id, title, source_path, text, api_symbols))
    return controls, dynamic_schema_entries(page_id, title, [source_path], [text])


def shared_controls() -> list[dict[str, Any]]:
    controls: list[dict[str, Any]] = []
    for page_id, title, source_path in [
        ("shared-shell", "平台后台应用壳", "frontend-platform/src/views/platform/Layout.vue"),
        ("shared-nav", "平台后台侧栏导航", "frontend-shared/src/admin/AdminLayout.vue"),
        ("shared-foundation-host", "平台基础页面宿主", "frontend-platform/src/views/FoundationPage.vue"),
        ("shared-foundation", "双宿主基础页面分区", "frontend-shared/src/foundation/FoundationPage.vue"),
    ]:
        parsed, dynamic = parse_file(page_id, title, source_path)
        controls.extend(parsed)
        controls.extend(dynamic)
    return controls


def build() -> dict[str, Any]:
    controls = shared_controls()
    source_files = set(SHARED_SOURCES)
    routes = route_records()
    page_file_count = 0
    for page_id, (title, paths) in PAGE_SOURCES.items():
        for path in paths:
            parsed, dynamic = parse_file(page_id, title, path)
            controls.extend(parsed)
            controls.extend(dynamic)
            source_files.add(path)
            page_file_count += 1
    # 新增路由仍登记实际控件；未绑定规格页号不能冒充公共壳层或被静默遗漏。
    unmapped_sources = sorted({route["component"] for route in routes if route["page_id"] == "unmapped-page"})
    for source_path in unmapped_sources:
        parsed, dynamic = parse_file("unmapped-page", Path(source_path).stem, source_path)
        controls.extend(parsed)
        controls.extend(dynamic)
        source_files.add(source_path)
    # 展开实际页面消费的共享组件依赖，登记其内置关闭、显隐、工具栏及菜单控件。
    shared_by_name = SHARED_COMPONENTS
    consumptions: list[dict[str, Any]] = []
    pending = sorted(source_files)
    while pending:
        consumer = pending.pop()
        consumer_text = read(ROOT / consumer)
        consumer_template, first_line = template_region(consumer_text)
        referenced = {match.group(1): first_line + consumer_template[:match.start()].count("\n")
                      for match in TAG_RE.finditer(consumer_template)}
        for component in sorted(referenced):
            source = shared_by_name.get(component)
            if source is None:
                continue
            declaration = next(match for match in TAG_RE.finditer(consumer_template) if match.group(1) == component)
            consumptions.append({"consumer": consumer, "component": component, "source": source, "source_line": referenced[component], "bindings": attrs(declaration.group(2) or "")})
            if source in source_files:
                continue
            source_files.add(source)
            pending.append(source)
            parsed, dynamic = parse_file("shared-component", component, source)
            controls.extend(parsed)
            controls.extend(dynamic)
    for control in controls:
        control["consumed_by"] = sorted({item["consumer"] for item in consumptions if item["source"] == control["source_component"]})
    controls.sort(key=lambda item: (item["page_id"], item["source_component"], item["source_line"], item["object_id"]))
    gaps = sorted({gap for control in controls for gap in control["gaps"]})
    return {
        "schema_version": "1.1",
        "architecture": "vue-router-pinia-element-plus-direct-admin-domain-components",
        "inventory_status": "source_inventory_not_verified",
        "generated_by": "tools/docs/generate_platform_ui_control_inventory.py",
        "scope": {
            "application": "frontend-platform",
            "pages": "P01-P32",
            "includes_shared_shell": True,
            "includes_unmapped_route_pages": True,
            "includes_referenced_shared_component_internals": True,
            "includes_template_text_and_icons": True,
            "control_count_is_not_the_305_spec_item_count": True,
            "verification_status_for_all_controls": "not_executed",
        },
        "contract_sources": [
            "docs/03-规范与术语/05-后台系统交互规范.md",
            "docs/03-规范与术语/06-后台逐控件规格.md",
            "docs/02-架构与设计/09-前端视觉与交互规范.md",
            "docs/02-架构与设计/08-前端共享层设计.md",
            "docs/02-架构与设计/13-前端组件库.md",
            "docs/02-架构与设计/04-接口与事件契约.md",
        ],
        "routes": routes,
        "source_files": sorted(source_files),
        "component_consumptions": sorted(consumptions, key=lambda item: (item["consumer"], item["component"])),
        "shared_component_bindings": {
            key: {
                "binding_definition_ref": key,
                "source": "element-plus" if key.startswith("element-plus.") else SHARED_COMPONENTS[key.split(".")[-1]],
                "contract_sources": [
                    "docs/02-架构与设计/08-前端共享层设计.md",
                    "docs/02-架构与设计/13-前端组件库.md",
                ],
            }
            for key in sorted({item["binding_definition_ref"] for item in controls} & set(TAG_BINDINGS.values()))
        },
        "controls": controls,
        "summary": {
            "page_count": len(PAGE_SOURCES),
            "page_source_file_count": page_file_count,
            "route_count": len(routes),
            "unmapped_route_source_file_count": len(unmapped_sources),
            "control_count": len(controls),
            "shared_control_count": sum(1 for item in controls if item["page_id"].startswith("shared-")),
            "dynamic_template_entry_count": sum(1 for item in controls if item["control_type"] == "dynamic_template_entry"),
            "table_column_count": sum(1 for item in controls if item["control_type"] == "table_column"),
            "menu_action_count": sum(1 for item in controls if item["control_type"] == "menu_action"),
            "script_binding_count": sum(1 for item in controls if item["control_type"].startswith("script_")),
            "text_node_count": sum(1 for item in controls if item["control_type"] == "display_text"),
            "icon_node_count": sum(1 for item in controls if item["control_type"] == "display_icon"),
            "controls_with_gaps": sum(1 for item in controls if item["gaps"]),
            "gap_categories": gaps,
            "verification_status_counts": {"not_executed": len(controls)},
        },
        "limitations": [
            "模板静态解析不能证明运行时v-for实例总量、权限服务端结果或动态schema的完整字段集合。",
            "字段范围、枚举、权限代码、后端副作用和浏览器行为未由本清单推断；缺失绑定保留在gaps。",
            "真实控件清单不等于P01-P20的305条规格项，也不等于DOM运行时节点总量。",
            "文字节点与图标按源码声明登记；插槽、条件、循环及外部无障碍原语仍须在真实浏览器中逐实例核对。",
            "当前清单不复用已退役 Vben 页签、缓存、右键菜单或旧页面实现的验收证据。",
            "unmapped-page 仅表示实际路由尚未绑定规格页号，登记源码不等于补造控件合同或验收通过。",
        ],
    }


def refresh_pages(current: dict[str, Any], fresh: dict[str, Any], pages: set[str]) -> dict[str, Any]:
    """只更新指定页及其私有组件，保留其他页上次盘点的源码记录。"""
    def owned_sources(data: dict[str, Any]) -> tuple[set[str], set[str]]:
        roots = {item["source_component"] for item in data["controls"] if item["page_id"] in pages}
        owned = set(roots)
        consumptions = data["component_consumptions"]
        while True:
            additions = {item["source"] for item in consumptions if item["consumer"] in owned
                         and all(other["consumer"] in owned for other in consumptions
                                 if other["source"] == item["source"])} - owned
            if not additions:
                return roots, owned
            owned.update(additions)

    old_roots, old_sources = owned_sources(current)
    new_roots, new_sources = owned_sources(fresh)
    controls = [item for item in current["controls"]
                if item["page_id"] not in pages and item["source_component"] not in old_sources]
    controls.extend(item for item in fresh["controls"]
                    if item["page_id"] in pages or item["source_component"] in new_sources)
    consumptions = [item for item in current["component_consumptions"] if item["consumer"] not in old_sources]
    consumptions.extend(item for item in fresh["component_consumptions"] if item["consumer"] in new_sources)
    controls = [{**item, "consumed_by": sorted({entry["consumer"] for entry in consumptions
                                             if entry["source"] == item["source_component"]})}
                for item in controls]
    controls.sort(key=lambda item: (item["page_id"], item["source_component"], item["source_line"], item["object_id"]))
    routes = [item for item in current["routes"] if item["page_id"] not in pages]
    routes.extend(item for item in fresh["routes"] if item["page_id"] in pages)
    # 保持原路由顺序，避免把其他页面的记录移动到差量中。
    route_order = {(item["path"], item["component"]): index for index, item in enumerate(current["routes"])}
    routes.sort(key=lambda item: route_order.get((item["path"], item["component"]), len(route_order)))
    bindings = {**fresh["shared_component_bindings"], **current["shared_component_bindings"]}
    used_bindings = {item["binding_definition_ref"] for item in controls}
    summary = {**current["summary"],
               "page_source_file_count": current["summary"]["page_source_file_count"] + len(new_roots) - len(old_roots),
               "route_count": len(routes), "control_count": len(controls),
               "shared_control_count": sum(item["page_id"].startswith("shared-") for item in controls),
               "controls_with_gaps": sum(bool(item["gaps"]) for item in controls),
               "gap_categories": sorted({gap for item in controls for gap in item["gaps"]}),
               "verification_status_counts": {"not_executed": len(controls)}}
    for key, control_type in [("dynamic_template_entry_count", "dynamic_template_entry"),
                              ("table_column_count", "table_column"), ("menu_action_count", "menu_action"),
                              ("text_node_count", "display_text"), ("icon_node_count", "display_icon")]:
        summary[key] = sum(item["control_type"] == control_type for item in controls)
    summary["script_binding_count"] = sum(item["control_type"].startswith("script_") for item in controls)
    return {**current, "controls": controls, "routes": routes,
            "source_files": sorted((set(current["source_files"]) - old_sources) | new_sources),
            "component_consumptions": sorted(consumptions, key=lambda item: (item["consumer"], item["component"])),
            "shared_component_bindings": {key: value for key, value in sorted(bindings.items()) if key in used_bindings},
            "summary": summary}


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true", help="compare generated content with the checked-in JSON")
    parser.add_argument("--output", type=Path, default=OUTPUT)
    parser.add_argument("--page", action="append", choices=sorted(PAGE_SOURCES), help="refresh only this page and its private components; repeat for multiple pages")
    args = parser.parse_args()
    target = args.output if args.output.is_absolute() else ROOT / args.output
    data = build()
    if args.page:
        if not target.exists():
            parser.error("--page requires an existing inventory")
        data = refresh_pages(json.loads(target.read_text(encoding="utf-8")), data, set(args.page))
    generated = json.dumps(data, ensure_ascii=False, indent=2) + "\n"
    scope = f" pages={','.join(args.page)}" if args.page else ""
    if args.check:
        if not target.exists():
            print(f"missing inventory: {target}", file=sys.stderr)
            return 1
        current = target.read_text(encoding="utf-8")
        if current != generated:
            print(f"inventory is stale: {target}", file=sys.stderr)
            return 1
        print(f"inventory OK{scope}: {target}")
        return 0
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(generated, encoding="utf-8")
    data = json.loads(generated)
    print(f"wrote {target}: controls={data['summary']['control_count']} gaps={data['summary']['controls_with_gaps']}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
