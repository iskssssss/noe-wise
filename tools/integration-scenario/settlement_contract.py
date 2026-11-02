"""独立钱包与消费适配器的结算传输契约；金额为精确分，不含供应商成本计价。"""
from __future__ import annotations

import hashlib
import json
import logging
import re
from decimal import Decimal, InvalidOperation
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode, urlparse
from urllib.request import HTTPRedirectHandler, ProxyHandler, Request, build_opener

LOG = logging.getLogger(__name__)
MAX_BODY_BYTES = 1024 * 1024
POLICY = "bounded_overdraft"
INSTRUCTION_FIELDS = frozenset({"instruction_id", "object_id", "execution_tenant_id", "wallet_account_id",
    "currency", "metering_object_ref", "sequence", "previous_instruction_id", "target_amount", "pricing_revision"})
OPTIONAL_INSTRUCTION_FIELDS = frozenset({"reconciles_rejection_id"})


class SettlementError(ValueError):
    """稳定业务错误由HTTP边界映射，正文不携带秘密或底层异常。"""
    def __init__(self, code: str, status: int = 400):
        super().__init__(code)
        self.code, self.status = code, status


class WalletFailure(SettlementError):
    """钱包已返回明确HTTP错误，不能把它误记为已持久业务拒绝。"""
    def __init__(self, status: int, code: str):
        super().__init__(code, status)


class WalletUnknown(RuntimeError):
    """传输或响应无法确认；只能沿原指令身份查询和恢复。"""


def identity(value) -> str:
    """原始非空字符串身份有界保存，拒绝隐式数值转换与空白别名。"""
    if (not isinstance(value, str) or not value or value != value.strip() or len(value) > 256
            or any(ord(char) < 32 or ord(char) == 127 for char in value)):
        raise SettlementError("BOUNDED_STRING_IDENTITY_REQUIRED")
    return value


def currency(value) -> str:
    """币种显式声明，禁止把不同币种相加。"""
    if not isinstance(value, str) or not re.fullmatch(r"[A-Z]{3}", value):
        raise SettlementError("EXPLICIT_CURRENCY_REQUIRED")
    return value


def money(value, allow_negative: bool = False) -> Decimal:
    """精确分采用numeric(38,12)等价范围，超精度拒绝而非隐式舍入。"""
    if not isinstance(value, str) or not re.fullmatch(r"-?(?:0|[1-9][0-9]*)(?:\.[0-9]+)?", value) or len(value) > 64:
        raise SettlementError("DECIMAL_MONEY_STRING_REQUIRED")
    try:
        result = Decimal(value)
    except InvalidOperation as failure:
        raise SettlementError("DECIMAL_MONEY_STRING_REQUIRED") from failure
    if not result.is_finite() or (result < 0 and not allow_negative):
        raise SettlementError("NONNEGATIVE_FINITE_MONEY_REQUIRED")
    scale = max(0, -result.as_tuple().exponent)
    integral = max(1, result.adjusted() + 1) if result else 1
    if scale > 12 or integral > 26:
        raise SettlementError("MONEY_PRECISION_EXCEEDED")
    return result


def money_text(value) -> str:
    """只规范展示已有精确数值，不执行隐式金额舍入。"""
    raw = format(value if isinstance(value, Decimal) else Decimal(str(value)), "f")
    result = raw.rstrip("0").rstrip(".") if "." in raw else raw
    result = "0" if result in {"-0", ""} else result
    money(result, allow_negative=True)
    return result


def canonical(value) -> str:
    """规范JSON用于幂等内容指纹，浮点NaN不进入账本。"""
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"), allow_nan=False)


def fingerprint(value) -> str:
    return hashlib.sha256(canonical(value).encode("utf-8")).hexdigest()


def object_identity(tenant: str, account: str, unit_currency: str, metering: str) -> str:
    """价格版本不参与结算对象身份，跨读取视图保持同一对象。"""
    return "settlement:" + fingerprint([identity(tenant), identity(account), currency(unit_currency), identity(metering)])


def instruction_identity(object_id: str, sequence: int) -> str:
    if isinstance(sequence, bool) or not isinstance(sequence, int) or sequence < 1:
        raise SettlementError("POSITIVE_SEQUENCE_REQUIRED")
    return "instruction:" + fingerprint([identity(object_id), sequence])


def normalize_instruction(value: dict) -> dict:
    """同键异内容拒绝；首笔与前序闭包在进入钱包事务前校验。"""
    if not isinstance(value, dict) or not INSTRUCTION_FIELDS <= value.keys() or value.keys() - INSTRUCTION_FIELDS - OPTIONAL_INSTRUCTION_FIELDS:
        raise SettlementError("SETTLEMENT_INSTRUCTION_FIELDS_INVALID")
    result = dict(value)
    for field in INSTRUCTION_FIELDS - {"sequence", "previous_instruction_id", "target_amount", "currency"}:
        result[field] = identity(result[field])
    if not re.fullmatch(r"[1-9][0-9]{0,63}", result["execution_tenant_id"]):
        raise SettlementError("DECIMAL_TENANT_ID_REQUIRED")
    result["currency"] = currency(result["currency"])
    result["target_amount"] = money_text(money(result["target_amount"]))
    sequence = result["sequence"]
    if isinstance(sequence, bool) or not isinstance(sequence, int) or sequence < 1 or sequence > 9223372036854775807:
        raise SettlementError("POSITIVE_SEQUENCE_REQUIRED")
    if (sequence == 1) != (result["previous_instruction_id"] is None):
        raise SettlementError("PREVIOUS_INSTRUCTION_REQUIRED")
    if sequence > 1:
        result["previous_instruction_id"] = identity(result["previous_instruction_id"])
    if "reconciles_rejection_id" in result:
        result["reconciles_rejection_id"] = identity(result["reconciles_rejection_id"])
    return result


def decode_json(raw: bytes):
    """重复字段和非标准数值不可形成不同解析器理解的结算指令。"""
    def pairs(values):
        result = {}
        for key, value in values:
            if key in result:
                raise SettlementError("DUPLICATE_JSON_FIELD")
            result[key] = value
        return result
    try:
        return json.loads(raw.decode("utf-8"), object_pairs_hook=pairs,
                          parse_constant=lambda _: (_ for _ in ()).throw(SettlementError("NONSTANDARD_JSON_NUMBER")))
    except (UnicodeError, json.JSONDecodeError) as failure:
        raise SettlementError("VALID_JSON_REQUIRED") from failure


class _NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, request, file, code, message, headers, new_url):
        return None


class WalletClient:
    """只向明确loopback独立钱包发送其服务凭据，单次有界请求不自动重试。"""
    def __init__(self, origin: str, token: str, timeout_seconds: float = 10):
        parsed = urlparse(origin)
        if (parsed.scheme != "http" or parsed.hostname not in {"127.0.0.1", "localhost", "::1"}
                or not parsed.port or parsed.username or parsed.password or parsed.path not in {"", "/"}
                or parsed.query or parsed.fragment):
            raise SettlementError("EXPLICIT_LOOPBACK_WALLET_REQUIRED")
        if not isinstance(token, str) or not token or any(char in token for char in "\r\n"):
            raise SettlementError("WALLET_SERVICE_CREDENTIAL_REQUIRED")
        if isinstance(timeout_seconds, bool) or not isinstance(timeout_seconds, (int, float)) or not 0 < timeout_seconds <= 30:
            raise SettlementError("WALLET_TIMEOUT_INVALID")
        self.origin, self.token, self.timeout = origin.rstrip("/"), token, timeout_seconds
        self.opener = build_opener(ProxyHandler({}), _NoRedirect())

    def request(self, method: str, path: str, body=None, query=None, idempotency_key=None):
        if not path.startswith("/api/v1/") or "?" in path or "#" in path or method not in {"GET", "POST"}:
            raise SettlementError("WALLET_ENDPOINT_INVALID")
        target = path + ("?" + urlencode(query) if query else "")
        raw = None if body is None else canonical(body).encode("utf-8")
        if raw is not None and len(raw) > MAX_BODY_BYTES:
            raise SettlementError("WALLET_REQUEST_TOO_LARGE")
        headers = {"Authorization": "Bearer " + self.token, "Accept": "application/json"}
        if raw is not None:
            headers["Content-Type"] = "application/json"
        if idempotency_key is not None:
            headers["Idempotency-Key"] = identity(idempotency_key)
        LOG.debug("调用独立钱包进入 method=%s path=%s", method, path)
        try:
            with self.opener.open(Request(self.origin + target, data=raw, headers=headers, method=method), timeout=self.timeout) as response:
                data = response.read(MAX_BODY_BYTES + 1)
                if len(data) > MAX_BODY_BYTES:
                    raise WalletUnknown("WALLET_RESPONSE_TOO_LARGE")
                envelope = decode_json(data)
                if not isinstance(envelope, dict) or envelope.get("code") != "OK" or "data" not in envelope:
                    raise WalletUnknown("WALLET_RESPONSE_UNCONFIRMED")
                return envelope["data"]
        except HTTPError as failure:
            try:
                envelope = decode_json(failure.read(MAX_BODY_BYTES))
                code = envelope.get("code", "WALLET_HTTP_ERROR") if isinstance(envelope, dict) else "WALLET_HTTP_ERROR"
            except (SettlementError, OSError):
                code = "WALLET_HTTP_ERROR"
            raise WalletFailure(failure.code, code) from None
        except (URLError, TimeoutError, OSError, SettlementError) as failure:
            raise WalletUnknown("WALLET_RESPONSE_UNCONFIRMED") from failure
        finally:
            LOG.debug("调用独立钱包退出 method=%s path=%s", method, path)

    def settle(self, instruction: dict) -> dict:
        normalized = normalize_instruction(instruction)
        return self.request("POST", "/api/v1/settlements", normalized,
                            idempotency_key=normalized["instruction_id"])

    def lookup(self, instruction_id: str):
        try:
            return self.request("GET", "/api/v1/settlements/detail", query={"instruction_id": identity(instruction_id)})
        except WalletFailure as failure:
            if failure.status == 404 and failure.code in {"NOT_FOUND", "SETTLEMENT_NOT_FOUND", "WALLET_RECEIPT_NOT_FOUND"}:
                return None
            raise
