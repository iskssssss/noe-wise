"""以相同受管 HTTP 参数验证 Tavily 免费 search/extract；不读取凭据、不重试或写平台 API。"""
import argparse
from datetime import datetime, timezone
import importlib.util
import json
from pathlib import Path
import time
import urllib.error
import urllib.parse
import urllib.request

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "official-skills/research/scripts/research.py"


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, response, code, message, headers, newurl):
        return None


def call(request):
    started = time.monotonic()
    sent = urllib.request.Request(request["url"], method=request["method"], headers=request["headers"],
                                 data=json.dumps(request["body"]).encode("utf-8"))
    opener = urllib.request.build_opener(NoRedirect())
    try:
        response = opener.open(sent, timeout=40)
    except urllib.error.HTTPError as error:
        response = error
    except (urllib.error.URLError, TimeoutError):
        return {"success": False, "error": "发送后传输失败，结果未知，未重试", "truncated": False}, round(time.monotonic() - started, 3)
    with response:
        payload = response.read(4 * 1024 * 1024 + 1)
        truncated = len(payload) > 4 * 1024 * 1024
        wrapper = {"success": True, "status": response.code, "content_type": response.headers.get("Content-Type", ""),
                   "body": payload[:4 * 1024 * 1024].decode("utf-8", errors="replace"), "truncated": truncated}
    return wrapper, round(time.monotonic() - started, 3)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    spec = importlib.util.spec_from_file_location("research_skill", SCRIPT)
    skill = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(skill)
    args.output.mkdir(parents=True, exist_ok=True)
    prepared = [skill.prepare_request({"mode": "prepare_search", "query": "Docling MarkItDown official document parsing supported formats", "options": {"max_results": 3}}),
                skill.prepare_request({"mode": "prepare_extract", "urls": ["https://docling-project.github.io/docling/", "https://github.com/microsoft/markitdown"]})]
    records, calls = [], []
    for item in prepared:
        wrapper, seconds = call(item["request"])
        timestamp = datetime.now(timezone.utc).isoformat()
        records.append({"operation": item["operation"], "retrieved_at": timestamp, "http_response": wrapper})
        calls.append({"operation": item["operation"], "url": item["request"]["url"], "status": wrapper.get("status"), "duration_seconds": seconds, "truncated": wrapper["truncated"]})
        (args.output / "live-http-responses.json").write_text(json.dumps({"responses": records}, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    normalized = skill.normalize({"responses": records})
    (args.output / "normalized-sources.json").write_text(json.dumps(normalized, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    domains = sorted({urllib.parse.urlsplit(source["url"]).hostname for source in normalized["sources"]})
    pages = [source for source in normalized["sources"] if any(item["level"] == "page_content" for item in source["evidence"])]
    result = {"calls": calls, "status": normalized["status"], "source_count": len(normalized["sources"]), "page_count": len(pages),
              "source_domains": domains, "credential_mode": "keyless", "platform_http_tool_invoked": False,
              "boundary": "真实供应商 HTTPS 接口验收；平台 Agent/http_request 组合路径由集成验收另行确认"}
    (args.output / "live-summary.json").write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(result, ensure_ascii=False, indent=2))
    if len(pages) < 2 or len(domains) < 2 or any(item.get("status") != 200 for item in calls):
        raise SystemExit("Tavily 免费来源验收未完整通过，参见真实回执")


if __name__ == "__main__":
    main()
