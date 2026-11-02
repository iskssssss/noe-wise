"""搜索研究 Skill 的 HTTP 失败、来源去重、引用归属与正式交付回归。"""
import contextlib
import copy
import importlib.util
import io
import json
import hashlib
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
PACKAGE = ROOT / "official-skills/research"


class ResearchSkillTest(unittest.TestCase):
    def setUp(self):
        spec = importlib.util.spec_from_file_location("research_skill", PACKAGE / "scripts/research.py")
        self.skill = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.skill)
        self.temp = tempfile.TemporaryDirectory(prefix="research-skill-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.skill.INPUT_DATA = self.root / "input/data"
        self.skill.INPUT_ARTIFACTS = self.root / "input/artifacts"
        self.skill.OUTPUT = self.root / "workspace/output"
        self.skill.INPUT_DATA.mkdir(parents=True)
        self.skill.INPUT_ARTIFACTS.mkdir(parents=True)

    def response(self, operation="search", results=None, failed_results=None, **override):
        payload = {"results": results if results is not None else [{"url": "https://example.com/page", "title": "公开资料", "content": "公开页面中的实际内容"}]}
        if failed_results is not None:
            payload["failed_results"] = failed_results
        wrapper = {"success": True, "status": 200, "content_type": "application/json", "body": json.dumps(payload), "truncated": False}
        wrapper.update(override)
        return {"operation": operation, "retrieved_at": "2026-10-08T10:00:00+08:00", "http_response": wrapper}

    def mount_corpus(self, responses):
        public = self.skill.save_corpus(self.skill.normalize({"responses": responses}))
        artifact = self.skill.INPUT_ARTIFACTS / "corpus-candidate"
        artifact.mkdir(exist_ok=True)
        file = artifact / "corpus.json"
        file.write_bytes((self.skill.OUTPUT / "corpus.json").read_bytes())
        return {"corpus_file": str(file), "corpus_sha256": public["corpus_sha256"]}

    def report_data(self, responses=None):
        return {"mode": "report", "title": "资料研究", "scope": "限定公开资料", **self.mount_corpus([self.response()] if responses is None else responses),
                "sections": [{"heading": "结论", "claims": [{"status": "fact", "text": "引用公开页面内容", "citations": ["S1"]},
                                                         {"status": "analysis", "text": "基于资料提出的建议", "citations": ["S1"]},
                                                         {"status": "uncertain", "text": "还需核实的内容", "citations": []}]}]}

    def run_skill(self, data):
        (self.skill.INPUT_DATA / "research.json").write_text(json.dumps(data), encoding="utf-8")
        stdout = io.StringIO()
        with contextlib.redirect_stdout(stdout):
            self.skill.main()
        return json.loads(stdout.getvalue())

    def test_free_request_has_real_https_contract_without_credential(self):
        data = json.loads((PACKAGE / "examples/search.json").read_text(encoding="utf-8"))
        self.run_skill(data)
        public = json.loads((self.skill.OUTPUT / "result.json").read_text(encoding="utf-8"))
        result = json.loads(public["results"][0]["text"])
        request = result["request"]
        self.assertEqual("https://api.tavily.com/search", request["url"])
        self.assertEqual("POST", request["method"])
        self.assertEqual("keyless", request["headers"]["X-Tavily-Access-Mode"])
        self.assertNotIn("Authorization", request["headers"])
        self.assertNotIn("api_key", request["body"])
        self.assertFalse(request["body"]["include_answer"])
        self.assertFalse((self.skill.OUTPUT / "research.md").exists())

    def test_extract_rejects_private_links_and_unsupported_parameters(self):
        for url in ["http://example.com", "https://localhost/", "https://127.0.0.1/", "https://169.254.169.254/", "https://[::1]/", "https://user:secret@example.com/", "https://internal/", "https://host.internal/", "https://example.com:8443/", "https://example.com/<attack>"]:
            with self.subTest(url=url), self.assertRaises(ValueError):
                self.skill.prepare_request({"mode": "prepare_extract", "urls": [url]})
        for options in [{"api_key": "do-not-send"}, {"chunks_per_source": 2}, {"timeout": 120}, {"extract_depth": []}]:
            with self.subTest(options=options), self.assertRaises(ValueError):
                self.skill.prepare_request({"mode": "prepare_extract", "urls": ["https://example.com/"], "options": options})

    def test_http_transport_success_does_not_accept_error_status_or_truncated_body(self):
        for override in [{"status": 401}, {"status": 429}, {"status": 500}, {"truncated": True}, {"success": False}, {"status": True}, {"body": "<html>not JSON</html>"}, {"body": '{"error":{"code":"keyless_cap_reached"}}'}]:
            with self.subTest(override=override):
                result = self.skill.normalize({"responses": [self.response(**override)]})
                self.assertEqual("failed", result["status"])
                self.assertEqual([], result["sources"])
                self.assertTrue(result["failures"])

    def test_deduplicate_fragment_keep_query_and_record_extraction_evidence(self):
        first = self.response(results=[{"url": "https://example.com/page#first", "content": "摘要"}, {"url": "https://example.com/page?v=2", "content": "另一版本"}])
        second = self.response("extract", [{"url": "https://EXAMPLE.com/page#second", "raw_content": "完整正文"}], [{"url": "https://example.com/missing", "error": "failed"}])
        result = self.skill.normalize({"responses": [first, second]})
        self.assertEqual("partial", result["status"])
        self.assertEqual(2, len(result["sources"]))
        self.assertEqual("S1", result["sources"][0]["source_id"])
        self.assertEqual(["search_snippet", "page_content"], [item["level"] for item in result["sources"][0]["evidence"]])
        self.assertEqual("https://example.com/missing", result["failures"][0]["url"])

    def test_report_rejects_invented_reference_and_uncited_fact(self):
        for citations in [["S999"], [], [None]]:
            data = self.report_data()
            data["sections"][0]["claims"][0]["citations"] = citations
            with self.subTest(citations=citations), self.assertRaises(ValueError):
                self.run_skill(data)
            self.assertFalse((self.skill.OUTPUT / "research.md").exists())

    def test_report_labels_partial_failures_inference_and_uncertainty(self):
        data = self.report_data([self.response(), self.response("extract", [], [{"url": "https://example.com/missing"}])])
        result = self.run_skill(data)
        self.assertEqual("partial", result["status"])
        self.assertEqual(["research.md"], result["files"])
        markdown = (self.skill.OUTPUT / "research.md").read_text(encoding="utf-8")
        for expected in ["**事实：**", "**分析：**", "**待确认：**", "部分完成", "来源缺口", "[S1](<https://example.com/page>)", "搜索摘要", "2026-10-08T10:00:00+08:00"]:
            self.assertIn(expected, markdown)
        self.assertFalse((self.skill.OUTPUT / "result.json").exists())

    def test_missing_page_content_does_not_create_a_source(self):
        result = self.skill.normalize({"responses": [self.response("extract", [{"url": "https://example.com/page", "raw_content": ""}])]})
        self.assertEqual("failed", result["status"])
        self.assertFalse(result["sources"])
        with self.assertRaises(ValueError):
            self.skill.report(self.report_data([self.response("extract", [])]))

    def test_explicit_content_limit_is_preserved_in_public_result_and_report(self):
        responses = [self.response("extract", [{"url": "https://example.com/page", "raw_content": "文" * (self.skill.MAX_CONTENT_CHARS + 1)}])]
        data = self.report_data(responses)
        normalized = self.skill.normalize({"responses": responses})
        evidence = normalized["sources"][0]["evidence"][0]
        self.assertTrue(evidence["content_truncated"])
        self.assertEqual(self.skill.MAX_CONTENT_CHARS, len(evidence["content"]))
        markdown, _ = self.skill.report(data)
        self.assertIn("正文在整理时截取", markdown)

    def test_requires_unique_explicit_input_and_aware_observation_time(self):
        with self.assertRaises(FileNotFoundError):
            self.skill.main()
        data = {"responses": [self.response()]}
        data["responses"][0]["retrieved_at"] = "2026-10-08T10:00:00"
        with self.assertRaises(ValueError):
            self.skill.normalize(data)
        (self.skill.INPUT_DATA / "research.json").write_text("{}", encoding="utf-8")
        artifact = self.skill.INPUT_ARTIFACTS / "previous"
        artifact.mkdir()
        (artifact / "research.json").write_text("{}", encoding="utf-8")
        with self.assertRaises(FileNotFoundError):
            self.skill.locate_input()

    def test_saved_receipts_preserve_raw_escaping_and_program_digest_without_model_copy(self):
        response = self.response("extract", [{"url": "https://example.com/", "raw_content": '原始正文\n含"引号"和\\路径：中文'}])
        response["body_sha256"] = hashlib.sha256(response["http_response"]["body"].encode()).hexdigest()
        file = self.skill.INPUT_DATA / "responses.json"
        file.write_text(json.dumps({"responses": [response]}, ensure_ascii=False), encoding="utf-8")
        self.run_skill({"mode": "normalize", "responses_file": str(file)})
        corpus = json.loads((self.skill.OUTPUT / "corpus.json").read_text(encoding="utf-8"))
        self.assertEqual(response["body_sha256"], corpus["receipts"][0]["body_sha256"])
        self.assertEqual('原始正文\n含"引号"和\\路径：中文', corpus["sources"][0]["evidence"][0]["content"])
        response["http_response"]["body"] += " "
        with self.assertRaisesRegex(ValueError, "body_sha256"):
            self.skill.normalize({"responses": [response]})

    def test_report_needs_same_mounted_corpus_and_rejects_repeated_receipts(self):
        data = self.report_data()
        self.run_skill(data)
        self.assertIn("[S1](<https://example.com/page>)", (self.skill.OUTPUT / "research.md").read_text(encoding="utf-8"))
        with self.assertRaisesRegex(ValueError, "不重复 HTTP"):
            self.skill.report({**data, "responses": [self.response()]})
        corpus_file = Path(data["corpus_file"])
        corpus_file.write_bytes(corpus_file.read_bytes() + b" ")
        with self.assertRaisesRegex(ValueError, "摘要不一致"):
            self.skill.report(data)

    def test_corpus_forged_ids_body_and_receipt_link_are_rejected_even_with_new_file_digest(self):
        data = self.report_data()
        file = Path(data["corpus_file"])
        original = json.loads(file.read_text(encoding="utf-8"))
        mutations = [lambda c: c["sources"][0].update(source_id="S999"),
                     lambda c: c["sources"][0]["evidence"][0].update(content="替换正文"),
                     lambda c: c["receipts"][0].update(status=429),
                     lambda c: c["sources"][0]["evidence"][0].update(retrieved_at="2026-10-09T10:00:00Z")]
        for mutation in mutations:
            corpus = copy.deepcopy(original)
            mutation(corpus)
            raw = json.dumps(corpus).encode()
            file.write_bytes(raw)
            with self.subTest(mutation=mutation), self.assertRaises(ValueError):
                self.skill.report({**data, "corpus_sha256": hashlib.sha256(raw).hexdigest()})

    def test_corpus_explicit_root_path_digest_and_paged_content(self):
        content = "官方中文资料\n" * 2000
        data = self.report_data([self.response("extract", [{"url": "https://example.com/", "raw_content": content}])])
        first = self.skill.read_corpus({**data, "source_id": "S1", "max_characters": 12000})
        second = self.skill.read_corpus({**data, "source_id": "S1", "text_offset": first["next_text_offset"]})
        self.assertEqual(content, first["content"] + second["content"])
        self.assertTrue(first["has_more_content"])
        self.assertFalse(second["has_more_content"])
        for file in ["corpus.json", str(self.root / "corpus.json"), str(self.skill.INPUT_DATA / "../outside.json")]:
            with self.subTest(file=file), self.assertRaises(ValueError):
                self.skill.load_corpus({**data, "corpus_file": file})
        with self.assertRaises(ValueError):
            self.skill.load_corpus({**data, "corpus_sha256": ""})

    def test_collector_advanced_requires_saved_definite_failure_and_preserves_receipt(self):
        node = shutil.which("node")
        if not node:
            self.skipTest("需要 Node 执行离线采集回归")
        url = "https://example.com/failed"
        source = (PACKAGE / "references/collect-responses.mjs").read_text(encoding="utf-8")
        source = source.replace("'/input/data'", json.dumps(self.skill.INPUT_DATA.as_posix())).replace("'/input/artifacts'", json.dumps(self.skill.INPUT_ARTIFACTS.as_posix())).replace("'/workspace/output'", json.dumps(self.skill.OUTPUT.as_posix()))
        fixture = '''
globalThis.tools = {call: async (_, request) => {
  if (request.body.extract_depth !== 'advanced') throw Error('wrong extraction depth');
  return {structuredContent:{success:true,status:200,truncated:false,body:'{"results":[],"failed_results":[]}'}};
}};
'''
        script = self.root / "advanced.mjs"
        script.write_text(fixture + source, encoding="utf-8")
        previous_file = self.skill.INPUT_ARTIFACTS / "previous.json"
        for label, payload, overrides, accepted in [
            ("failure", {"results": [], "failed_results": [{"url": url, "error": "fetch failed"}]}, {}, True),
            ("already_successful", {"results": [{"url": url, "raw_content": "ok"}], "failed_results": [{"url": url}]}, {}, False),
            ("no_failure", {"results": [], "failed_results": []}, {}, False),
            ("truncated", {"results": [], "failed_results": [{"url": url}]}, {"truncated": True}, False),
            ("http_failure", {"results": [], "failed_results": [{"url": url}]}, {"status": 500}, False),
        ]:
            with self.subTest(label=label):
                body = json.dumps(payload)
                response = {"success": True, "status": 200, "truncated": False, "body": body, **overrides}
                previous = {"operation": "extract", "retrieved_at": "2026-10-19T00:00:00Z", "http_response": response,
                            "body_sha256": hashlib.sha256(body.encode()).hexdigest()}
                previous_file.write_text(json.dumps({"responses": [previous]}), encoding="utf-8")
                plan = {"http_tool_handle": "tool:fixture-http", "urls": [url], "advanced_urls": [url],
                        "previous_responses_file": str(previous_file)}
                (self.skill.INPUT_DATA / "research-requests.json").write_text(json.dumps(plan), encoding="utf-8")
                result = subprocess.run([node, str(script)], capture_output=True, text=True, encoding="utf-8", timeout=10)
                self.assertEqual(accepted, result.returncode == 0, result.stderr)
                if not accepted:
                    self.assertIn("advanced extraction requires a previously recorded definite per-URL failure", result.stderr)
                if accepted:
                    saved = json.loads((self.skill.OUTPUT / "responses.json").read_text(encoding="utf-8"))
                    self.assertEqual(previous, saved["responses"][0])
                    self.assertEqual(2, len(saved["responses"]))

    def test_node_collector_splits_official_pages_and_routes_raw_bodies_through_managed_tools(self):
        node = shutil.which("node")
        if not node:
            self.skipTest("需要 Node 执行采集工具桥组合测试")
        plan = {"http_tool_handle": "tool:fixture-http", "queries": ["Docling official", "MarkItDown official"],
                "urls": ["https://github.com/docling-project/docling", "https://github.com/microsoft/markitdown"]}
        (self.skill.INPUT_DATA / "research-requests.json").write_text(json.dumps(plan), encoding="utf-8")
        source = (PACKAGE / "references/collect-responses.mjs").read_text(encoding="utf-8")
        source = source.replace("'/input/data'", json.dumps(str(self.skill.INPUT_DATA).replace("\\", "/"))).replace("'/input/artifacts'", json.dumps(str(self.skill.INPUT_ARTIFACTS).replace("\\", "/"))).replace("'/workspace/output'", json.dumps(str(self.skill.OUTPUT).replace("\\", "/")))
        fixture = '''
globalThis.tools = { call: async (handle, request) => {
  if (handle !== 'tool:fixture-http' || !['https://api.tavily.com/search', 'https://api.tavily.com/extract'].includes(request.url)) throw Error('unexpected tool call');
  if (request.headers['X-Tavily-Access-Mode'] !== 'keyless' || request.headers.Authorization || request.body.api_key) throw Error('credential or paid call');
  if (request.url.endsWith('/extract') && request.body.urls.length !== 1) throw Error('large official pages must be split');
  const payload = request.url.endsWith('/search') ? {results:[{url:'https://github.com/docling-project/docling',content:'官方项目搜索摘要'}]} :
    {results:[{url:request.body.urls[0],raw_content:'真实工具回执\\n含"引号"与中文\\\\路径。'+ 'document text '.repeat(1500)}],failed_results:[]};
  return {structuredContent:{success:true,status:200,truncated:false,body:JSON.stringify(payload)}};
}};
'''
        script = self.root / "collector.mjs"
        script.write_text(fixture + source, encoding="utf-8")
        result = subprocess.run([node, str(script)], capture_output=True, text=True, encoding="utf-8", timeout=30)
        self.assertEqual(0, result.returncode, result.stderr)
        saved = self.skill.OUTPUT / "responses.json"
        responses = json.loads(saved.read_text(encoding="utf-8"))["responses"]
        self.assertEqual(["search", "search", "extract", "extract"], [x["operation"] for x in responses])
        mounted = self.skill.INPUT_ARTIFACTS / "raw-candidate"
        mounted.mkdir()
        file = mounted / "responses.json"
        file.write_bytes(saved.read_bytes())
        normalized = self.skill.normalize({"responses_file": str(file)})
        self.assertEqual(2, len(normalized["sources"]))
        for source in normalized["sources"]:
            page = next(x for x in source["evidence"] if x["level"] == "page_content")
            self.assertGreater(len(page["content"]), 19000)
            self.assertFalse(page["content_truncated"])
        self.skill.save_corpus(normalized)
        data = self.report_data(responses)
        self.run_skill(data)
        self.assertTrue((self.skill.OUTPUT / "research.md").is_file())


if __name__ == "__main__":
    unittest.main()
