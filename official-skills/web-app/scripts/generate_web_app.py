"""校验并交付完整单文件网页，布局和交互由任务内容决定。"""
import json
import re
from html.parser import HTMLParser
from pathlib import Path
from zipfile import ZIP_DEFLATED, ZipFile

INPUT_DATA = Path('/input/data')
INPUT_ARTIFACTS = Path('/input/artifacts')
OUTPUT = Path('/workspace/output')
THEMES = json.loads((Path(__file__).resolve().parent.parent / 'resources/themes.json').read_text(encoding='utf-8'))['presets']
DEFAULT_THEME = {'font_face': 'Noto Sans CJK SC', 'accent': '176B87', 'background': 'F7F9FC', 'text': '172B3A'}


def _rgb(color):
    return [int(color[index:index + 2], 16) for index in (0, 2, 4)]


def _luminance(color):
    channels = [value / 255 for value in _rgb(color)]
    linear = [value / 12.92 if value <= 0.04045 else ((value + 0.055) / 1.055) ** 2.4 for value in channels]
    return sum(value * weight for value, weight in zip(linear, (0.2126, 0.7152, 0.0722)))


def contrast(first, second):
    values = sorted((_luminance(first), _luminance(second)))
    return (values[1] + 0.05) / (values[0] + 0.05)


def _blend(first, second, amount):
    # 与 Node 入口一致，使用非负值四舍五入，避免两种语言的银行家舍入差异。
    return ''.join(f'{int(left * (1 - amount) + right * amount + 0.5):02X}' for left, right in zip(_rgb(first), _rgb(second)))


def _chart_color(color, text, background):
    for amount in (0, 0.15, 0.3, 0.45, 0.6, 0.75, 0.9, 1):
        candidate = _blend(color, text, amount)
        if contrast(candidate, background) >= 3:
            return candidate
    return text


def resolve_theme(value):
    if not isinstance(value, dict) or set(value) - {'preset', *DEFAULT_THEME}:
        raise ValueError('theme 必须是对象，仅支持 preset/font_face/accent/background/text')
    preset = value.get('preset')
    if 'preset' in value and (not isinstance(preset, str) or preset not in THEMES):
        raise ValueError('theme.preset 必须是已支持的主题名称')
    theme = dict(DEFAULT_THEME)
    if preset:
        theme.update({key: THEMES[preset][key] for key in DEFAULT_THEME})
    for key, item in value.items():
        if key == 'preset':
            continue
        if key == 'font_face':
            if not isinstance(item, str) or not item.strip() or len(item) > 100 or re.search(r'[\x00-\x1f<>"\';\\]', item):
                raise ValueError('theme.font_face 必须是安全的非空字体名称')
            theme[key] = item.strip()
        elif isinstance(item, str) and re.fullmatch(r'[0-9a-fA-F]{6}', item):
            theme[key] = item.upper()
        else:
            raise ValueError(f'theme.{key} 必须是六位十六进制颜色')
    if contrast(theme['text'], theme['background']) < 4.5:
        raise ValueError('theme.text 与 background 对比度须至少为 4.5；调整颜色后重新生成')
    surface = _blend(theme['background'], theme['text'], 0.045)
    if contrast(theme['text'], surface) < 4.5:
        surface = theme['background']
    secondary = _blend(theme['text'], theme['background'], 0.22)
    accent_text = theme['accent'] if min(contrast(theme['accent'], theme['background']), contrast(theme['accent'], surface)) >= 4.5 else theme['text']
    chart_palette = ([theme['accent']] + [color for color in THEMES[preset]['palette'] if color != THEMES[preset]['accent']])[:4] if preset else [theme['accent'], 'D27B40', '668D3C', '815BA1']
    theme.update({'preset': preset, 'name': THEMES[preset]['name'] if preset else '自定义', 'surface': surface,
                  'muted': secondary if min(contrast(secondary, theme['background']), contrast(secondary, surface)) >= 4.5 else theme['text'],
                  'border': _blend(theme['background'], theme['text'], 0.25), 'accent_text': accent_text,
                  'on_accent': 'FFFFFF' if contrast('FFFFFF', theme['accent']) >= contrast('000000', theme['accent']) else '000000',
                  'chart_colors': [_chart_color(color, theme['text'], theme['background']) for color in chart_palette]})
    return theme


def theme_css(theme):
    roles = ('background', 'text', 'accent', 'surface', 'muted', 'border', 'accent_text', 'on_accent')
    variables = ';'.join(f'--sf-{key.replace("_", "-")}:#{theme[key]}' for key in roles)
    return (f':root{{{variables};--sf-font:"{theme["font_face"]}","Noto Sans CJK SC","Microsoft YaHei",sans-serif}}'
            'html{background:var(--sf-background);color:var(--sf-text)}'
            'body{background:var(--sf-background);color:var(--sf-text);font-family:var(--sf-font)}'
            'button,input,select,textarea{font-family:inherit}a{color:var(--sf-accent-text)}'
            '.sf-surface{background:var(--sf-surface);color:var(--sf-text);border-color:var(--sf-border)}'
            '.sf-muted{color:var(--sf-muted)}.sf-accent{background:var(--sf-accent);color:var(--sf-on-accent)}')


def locate_input(filename):
    """只消费本次显式挂载的唯一输入，并区分漏传和重复选择。"""
    matches = []
    direct = INPUT_DATA / filename
    if direct.is_file():
        matches.append(direct)
    if INPUT_ARTIFACTS.is_dir():
        matches.extend(artifact / filename for artifact in sorted(INPUT_ARTIFACTS.iterdir())
                       if artifact.is_dir() and (artifact / filename).is_file())
    if not matches:
        raise FileNotFoundError(f'缺少显式输入 {filename}：通过 input_files 提供 UTF-8 文本，或通过 artifact_inputs 挂载已收集文件；/workspace/work 不会自动成为 Skill 输入')
    if len(matches) > 1:
        raise ValueError(f'显式输入 {filename} 不唯一：input_files 与 artifact_inputs 中的同名输入只保留一份；使用已收集文件时省略同名 input_files，不要用空字符串占位')
    return matches[0]


class OfflinePage(HTMLParser):
    """检查静态加载引用；JavaScript 行为必须另经离线浏览器验证。"""
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.tags = set()
        self.viewport = False
        self.language = False
        self.title = ''
        self.in_title = False
        self.errors = []
        self.styles = []
        self.in_style = False
        self.head_end_position = None

    def handle_starttag(self, tag, attrs):
        self.tags.add(tag)
        values = dict(attrs)
        if tag == 'html':
            self.language = bool(values.get('lang'))
        if tag == 'meta' and values.get('name', '').lower() == 'viewport':
            self.viewport = bool(values.get('content'))
        if tag == 'meta' and values.get('http-equiv', '').lower() == 'refresh':
            self.errors.append('不支持自动跳转页面')
        if tag in {'iframe', 'object', 'embed', 'base'}:
            self.errors.append(f'单文件离线网页不支持 {tag}')
        for key in ('src', 'poster', 'srcset'):
            value = values.get(key)
            if value and not (key != 'srcset' and value.startswith('data:')):
                self.errors.append(f'{tag}.{key} 必须内联为 data URI')
        if tag == 'link' and values.get('rel', '').lower() in {'stylesheet', 'preload', 'modulepreload', 'prefetch'}:
            self.errors.append('样式和依赖必须内联')
        if tag == 'form' and values.get('action') not in (None, '', '#'):
            self.errors.append('离线表单不能提交到外部地址')
        if values.get('style'):
            self.styles.append(values['style'])
        self.in_title = tag == 'title' or self.in_title
        self.in_style = tag == 'style' or self.in_style

    def handle_endtag(self, tag):
        if tag == 'head':
            self.head_end_position = self.getpos()
        if tag == 'title':
            self.in_title = False
        if tag == 'style':
            self.in_style = False

    def handle_data(self, text):
        if self.in_title:
            self.title += text
        if self.in_style:
            self.styles.append(text)


def validate_html(source):
    if not isinstance(source, str) or not source.strip():
        raise ValueError('html 必须为完整的非空 HTML 字符串')
    if len(source.encode('utf-8')) > 8 * 1024 * 1024:
        raise ValueError('单文件网页超过 8 MiB')
    page = OfflinePage()
    page.feed(source)
    page.close()
    if not {'html', 'head', 'body', 'title'}.issubset(page.tags) or not page.title.strip():
        raise ValueError('需要 html/head/title/body 和非空网页标题')
    if not page.viewport or not page.language:
        raise ValueError('需要 viewport 与 html lang')
    for style in page.styles:
        if re.search(r'@import\b', style, re.I):
            page.errors.append('CSS 不允许 @import')
        for match in re.finditer(r'url\(\s*[\"\']?([^\)\"\']+)', style, re.I):
            if not match.group(1).strip().startswith(('data:', '#')):
                page.errors.append('CSS 图片和字体必须内联')
    if page.errors:
        raise ValueError('; '.join(sorted(set(page.errors))))
    return {'title': page.title.strip(), 'static_references': 'inline',
            'browser_interaction': 'requires_offline_browser_check'}


def main():
    data = json.loads(locate_input('site.json').read_text(encoding='utf-8'))
    if not isinstance(data, dict) or set(data) - {'html', 'bundle_zip', 'theme'}:
        raise ValueError('site.json 仅支持 html、可选 bundle_zip 和 theme')
    bundle = data.get('bundle_zip', False)
    if not isinstance(bundle, bool):
        raise ValueError('bundle_zip 必须是布尔值')
    summary = validate_html(data.get('html'))
    source = data['html']
    if 'theme' in data:
        theme = resolve_theme(data['theme'])
        page = OfflinePage()
        page.feed(source)
        if page.head_end_position is None:
            raise ValueError('应用 theme 需要明确的 head 结束标签')
        line, column = page.head_end_position
        offset = sum(len(item) for item in source.splitlines(keepends=True)[:line - 1]) + column
        source = source[:offset] + '<style data-skillforge-theme="1">' + theme_css(theme) + '</style>' + source[offset:]
        summary['theme'] = {key: theme[key] for key in ('preset', 'background', 'text', 'accent')}
        validate_html(source)
    OUTPUT.mkdir(parents=True, exist_ok=True)
    target = OUTPUT / 'index.html'
    target.write_text(source, encoding='utf-8')
    if bundle:
        with ZipFile(OUTPUT / 'site.zip', 'w', compression=ZIP_DEFLATED) as archive:
            archive.write(target, arcname=target.name)
    print(json.dumps(summary, ensure_ascii=False))


if __name__ == '__main__':
    main()
