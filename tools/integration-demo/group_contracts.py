"""群控制台固定命令及唯一 wire 编解码；开放 canonical_json 正文仍由正式 Runtime 校验。"""
from __future__ import annotations

from dataclasses import MISSING, asdict, dataclass, fields, is_dataclass
from enum import StrEnum
from types import UnionType
from typing import get_args, get_origin, get_type_hints


class Operation(StrEnum):
    MEMBER = "member"
    AGENT = "agent"
    MEMORY_REVIEW = "memory-review"
    MEMORY_CORRECT = "memory-correct"
    MEMORY_FORGET = "memory-forget"
    MEMORY_RESTORE = "memory-restore"
    EXTRACTION_POLICY = "extraction-policy"
    CONTROL = "control"
    CONTROL_TERMINALIZE = "control-terminalize"
    CONTROL_RECONCILE = "control-reconcile"
    CONTROL_RELEASE = "control-release"
    RUN_COMMAND = "run-command"
    RUN_RESPOND = "run-respond"
    SENDER_CREATE = "sender-intent/create"
    SENDER_RECONCILE = "sender-intent/reconcile"
    SENDER_RELEASE = "sender-intent/release"
    SENDER_DELIVERY_RECONCILE = "sender-delivery-reconcile"


@dataclass(frozen=True)
class Member:
    end_user_id: str
    role: str
    status: str
    expected_revision: int
    source_event_id: str
    source_revision: int


@dataclass(frozen=True)
class Agent:
    agent_id: str
    status: str
    expected_revision: int
    source_event_id: str
    source_revision: int


@dataclass(frozen=True)
class MemoryRevision:
    fact_id: str
    expected_revision: int


@dataclass(frozen=True)
class MemoryReview(MemoryRevision):
    approved: bool


@dataclass(frozen=True)
class MemoryCorrect(MemoryRevision):
    object_json: str
    subject: str | None = None
    predicate: str | None = None
    confidence: float = 0.0
    importance: float = 0.0


@dataclass(frozen=True)
class ExtractionPolicy:
    enabled: bool
    expected_revision: int


@dataclass(frozen=True)
class Control:
    command_id: str
    expected_revision: int
    desired_state: str
    scopes: tuple[str, ...]
    run_id: str | None = None


@dataclass(frozen=True)
class Terminalize:
    command_id: str
    fingerprint: str
    local_intent_id: str
    expected_revision: int
    run_id: str | None = None


@dataclass(frozen=True)
class Reconcile:
    command_id: str


@dataclass(frozen=True)
class ReleaseMessage:
    message_id: str
    message_version: int
    delivery_id: str


@dataclass(frozen=True)
class Release:
    run_id: str
    expected_revision: int
    messages: tuple[ReleaseMessage, ...]
    blocked_boundary_refs: tuple[str, ...]


@dataclass(frozen=True)
class RunCommand:
    run_id: str
    input_id: str
    action: str
    text: str | None = None


@dataclass(frozen=True)
class Wait:
    action_id: str
    generation: str


@dataclass(frozen=True)
class Payload:
    schema_id: str
    canonical_json: str


@dataclass(frozen=True)
class ToolResponse:
    method: str
    result: Payload


@dataclass(frozen=True)
class ToolInput:
    wait: Wait
    responses: dict[str, ToolResponse]


@dataclass(frozen=True)
class HumanInput:
    wait: Wait
    text: str


@dataclass(frozen=True)
class WorkflowInput:
    wait: Wait
    execution_ref: str
    suspend_ref: str
    action_key: str
    execution_revision: str
    response: Payload


@dataclass(frozen=True)
class HitlResponse:
    execution_id: str
    run_revision: str
    tool_input: ToolInput | None = None
    human_input: HumanInput | None = None
    workflow_input: WorkflowInput | None = None


@dataclass(frozen=True)
class RunRespond:
    run_id: str
    input_id: str
    hitl_response: HitlResponse


@dataclass(frozen=True)
class SenderCreate:
    local_intent_id: str
    scopes: tuple[str, ...]
    expected_revision: int
    run_id: str | None = None


@dataclass(frozen=True)
class SenderReconcile:
    intent_id: str
    expected_revision: int
    action: str


@dataclass(frozen=True)
class SenderRelease:
    intent_id: str
    expected_revision: int
    resume_command_id: str


@dataclass(frozen=True)
class SenderDeliveryReconcile:
    delivery_id: str


COMMAND_TYPES = {
    Operation.MEMBER: Member, Operation.AGENT: Agent,
    Operation.MEMORY_REVIEW: MemoryReview, Operation.MEMORY_CORRECT: MemoryCorrect,
    Operation.MEMORY_FORGET: MemoryRevision, Operation.MEMORY_RESTORE: MemoryRevision,
    Operation.EXTRACTION_POLICY: ExtractionPolicy, Operation.CONTROL: Control,
    Operation.CONTROL_TERMINALIZE: Terminalize, Operation.CONTROL_RECONCILE: Reconcile,
    Operation.CONTROL_RELEASE: Release, Operation.RUN_COMMAND: RunCommand, Operation.RUN_RESPOND: RunRespond,
    Operation.SENDER_CREATE: SenderCreate, Operation.SENDER_RECONCILE: SenderReconcile,
    Operation.SENDER_RELEASE: SenderRelease, Operation.SENDER_DELIVERY_RECONCILE: SenderDeliveryReconcile,
}
CONTROL_SCOPES = frozenset(("execution", "business_write", "send", "memory_extraction"))
SERVER_FIELDS = frozenset(("group_binding_id", "operator_end_user_id", "reader_end_user_id", "responder_end_user_id",
                           "agent_id", "extractor_agent_id", "tenant_id", "integration_id", "principal_id"))


def _decode(kind, value):
    """只解码注册的 dataclass 字段，递归类型检查不赋予身份、权限或补造等待版本。"""
    if is_dataclass(kind):
        if not isinstance(value, dict):
            raise ValueError("GROUP_FIXED_COMMAND_REQUIRED")
        names = {item.name for item in fields(kind)}
        required = {item.name for item in fields(kind) if item.default is MISSING and item.default_factory is MISSING}
        if set(value) - names or not required <= set(value):
            raise ValueError("GROUP_COMMAND_FIELDS_INVALID")
        hints = get_type_hints(kind)
        return kind(**{name: _decode(hints[name], actual) for name, actual in value.items()})
    origin, arguments = get_origin(kind), get_args(kind)
    if origin is UnionType:
        if value is None and type(None) in arguments:
            return None
        return _decode(next(item for item in arguments if item is not type(None)), value)
    if origin is tuple:
        if not isinstance(value, list):
            raise ValueError("GROUP_COMMAND_ARRAY_REQUIRED")
        return tuple(_decode(arguments[0], item) for item in value)
    if origin is dict:
        if not isinstance(value, dict) or not value:
            raise ValueError("GROUP_DYNAMIC_RESPONSE_DICTIONARY_REQUIRED")
        return {_decode(arguments[0], key): _decode(arguments[1], item) for key, item in value.items()}
    if kind is float:
        if type(value) not in (int, float) or not 0 <= value <= 1:
            raise ValueError("GROUP_SCORE_INVALID")
        return float(value)
    if type(value) is not kind or kind is str and not value.strip():
        raise ValueError("GROUP_COMMAND_VALUE_INVALID")
    if kind is int and not 0 <= value <= 9223372036854775807:
        raise ValueError("GROUP_COMMAND_REVISION_INVALID")
    return value


def decode(operation: Operation, value):
    server_fields = SERVER_FIELDS - {"agent_id"} if operation is Operation.AGENT else SERVER_FIELDS
    if not isinstance(value, dict) or server_fields.intersection(value):
        raise ValueError("GROUP_SERVER_CONTEXT_IMMUTABLE")
    command = _decode(COMMAND_TYPES[operation], value)
    if isinstance(command, (Control, SenderCreate)):
        if not command.scopes or len(set(command.scopes)) != len(command.scopes) or not set(command.scopes) <= CONTROL_SCOPES:
            raise ValueError("GROUP_CONTROL_SCOPES_INVALID")
    if isinstance(command, Control) and command.desired_state not in {"running", "paused"}:
        raise ValueError("GROUP_CONTROL_STATE_INVALID")
    if isinstance(command, Member) and (command.role not in {"member", "manager"} or command.status not in {"active", "left"}):
        raise ValueError("GROUP_MEMBER_STATE_INVALID")
    if isinstance(command, Agent) and command.status not in {"active", "removed"}:
        raise ValueError("GROUP_AGENT_STATE_INVALID")
    if isinstance(command, RunCommand) and (command.action not in {"steer", "cancel_execution"}
            or command.action == "steer" and command.text is None or command.action == "cancel_execution" and command.text is not None):
        raise ValueError("GROUP_RUN_COMMAND_INVALID")
    if isinstance(command, SenderReconcile) and command.action not in {"query", "resubmit", "abandon"}:
        raise ValueError("GROUP_SENDER_RECONCILIATION_INVALID")
    if isinstance(command, RunRespond):
        reply = command.hitl_response
        if reply.execution_id != command.run_id or sum(item is not None for item in (reply.tool_input, reply.human_input, reply.workflow_input)) != 1:
            raise ValueError("GROUP_EXACT_RUN_REPLY_REQUIRED")
    return command


def wire(value):
    """字段名只来自固定类型声明；只有工具响应键和原公开 DTO 属于开放协议边界。"""
    if is_dataclass(value):
        return {item.name: wire(getattr(value, item.name)) for item in fields(value) if getattr(value, item.name) is not None}
    if isinstance(value, (tuple, list)):
        return [wire(item) for item in value]
    if isinstance(value, dict):
        return {key: wire(item) for key, item in value.items()}
    return value
