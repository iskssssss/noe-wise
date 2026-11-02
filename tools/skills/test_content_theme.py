"""实际 HTML 入口的主题、内容保留和失败边界；不访问平台或外部服务。"""
import contextlib
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
PACKAGES = ROOT / 'official-skills'
spec = importlib.util.spec_from_file_location('theme_web', PACKAGES / 'web-app/scripts/generate_web_app.py')
web = importlib.util.module_from_spec(spec)
spec.loader.exec_module(web)
EXAMPLE = json.loads((PACKAGES / 'web-app/examples/site-themed.json').read_text(encoding='utf-8'))


class ContentThemeTest(unittest.TestCase):
    def generate(self, data, output):
        web.INPUT_DATA = output.parent / 'input'
        web.INPUT_ARTIFACTS = output.parent / 'artifacts'
        web.OUTPUT = output
        web.INPUT_DATA.mkdir(exist_ok=True)
        (web.INPUT_DATA / 'site.json').write_text(json.dumps(data), encoding='utf-8')
        with contextlib.redirect_stdout(io.StringIO()):
            web.main()

    def test_each_named_theme_reaches_actual_html_and_preserves_interaction(self):
        with tempfile.TemporaryDirectory() as temp:
            for name in web.THEMES:
                with self.subTest(preset=name):
                    case = Path(temp) / name
                    case.mkdir()
                    self.generate({**EXAMPLE, 'theme': {'preset': name}}, case / 'output')
                    html = (case / 'output/index.html').read_text(encoding='utf-8')
                    theme = web.resolve_theme({'preset': name})
                    self.assertIn(f'--sf-background:#{theme["background"]}', html)
                    self.assertIn(f'--sf-text:#{theme["text"]}', html)
                    self.assertIn(EXAMPLE['html'].split('<script>')[1].split('</script>')[0], html)
                    self.assertGreaterEqual(web.contrast(theme['text'], theme['background']), 4.5)
                    self.assertGreaterEqual(web.contrast(theme['text'], theme['surface']), 4.5)
                    self.assertGreaterEqual(web.contrast(theme['on_accent'], theme['accent']), 4.5)
                    self.assertEqual(['index.html'], sorted(path.name for path in (case / 'output').iterdir()))

    def test_custom_color_override_is_used_and_source_head_like_script_is_preserved(self):
        with tempfile.TemporaryDirectory() as temp:
            data = {**EXAMPLE, 'theme': {'preset': 'ocean-depths', 'accent': 'f9a620'}}
            data['html'] = data['html'].replace('<script>', '<script>const preserve="</head>";')
            self.generate(data, Path(temp) / 'output')
            html = (Path(temp) / 'output/index.html').read_text(encoding='utf-8')
            self.assertIn('--sf-accent:#F9A620', html)
            self.assertIn('const preserve="</head>";', html)
            self.assertLess(html.index('data-skillforge-theme'), html.index('</head>'))

    def test_invalid_themes_fail_before_any_output(self):
        bad = [None, [], {'preset': None}, {'preset': 'missing'}, {'background': 'FFFFFF', 'text': 'FFFFFE'}, {'font_face': 'a";}</style><script>'}, {'accent': '#123456'}, {'unexpected': '123456'}]
        for theme in bad:
            with self.subTest(theme=theme), tempfile.TemporaryDirectory() as temp:
                output = Path(temp) / 'output'
                with self.assertRaises(ValueError):
                    self.generate({**EXAMPLE, 'theme': theme}, output)
                self.assertFalse(output.exists())

    def test_theme_resources_match_across_independent_packages(self):
        self.assertEqual((PACKAGES / 'web-app/resources/themes.json').read_bytes(), (PACKAGES / 'presentation/resources/themes.json').read_bytes())

    def test_communication_examples_keep_unknowns_and_use_supported_blocks(self):
        document = json.loads((PACKAGES / 'document/examples/document-weekly-update.json').read_text(encoding='utf-8'))
        self.assertEqual(['进展', '下一步计划', '问题与依赖'], [section['heading'] for section in document['sections']])
        self.assertIn('待确认', json.dumps(document, ensure_ascii=False))
        document_spec = importlib.util.spec_from_file_location('content_document', PACKAGES / 'document/scripts/generate_document.py')
        renderer = importlib.util.module_from_spec(document_spec)
        document_spec.loader.exec_module(renderer)
        markdown, assets = renderer.render_markdown(*renderer.normalize_document(document))
        self.assertIn('## 下一步计划', markdown)
        self.assertIn('| 计划内容待确认 | 待确认 | 待确认 |', markdown)
        self.assertEqual([], assets)
        for skill in ('document', 'presentation'):
            reference = (PACKAGES / skill / 'references/enterprise-writing.md').read_text(encoding='utf-8')
            self.assertIn('不授予', reference)
            self.assertIn('待确认', reference)
            self.assertTrue((PACKAGES / skill / 'references/upstream/internal-comms/LICENSE.txt').is_file())


if __name__ == '__main__':
    unittest.main()
