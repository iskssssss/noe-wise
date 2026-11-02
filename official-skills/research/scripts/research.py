"""为受管 HTTP 生成免费请求参数，整理真实来源并校验 Markdown 引用；不直接联网。"""
from __future__ import annotations

from datetime import datetime
import hashlib
import ipaddress
import json
from pathlib import Path
import re
from urllib.parse import urlsplit, urlunsplit

INPUT_DATA = Path("/input/data")
INPUT_ARTIFACTS = Path("/input/artifacts")
OUTPUT = Path("/workspace/output")
MAX_INPUT_BYTES = 4 * 1024 * 1024
MAX_CONTENT_CHARS = 24000
CORPUS_SCHEMA = "skillforge.research-corpus.v1"


def sha256(value):
    return hashlib.sha256(value).hexdigest()


def mounted_json(value, label):
    path = Path(text(value, label, 4096))
    if not path.is_absolute() or ".." in path.parts or path.suffix != ".json":
        raise ValueError(f"{label} 必须指向明确挂载的 JSON 绝对路径")
    roots = [INPUT_DATA.absolute(), INPUT_ARTIFACTS.absolute()]
    if not any(path.is_relative_to(root) for root in roots):
        raise ValueError(f"{label} 超出输入挂载根")
    current = path
    while current != current.parent:
        if current.is_symlink():
            raise ValueError(f"{label} 不支持符号链接")
        current = current.parent
    resolved = path.resolve()
    if not any(resolved.is_relative_to(root.resolve()) for root in roots) or not resolved.is_file():
        raise ValueError(f"{label} 不是受管普通文件")
    if resolved.stat().st_size > MAX_INPUT_BYTES:
        raise ValueError(f"{label} 超过大小预算")
    return resolved.read_bytes()


def supplied_responses(data):
    if "responses_file" in data:
        if "responses" in data:
            raise ValueError("responses 与 responses_file 不能重复提供")
        saved = json.loads(mounted_json(data["responses_file"], "responses_file"))
        if not isinstance(saved, dict) or set(saved) != {"responses"}:
            raise ValueError("responses_file 必须只包含原始 responses 列表")
        return saved["responses"]
    return data.get("responses")


def text(value, label, maximum=24000):
    if not isinstance(value, str) or not value.strip() or len(value) > maximum:
        raise ValueError(f"{label} 必须为非空字符串且不超过 {maximum} 字符")
    if any(ord(char) < 32 and char not in "\n\r\t" for char in value):
        raise ValueError(f"{label} 含有非法控制字符")
    return value.strip()


def public_url(value):
    value = text(value, "URL", 4096)
    if any(char.isspace() for char in value) or any(char in value for char in '\\<>"'):
        raise ValueError("URL 不得含空白或反斜杠")
    parsed = urlsplit(value)
    host = (parsed.hostname or "").rstrip(".").lower()
    if parsed.scheme != "https" or not host or parsed.username is not None or parsed.password is not None:
        raise ValueError("URL 必须是无用户信息的公开 HTTPS 地址")
    if parsed.port not in (None, 443) or "." not in host or host.endswith((".local", ".internal", ".localhost")):
        raise ValueError("URL 必须使用公开域名与标准 HTTPS 端口")
    try:
        address = ipaddress.ip_address(host)
    except ValueError:
        address = None
    if address is not None:
        raise ValueError("URL 不支持 IP 字面量，请使用公开域名")
    if host in {"localhost", "metadata.google.internal"} or not re.fullmatch(r"[a-z0-9.-]+", host):
        raise ValueError("URL 必须使用公开 DNS 域名")
    # 片段不影响内容身份；保留查询参数，避免将不同页面错误合并。
    return urlunsplit(("https", host, parsed.path or "/", parsed.query, ""))


def integer(value, label, minimum, maximum):
    if isinstance(value, bool) or not isinstance(value, int) or not minimum <= value <= maximum:
        raise ValueError(f"{label} 必须是 {minimum}–{maximum} 的整数")
    return value


def enum(value, label, choices):
    if not isinstance(value, str) or value not in choices:
        raise ValueError(f"{label} 不在支持值中")
    return value


def domains(value, label):
    if not isinstance(value, list) or len(value) > 20:
        raise ValueError(f"{label} 必须为最多 20 个域名的列表")
    for item in value:
        item = text(item, label, 253)
        if "/" in item or ":" in item or public_url("https://" + item).split("/")[2] != item.lower():
            raise ValueError(f"{label} 只能包含公开域名")
    return value


def prepare_request(data):
    mode = data.get("mode")
    options = data.get("options", {})
    if not isinstance(options, dict):
        raise ValueError("options 必须是对象")
    if mode == "prepare_search":
        allowed = {"search_depth", "max_results", "topic", "time_range", "include_domains", "exclude_domains", "include_raw_content"}
        body = {"query": text(data.get("query"), "query", 400), "search_depth": "basic", "max_results": 5, "include_answer": False}
        endpoint = "search"
    elif mode == "prepare_extract":
        allowed = {"extract_depth", "format", "timeout", "query", "chunks_per_source"}
        urls = data.get("urls")
        if not isinstance(urls, list) or not 1 <= len(urls) <= 20:
            raise ValueError("urls 必须包含 1–20 个 URL")
        body = {"urls": list(dict.fromkeys(public_url(url) for url in urls)), "extract_depth": "basic", "format": "markdown", "timeout": 20}
        endpoint = "extract"
    else:
        raise ValueError("prepare 模式不支持")
    if set(options) - allowed:
        raise ValueError("options 存在不支持参数")
    choices = {"search_depth": {"basic", "advanced", "fast", "ultra-fast"}, "topic": {"general", "news", "finance"},
               "time_range": {"day", "week", "month", "year"}, "extract_depth": {"basic", "advanced"}, "format": {"markdown", "text"}}
    for key, value in options.items():
        if key in choices:
            body[key] = enum(value, key, choices[key])
        elif key in {"include_domains", "exclude_domains"}:
            body[key] = domains(value, key)
        elif key in {"max_results", "timeout", "chunks_per_source"}:
            body[key] = integer(value, key, 1, {"max_results": 20, "timeout": 30, "chunks_per_source": 5}[key])
        elif key == "query":
            body[key] = text(value, key, 400)
        elif key == "include_raw_content":
            if not isinstance(value, bool) and (not isinstance(value, str) or value not in {"markdown", "text"}):
                raise ValueError("include_raw_content 必须为布尔或 markdown/text")
            body[key] = value
    if "chunks_per_source" in body and "query" not in body:
        raise ValueError("chunks_per_source 必须同时提供 query")
    return {"operation": endpoint, "request": {"method": "POST", "url": "https://api.tavily.com/" + endpoint,
            "headers": {"Content-Type": "application/json", "X-Tavily-Access-Mode": "keyless", "X-Client-Source": "skillforge-keyless", "X-Client-Name": "SkillForge"}, "body": body}}


def observed_time(value):
    value = text(value, "retrieved_at", 80)
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError as invalid:
        raise ValueError("retrieved_at 必须是带时区 ISO 时间") from invalid
    if parsed.tzinfo is None:
        raise ValueError("retrieved_at 必须携带时区")
    return value


def normalize(data):
    responses = supplied_responses(data)
    if not isinstance(responses, list) or not 1 <= len(responses) <= 12:
        raise ValueError("responses 必须包含 1–12 个真实 HTTP 回执")
    sources, failures, receipts, by_url = [], [], [], {}
    for index, entry in enumerate(responses):
        if not isinstance(entry, dict):
            raise ValueError("responses 项必须是对象")
        operation = enum(entry.get("operation"), "operation", {"search", "extract"})
        timestamp = observed_time(entry.get("retrieved_at"))
        response = entry.get("http_response")
        if not isinstance(response, dict):
            raise ValueError("http_response 必须是工具原始回执对象")
        status = response.get("status")
        body = response.get("body")
        if not isinstance(body, str) or len(body.encode("utf-8")) > MAX_INPUT_BYTES:
            raise ValueError("HTTP body 必须是限制范围内的 JSON 字符串")
        body_digest = sha256(body.encode("utf-8"))
        if entry.get("body_sha256", body_digest) != body_digest:
            raise ValueError("HTTP body 与程序保存的 body_sha256 不一致")
        receipts.append({"response_index": index, "operation": operation, "retrieved_at": timestamp,
                         "success": response.get("success"), "status": status,
                         "truncated": response.get("truncated"), "body_sha256": body_digest})
        if response.get("success") is not True or isinstance(status, bool) or not isinstance(status, int) or not 200 <= status < 300 or response.get("truncated") is not False:
            failures.append({"response_index": index, "operation": operation, "status": status, "reason": "HTTP 失败或正文被截断；此回执不作为来源"})
            continue
        try:
            payload = json.loads(body)
        except json.JSONDecodeError:
            failures.append({"response_index": index, "operation": operation, "reason": "HTTP body 不是合法 JSON"})
            continue
        if not isinstance(payload, dict) or "error" in payload or not isinstance(payload.get("results"), list):
            failures.append({"response_index": index, "operation": operation, "reason": "供应商错误或缺失 results"})
            continue
        results = payload["results"]
        if len(results) > 20:
            raise ValueError("单次响应超过 20 个来源")
        if not results:
            failures.append({"response_index": index, "operation": operation, "reason": "未返回可读来源"})
        for result in results:
            try:
                if not isinstance(result, dict):
                    raise ValueError("来源必须是对象")
                url = public_url(result.get("url"))
                content = (result.get("raw_content") if operation == "extract" else result.get("raw_content") or result.get("content")) or ""
                if not isinstance(content, str) or not content.strip():
                    raise ValueError("来源没有可读正文或摘要")
                title = result.get("title") or url
                title = text(title, "title", 2000)
                evidence = {"operation": operation, "response_index": index, "retrieved_at": timestamp,
                            "level": "page_content" if result.get("raw_content") else "search_snippet",
                            "content": content[:MAX_CONTENT_CHARS], "content_truncated": len(content) > MAX_CONTENT_CHARS,
                            "content_sha256": sha256(content[:MAX_CONTENT_CHARS].encode("utf-8")),
                            "full_content_sha256": sha256(content.encode("utf-8")), "full_content_characters": len(content)}
                if url not in by_url:
                    source = {"source_id": "S" + str(len(sources) + 1), "url": url, "title": title, "evidence": []}
                    by_url[url] = source
                    sources.append(source)
                by_url[url]["evidence"].append(evidence)
            except ValueError as invalid:
                failures.append({"response_index": index, "operation": operation, "reason": str(invalid)})
        failed = payload.get("failed_results", [])
        if not isinstance(failed, list) or len(failed) > 20:
            raise ValueError("failed_results 格式错误")
        for result in failed:
            failure = {"response_index": index, "operation": operation, "reason": "供应商未提取该来源"}
            if isinstance(result, dict):
                try:
                    failure["url"] = public_url(result.get("url"))
                except ValueError:
                    pass
            failures.append(failure)
    return {"status": "partial" if failures and sources else "failed" if not sources else "complete",
            "sources": sources, "failures": failures, "receipts": receipts}


def save_corpus(normalized):
    corpus = {"schema": CORPUS_SCHEMA, **normalized}
    raw = (json.dumps(corpus, ensure_ascii=False, sort_keys=True, separators=(",", ":")) + "\n").encode("utf-8")
    if len(raw) > MAX_INPUT_BYTES:
        raise ValueError("corpus.json 超过大小预算，请缩小明确请求范围")
    OUTPUT.mkdir(parents=True, exist_ok=True)
    (OUTPUT / "corpus.json").write_bytes(raw)
    # 完整证据留在不可变候选；公开摘要按预算给每个来源稳定 ID，可显式回读其余正文。
    public = {"status": normalized["status"], "corpus_file": "corpus.json", "corpus_sha256": sha256(raw),
              "corpus_bytes": len(raw), "sources": [], "failures": normalized["failures"]}
    remaining = 12000
    for source in normalized["sources"]:
        item = {"source_id": source["source_id"], "url": source["url"], "title": source["title"], "evidence": []}
        for index, evidence in enumerate(source["evidence"]):
            count = min(1200, remaining, len(evidence["content"]))
            remaining -= count
            item["evidence"].append({"evidence_index": index, **{k: evidence[k] for k in
                ("operation", "retrieved_at", "level", "content_truncated", "full_content_characters")},
                "content": evidence["content"][:count], "has_more_content": count < len(evidence["content"]),
                "next_text_offset": count if count < len(evidence["content"]) else None})
        public["sources"].append(item)
    return public


def load_corpus(data):
    if "responses" in data or "responses_file" in data:
        raise ValueError("report/read 只复用 mounted corpus_file，不重复 HTTP 回执")
    expected = data.get("corpus_sha256")
    if not isinstance(expected, str) or not re.fullmatch(r"[0-9a-f]{64}", expected):
        raise ValueError("corpus_sha256 必须使用 normalize 公开回执的摘要")
    raw = mounted_json(data.get("corpus_file"), "corpus_file")
    if sha256(raw) != expected:
        raise ValueError("corpus_file 摘要不一致，不能替换来源或正文")
    corpus = json.loads(raw)
    if not isinstance(corpus, dict) or set(corpus) != {"schema", "status", "sources", "failures", "receipts"} or corpus["schema"] != CORPUS_SCHEMA:
        raise ValueError("corpus 结构或版本错误")
    receipts, sources, failures = corpus["receipts"], corpus["sources"], corpus["failures"]
    if not isinstance(receipts, list) or not 1 <= len(receipts) <= 12 or not isinstance(sources, list) or len(sources) > 240 or not isinstance(failures, list) or len(failures) > 480:
        raise ValueError("corpus 数量结构错误")
    for index, receipt in enumerate(receipts):
        if not isinstance(receipt, dict) or receipt.get("response_index") != index:
            raise ValueError("corpus 回执顺序错误")
        enum(receipt.get("operation"), "operation", {"search", "extract"})
        observed_time(receipt.get("retrieved_at"))
        if not isinstance(receipt.get("body_sha256"), str) or not re.fullmatch(r"[0-9a-f]{64}", receipt["body_sha256"]):
            raise ValueError("corpus 原始正文摘要错误")
    urls = set()
    for index, source in enumerate(sources):
        if not isinstance(source, dict) or source.get("source_id") != "S" + str(index + 1) or source.get("url") != public_url(source.get("url")) or source["url"] in urls:
            raise ValueError("corpus 来源 ID 或 URL 重复/错误")
        urls.add(source["url"])
        text(source.get("title"), "title", 2000)
        if not isinstance(source.get("evidence"), list) or not 1 <= len(source["evidence"]) <= 240:
            raise ValueError("corpus 来源缺少证据")
        for evidence in source["evidence"]:
            if not isinstance(evidence, dict):
                raise ValueError("corpus 正文结构错误")
            response_index = integer(evidence.get("response_index"), "response_index", 0, len(receipts) - 1)
            receipt = receipts[response_index]
            status = receipt.get("status")
            if receipt.get("success") is not True or isinstance(status, bool) or not isinstance(status, int) or not 200 <= status < 300 or receipt.get("truncated") is not False:
                raise ValueError("corpus 证据引用了失败或截断回执")
            if evidence.get("operation") != receipt["operation"] or evidence.get("retrieved_at") != receipt["retrieved_at"]:
                raise ValueError("corpus 正文与回执身份不一致")
            level = enum(evidence.get("level"), "level", {"page_content", "search_snippet"})
            if evidence["operation"] == "extract" and level != "page_content":
                raise ValueError("corpus extract 正文级别错误")
            content = evidence.get("content")
            if not isinstance(content, str) or not content.strip() or len(content) > MAX_CONTENT_CHARS or sha256(content.encode("utf-8")) != evidence.get("content_sha256"):
                raise ValueError("corpus 正文或摘要错误")
            length = integer(evidence.get("full_content_characters"), "full_content_characters", len(content), MAX_INPUT_BYTES)
            if evidence.get("content_truncated") is not (length > len(content)) or not isinstance(evidence.get("full_content_sha256"), str) or not re.fullmatch(r"[0-9a-f]{64}", evidence["full_content_sha256"]):
                raise ValueError("corpus 正文截取信息错误")
            if length == len(content) and evidence["full_content_sha256"] != evidence["content_sha256"]:
                raise ValueError("corpus 完整正文摘要不一致")
    for failure in failures:
        if not isinstance(failure, dict):
            raise ValueError("corpus 失败结构错误")
        position = integer(failure.get("response_index"), "response_index", 0, len(receipts) - 1)
        if failure.get("operation") != receipts[position]["operation"]:
            raise ValueError("corpus 失败回执身份错误")
        text(failure.get("reason"), "failure.reason", 2000)
        if "url" in failure:
            public_url(failure["url"])
    expected_status = "partial" if failures and sources else "failed" if not sources else "complete"
    if corpus["status"] != expected_status:
        raise ValueError("corpus 状态与证据不一致")
    return corpus


def read_corpus(data):
    corpus = load_corpus(data)
    source_id = text(data.get("source_id"), "source_id", 20)
    source = next((source for source in corpus["sources"] if source["source_id"] == source_id), None)
    if source is None:
        raise ValueError("source_id 不属于当前 corpus")
    index = integer(data.get("evidence_index", 0), "evidence_index", 0, len(source["evidence"]) - 1)
    evidence = source["evidence"][index]
    offset = integer(data.get("text_offset", 0), "text_offset", 0, len(evidence["content"]))
    maximum = integer(data.get("max_characters", 12000), "max_characters", 1, 12000)
    end = min(offset + maximum, len(evidence["content"]))
    return {"source_id": source_id, "url": source["url"], "evidence_index": index,
            **{k: evidence[k] for k in ("operation", "retrieved_at", "level", "content_truncated", "full_content_characters")},
            "text_offset": offset, "content": evidence["content"][offset:end], "has_more_content": end < len(evidence["content"]),
            "next_text_offset": end if end < len(evidence["content"]) else None}


def plain_markdown(value):
    # 模型输入作为文字段落，引用链接只由已核实来源清单创建。
    return value.replace("\\", "\\\\").replace("[", "\\[").replace("]", "\\]").replace("<", "&lt;").replace(">", "&gt;")


def report(data):
    normalized = load_corpus(data)
    if not normalized["sources"]:
        raise ValueError("没有可读来源，不能生成研究报告")
    title = text(data.get("title"), "title", 200)
    scope = text(data.get("scope"), "scope", 2000)
    sections = data.get("sections")
    if not isinstance(sections, list) or not 1 <= len(sections) <= 30:
        raise ValueError("sections 必须为 1–30 节")
    known = {source["source_id"]: source for source in normalized["sources"]}
    lines = ["# " + plain_markdown(title), "", plain_markdown(scope), "", "来源状态：" + ("部分完成，存在来源缺口。" if normalized["failures"] else "已整理所提供的可读来源。"), ""]
    for section in sections:
        if not isinstance(section, dict):
            raise ValueError("section 必须是对象")
        heading = text(section.get("heading"), "heading", 200)
        claims = section.get("claims")
        if not isinstance(claims, list) or not 1 <= len(claims) <= 100:
            raise ValueError("claims 必须为 1–100 条")
        lines.extend(["## " + plain_markdown(heading), ""])
        for claim in claims:
            if not isinstance(claim, dict):
                raise ValueError("claim 必须是对象")
            status = enum(claim.get("status"), "claim.status", {"fact", "analysis", "uncertain"})
            content = text(claim.get("text"), "claim.text", 12000)
            citations = claim.get("citations", [])
            if not isinstance(citations, list) or any(not isinstance(item, str) or item not in known for item in citations):
                raise ValueError("citations 必须引用真实来源 source_id")
            if status == "fact" and not citations:
                raise ValueError("事实段落必须至少引用一个来源")
            label = {"fact": "事实", "analysis": "分析", "uncertain": "待确认"}[status]
            refs = " ".join("[" + source_id + "](<" + known[source_id]["url"] + ">)" for source_id in dict.fromkeys(citations))
            lines.extend(["**" + label + "：** " + plain_markdown(content) + (" " + refs if refs else ""), ""])
    limitations = data.get("limitations", [])
    if not isinstance(limitations, list) or len(limitations) > 30:
        raise ValueError("limitations 必须为最多 30 项的列表")
    lines.extend(["## 来源与核实范围", "", "引用检查确认来源归属；结论是否获得正文支持仍由研究者逐条核实。", ""])
    for source in normalized["sources"]:
        levels = sorted({"已提取正文" if item["level"] == "page_content" else "搜索摘要" for item in source["evidence"]})
        dates = sorted({item["retrieved_at"] for item in source["evidence"]})
        suffix = "；正文在整理时截取" if any(item["content_truncated"] for item in source["evidence"]) else ""
        lines.append("- " + source["source_id"] + " [" + plain_markdown(source["title"].replace("\n", " ").replace("\r", " ")) + "](<" + source["url"] + ">)；" + "、".join(levels) + "；读取时间 " + "、".join(dates) + suffix)
    if limitations or normalized["failures"]:
        lines.extend(["", "## 限制与来源缺口", ""])
        lines.extend("- " + plain_markdown(text(item, "limitation", 2000)) for item in limitations)
        for failure in normalized["failures"]:
            lines.append("- 回执 " + str(failure["response_index"] + 1) + "（" + failure["operation"] + "）：" + failure["reason"] + (" " + failure["url"] if "url" in failure else ""))
    return "\n".join(lines) + "\n", normalized


def locate_input():
    candidates = [INPUT_DATA / "research.json"] if (INPUT_DATA / "research.json").is_file() else []
    if INPUT_ARTIFACTS.is_dir():
        candidates.extend(root / "research.json" for root in INPUT_ARTIFACTS.iterdir() if root.is_dir() and (root / "research.json").is_file())
    if len(candidates) != 1:
        raise FileNotFoundError("需要且只能提供一个明确挂载的 research.json")
    resolved = candidates[0].resolve()
    roots = [INPUT_DATA.resolve(), INPUT_ARTIFACTS.resolve()]
    if not any(resolved.is_relative_to(root) for root in roots) or resolved.stat().st_size > MAX_INPUT_BYTES:
        raise ValueError("research.json 超出输入根或大小预算")
    return resolved


def main():
    data = json.loads(locate_input().read_text(encoding="utf-8"))
    if not isinstance(data, dict):
        raise ValueError("research.json 必须是对象")
    mode = data.get("mode")
    if mode in {"prepare_search", "prepare_extract"}:
        result = prepare_request(data)
    elif mode == "normalize":
        result = save_corpus(normalize(data))
    elif mode == "read":
        result = read_corpus(data)
    elif mode == "report":
        markdown, result = report(data)
        OUTPUT.mkdir(parents=True, exist_ok=True)
        (OUTPUT / "research.md").write_text(markdown, encoding="utf-8")
        print(json.dumps({"mode": mode, "status": result["status"], "source_count": len(result["sources"]), "failure_count": len(result["failures"]), "files": ["research.md"]}))
        return
    else:
        raise ValueError("mode 必须为 prepare_search、prepare_extract、normalize、read 或 report")
    OUTPUT.mkdir(parents=True, exist_ok=True)
    public = {"results": [{"kind": "text", "format": "plain", "text": json.dumps(result, ensure_ascii=False)}]}
    (OUTPUT / "result.json").write_text(json.dumps(public, ensure_ascii=False), encoding="utf-8")
    print(json.dumps({"mode": mode}))


if __name__ == "__main__":
    main()
