"""隔离HTTP夹具覆盖复杂文件下载、queued及未知受理不重放；不调用业务沙箱。"""
import hashlib
import io
import json
import os
from pathlib import Path
import sys
import tempfile
import threading
import unittest
from unittest.mock import patch
import urllib.error
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import zipfile
import verify_skill_sandbox as runner
from sandbox_acceptance_support import admission_failure, configuration

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'execution'))
from complex_fixture import fixture


class SkillSandboxRunnerTest(unittest.TestCase):
    """以真实loopback HTTP监听及72行原始文件验证控制脚本三条复杂序列。"""
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.files, self.oracle = fixture()
        self.posts = 0
        self.details = 0
        self.mode = 'normal'
        self.execution = None
        self.public_results = None
        test = self
        class Handler(BaseHTTPRequestHandler):
            """只保存请求次数；不记录凭据、请求正文或下载字节。"""
            def log_message(self, *args):
                pass
            def reply(self, status, data, mime='application/json'):
                content = json.dumps(data).encode() if mime == 'application/json' else data
                self.send_response(status)
                self.send_header('Content-Type', mime)
                self.send_header('Content-Length', str(len(content)))
                self.end_headers()
                self.wfile.write(content)
            def do_POST(self):
                test.posts += 1
                content = self.rfile.read(int(self.headers['Content-Length']))
                with zipfile.ZipFile(io.BytesIO(content)) as archive:
                    test.execution = json.loads(archive.read('request.json'))['execution']
                if test.mode == 'rejected':
                    self.reply(400, {'submission': 'NOT_SUBMITTED', 'code': 'INVALID_EXECUTION_POLICY', 'message': '已知准入拒绝'})
                elif test.mode == 'unproven':
                    self.reply(500, {'code': 'INTERNAL_ERROR'})
                else:
                    self.reply(202, {'state': 'queued'})
            def do_GET(self):
                if '/detail?' in self.path:
                    test.details += 1
                    state = 'unknown' if test.mode == 'unproven' else 'queued' if test.details == 1 else 'running' if test.details == 2 else 'completed'
                    self.reply(200, {'state': state, 'exitCode': 0, 'files': test.oracle['files'],
                                     'publicResults': test.public_results})
                elif '/files?' in self.path:
                    archive = io.BytesIO()
                    with zipfile.ZipFile(archive, 'w') as output:
                        for name, content in test.files.items():
                            output.writestr(name, content)
                    self.reply(200, archive.getvalue(), 'application/zip')
                else:
                    self.reply(404, {'code': 'UNEXPECTED_REQUEST'})
        self.server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.endpoint = f'http://127.0.0.1:{self.server.server_port}'
        self.config = self.root / 'private.json'
        self.config.write_text(json.dumps({'endpoint': self.endpoint, 'token': 'synthetic-test-service-token-0123456789'}))
        manifest = {'entry': {'language': 'python', 'script_path': 'scripts/main.py'}, 'outputs': {'json': list(self.files)},
                    'default_output_format': 'json', 'example_inputs': ['examples/input.json'], 'input_name': 'case.json'}
        self.package = {'manifest.json': json.dumps(manifest).encode(), 'examples/input.json': b'{}', 'scripts/main.py': b'pass'}

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(5)
        self.assertFalse(self.thread.is_alive())
        self.temporary.cleanup()

    def run_case(self):
        arguments = ['verify_skill_sandbox.py', '--skill', 'configuration', '--example', 'examples/input.json', '--output', str(self.root / 'case'),
                     '--service-config', str(self.config), '--batch-id', 'complex-20261012', '--wait', '5']
        with patch.object(sys, 'argv', arguments), patch.object(runner, 'package_files', return_value=(self.package, 'fixture:1')), patch.object(runner.time, 'sleep'):
            runner.main()

    def test_queue_run_complete_verifies_six_formats_then_reads_original_without_post(self):
        self.run_case()
        self.assertEqual(3, self.details)
        self.run_case()
        self.assertEqual(1, self.posts)
        receipt = json.loads((self.root / 'case/execution.json').read_text())
        self.assertTrue(receipt['files_verified'])
        self.assertNotIn('publicResultFile', self.execution)
        for fact in self.oracle['files']:
            observed = (self.root / 'case/files' / fact['path']).read_bytes()
            self.assertEqual(fact['sha256'], hashlib.sha256(observed).hexdigest())

    def test_fixed_typed_result_expectation_requires_real_response_without_a_selector(self):
        manifest = json.loads(self.package['manifest.json'])
        manifest['expects_typed_results'] = True
        self.package['manifest.json'] = json.dumps(manifest).encode()
        with self.assertRaisesRegex(ValueError, 'expected typed results'):
            self.run_case()
        self.assertNotIn('publicResultFile', self.execution)
        self.public_results = {'results': [{'kind': 'text', 'format': 'plain', 'text': '真实夹具结果'}]}
        self.run_case()
        self.assertEqual(1, self.posts, '补读同一回执不能重新提交原执行')
        receipt = json.loads((self.root / 'case/execution.json').read_text())
        self.assertTrue(receipt['typed_results_verified'])
        self.assertTrue(receipt['files_verified'])

    def test_known_rejection_remains_rejected_and_never_queries_or_reposts(self):
        self.mode = 'rejected'
        with self.assertRaisesRegex(RuntimeError, 'SUBMISSION_REJECTED'):
            self.run_case()
        with self.assertRaisesRegex(RuntimeError, 'ORIGINAL_ADMISSION_REJECTED'):
            self.run_case()
        self.assertEqual((1, 0), (self.posts, self.details))
        self.assertEqual('rejected', json.loads((self.root / 'case/execution.json').read_text())['submission'])

    def test_unproven_http_failure_is_unknown_and_reentry_only_queries_receipt(self):
        self.mode = 'unproven'
        with self.assertRaisesRegex(RuntimeError, 'SUBMISSION_UNKNOWN'):
            self.run_case()
        with self.assertRaisesRegex(RuntimeError, 'RESULT_UNKNOWN'):
            self.run_case()
        self.assertEqual((1, 1), (self.posts, self.details))
        self.assertFalse((self.root / 'case/files').exists())

    def test_connection_requires_explicit_batch_and_does_not_read_root_env(self):
        for endpoint in ('http://localhost:18200', 'https://127.0.0.1:18200', 'http://127.0.0.1:18200/path', 'http://127.0.0.1:18200?token=value'):
            with patch.dict(os.environ, {'SKILL_SANDBOX_ENDPOINT': endpoint, 'SKILL_SANDBOX_TOKEN': 'x' * 32}):
                with self.assertRaises(ValueError):
                    configuration(None, 'batch-20261012')
        with self.assertRaises(ValueError):
            configuration(self.config, '')


if __name__ == '__main__':
    unittest.main()
