"""网页完整输入、离线资源约束与明确交付检查。"""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
PACKAGE = ROOT / 'official-skills/web-app'
spec = importlib.util.spec_from_file_location('web_skill', PACKAGE / 'scripts/generate_web_app.py')
web = importlib.util.module_from_spec(spec)
spec.loader.exec_module(web)
HTML = json.loads((PACKAGE / 'examples/site.json').read_text(encoding='utf-8'))['html']


class WebSkillTest(unittest.TestCase):
    def setUp(self):
        """每例隔离生成器目录，避免输入残留改变唯一输入的判断。"""
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        for name, directory in [('INPUT_DATA', 'input/data'), ('INPUT_ARTIFACTS', 'input/artifacts'), ('OUTPUT', 'output')]:
            binding = patch.object(web, name, self.root / directory)
            binding.start()
            self.addCleanup(binding.stop)

    def test_preserves_authored_page_and_only_declared_default_file(self):
        web.INPUT_DATA.mkdir(parents=True)
        (web.INPUT_DATA / 'site.json').write_text(json.dumps({'html':HTML}), encoding='utf-8')
        web.main()
        self.assertEqual(HTML, (web.OUTPUT / 'index.html').read_text(encoding='utf-8'))
        self.assertEqual(['index.html'], [p.name for p in web.OUTPUT.iterdir()])

    def test_collected_artifact_input_generates_without_duplicate_input_file(self):
        artifact = web.INPUT_ARTIFACTS / 'managed-input'
        artifact.mkdir(parents=True)
        (artifact / 'site.json').write_text(json.dumps({'html':HTML}), encoding='utf-8')
        web.main()
        self.assertEqual(HTML, (web.OUTPUT / 'index.html').read_text(encoding='utf-8'))

    def test_temporary_work_file_is_not_an_explicit_skill_input(self):
        work = self.root / 'workspace/work'
        work.mkdir(parents=True)
        (work / 'site.json').write_text(json.dumps({'html':HTML}), encoding='utf-8')
        with self.assertRaisesRegex(FileNotFoundError, '缺少显式输入 site.json'):
            web.main()
        self.assertFalse(web.OUTPUT.exists())

    def test_duplicate_direct_and_artifact_inputs_are_rejected_before_output(self):
        web.INPUT_DATA.mkdir(parents=True)
        artifact = web.INPUT_ARTIFACTS / 'managed-input'
        artifact.mkdir(parents=True)
        for directory in [web.INPUT_DATA, artifact]:
            (directory / 'site.json').write_text(json.dumps({'html':HTML}), encoding='utf-8')
        with self.assertRaisesRegex(ValueError, 'site.json 不唯一.*省略同名 input_files'):
            web.main()
        self.assertFalse(web.OUTPUT.exists())

    def test_external_loading_is_rejected_but_navigation_link_is_allowed(self):
        for added in ['<script src="https://example.org/app.js"></script>',
                      '<img src="local.png">','<link rel="stylesheet" href="x.css">',
                      '<style>@import "x.css";</style>', '<style>body{background:url(https://example.org/a)}</style>',
                      '<iframe srcdoc="x"></iframe>']:
            with self.subTest(added=added), self.assertRaises(ValueError):
                web.validate_html(HTML.replace('</head>',added+'</head>'))
        web.validate_html(HTML.replace('</body>','<a href="https://example.org">来源</a></body>'))

    def test_language_viewport_and_title_are_required(self):
        for changed in [HTML.replace('lang="zh-CN"',''), HTML.replace('name="viewport"','name="other"'),
                        HTML.replace('<title>项目需求报告</title>','<title></title>')]:
            with self.assertRaises(ValueError):
                web.validate_html(changed)


if __name__ == '__main__':
    unittest.main()
