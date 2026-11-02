"""从固定上游包生成适配Skill；显式--apply才调用平台草稿/发布API，不初始化业务正文。"""
from __future__ import annotations
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import urllib.error
import urllib.parse
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[2]
PIN = '3f10983c09fcd443c7420830ab984c9d409954bf'
TOOLS = ('browser_start', 'browser_call', 'browser_stop', 'browser_tasks', 'read_image', 'read_context_reference')
DRAFT_CONTENT_FIELDS = ('version_key', 'files', 'model_invocable', 'user_invocable',
                        'activation_mode', 'conflict_group', 'required_capabilities')


class NoRedirect(urllib.request.HTTPRedirectHandler):
    """认证请求不跟随重定向，避免凭据外发和未知写入的自动重发。"""

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def prepare(source: Path, version: str) -> dict[str, bytes]:
    actual = subprocess.check_output(['git', '-C', str(source), 'rev-parse', 'HEAD'], text=True).strip()
    if actual != PIN:
        raise ValueError('upstream commit does not match frozen BrowserSkill version')
    if not re.fullmatch(r'[A-Za-z0-9._-]{1,128}', version):
        raise ValueError('invalid skill version')
    dirty = subprocess.check_output(['git', '-C', str(source), 'status', '--porcelain', '--', 'crates/bsk-cli/skill'], text=True).strip()
    if dirty:
        raise ValueError('upstream skill source must match pinned commit without local modifications')
    package = source / 'crates/bsk-cli/skill'
    files = {p.relative_to(package).as_posix(): p.read_bytes() for p in package.rglob('*') if p.is_file()}
    # 上游本地文件传输文档不属于远程能力；所有入口引用同步删除。
    files.pop('references/files.md', None)
    for name, content in list(files.items()):
        if name.endswith('.md'):
            text = content.decode('utf-8')
            text = '\n'.join(line for line in text.splitlines() if 'references/files.md' not in line) + '\n'
            files[name] = text.encode('utf-8')
    text = files['SKILL.md'].decode('utf-8')
    front, body = text.removeprefix('---\n').split('\n---\n', 1)
    if 'metadata:' in front or 'allowed-tools:' in front:
        raise ValueError('fixed upstream metadata changed')
    front += '\nallowed-tools: [' + ', '.join(TOOLS) + ']\nmetadata:\n'
    front += f'  version: "{version}"\n  upstream-commit: "{PIN}"\n  homepage: "https://github.com/Tencent/BrowserSkill"\n'
    contract = json.loads((ROOT / 'backend-java/skillforge-execution-contract/src/main/resources/browser-cli-contract.json').read_text(encoding='utf-8'))
    if contract['upstream_commit'] != PIN:
        raise ValueError('CLI adapter contract version mismatch')
    transport = {
        'upstream_commit': PIN,
        'transport': 'PLATFORM tools only; CLI examples below describe upstream semantics, never invoke a shell or code sandbox',
        'browser_start': {
            'parameters': {'browser': '<optional personal browser_id; omit to use the configured default>'},
            'connection': 'Before any task is dispatched, call with {} unless a real personal browser_id was returned by the device directory. Do not use an empty string, default or a browser product name. A missing or offline personal device suspends this invocation through TOOL_INPUT. While it is waiting, do not submit another browser_start or ask for pairing credentials: the host shows a connection card and resumes the same invocation with the exact confirmed device. A deterministic parameter rejection is not a pending connection; correct only that rejected request, and never invent a task. Pairing does not replay rejected receipts or unknown browser actions.',
            'result': 'After connection and actual start, content starts with the exact task UUID; structuredContent retains the complete receipt',
        },
        'browser_call': {
            'parameters': {'task': '<returned task UUID>', 'command': {'operation': '<official operation>', 'parameters': {}}},
            'shape': 'command is a JSON object with exactly operation and parameters. parameters is a JSON object, even when empty; never stringify either object or put operation fields beside parameters. Fill parameters with the fields declared for that operation below.',
            'examples': [
                {'task': '<returned task UUID>', 'command': {'operation': 'navigate', 'parameters': {'url': '<requested URL>'}}},
                {'task': '<returned task UUID>', 'command': {'operation': 'observe', 'parameters': {}}},
                {'task': '<returned task UUID>', 'command': {'operation': 'screenshot', 'parameters': {}}},
            ],
        },
        'browser_stop': {'parameters': {'task': '<task>'}},
        'browser_tasks': {'parameters': {}, 'result': 'content lists exact task IDs and current binding status; structuredContent.items contains the personal task directory: task, invocation, operation, status, session, run, browser, created_at, public window binding, stable error_code and cleanup_complete only. status is the current binding projection, not an earlier invocation outcome. Complete page results must be read from their original tool source.'},
        'continuation': {
            'task': 'Copy the full task UUID returned by browser_start or browser_tasks verbatim. binding.session_id is the upstream browser session, never the platform task; do not construct task:<session_id> or any guessed identifier.',
            'missing_task': 'Call browser_tasks and identify the existing task from the current session and operation intent. Several tasks or receipts may exist; never choose solely by newest receipt or list position.',
            'complete_source': 'When the short tool history omits required facts, use read_context_reference with mode=history to locate the visible browser tool record, then copy its returned source_event_id for a complete-source read. Never pass a tool resultRef or guess a context reference.',
            'known_rejection': 'A receipt or tool result marked rejected with a stable error_code is a known refusal. Correct the rejected parameters from real returned facts; it is not a successful browser action.',
            'binding_lost': 'Only active tasks may be continued. inactive with browser_task_binding_lost means the original daemon binding no longer exists; create a new task if the current goal still requires browsing. unknown is not evidence of a usable binding or completed cleanup. Never rebind an old task or replay an unknown operation automatically.',
        },
        'navigation': {
            'after_action': 'For click and press, inspect result.navigation. Its status is observed, partial or unconfirmed; new_tabs are observed candidates, not selected or newly authorized targets. Successful click/press means only the action succeeded, not that the intended page was reached.',
            'select_target': 'Compare source_tab_id, active_tab_id and new_tabs with the intended URL, title and task. If observation is missing, partial or insufficient, call tab-list. Choose the actual tab_id by intent, never the last tab or a 1-based index. Use tab-select when changing the active page.',
            'fresh_observation': 'After navigation or switching, call observe/snapshot on the chosen real tab_id. Do not reuse old-page element refs. Pass that tab_id explicitly to following page operations.',
            'observation_failure': 'Navigation observation failure does not negate a successful click/press. Inspect the current pages; never repeat the action solely because navigation is unconfirmed or the source page stayed unchanged.',
            'authorization': 'Keep the official Agent Window and tab-borrow authorization flow. A candidate tab does not grant control; do not silently operate a user-window or unauthorized tab.',
        },
        'identity': 'tenant/end_user/session/run/invocation supplied by trusted host; no model owner, --session, browser override or filesystem path',
        'screenshots': 'screenshot produces model image content; read_image consumes managed media_id; do not encode image bytes as text',
        'recording': 'record-start owns a separate official recording window, returns submitted receipt immediately; record-stop targets that task recording only',
        'unknown': 'query receipts; never replay an unknown side effect',
        'approval': 'no added platform approval or per-call confirmation; existing login, tool mounts and official extension settings apply',
        'unsupported_remote_operations': ['upload', 'download'],
        'operations': contract['operations'],
    }
    # 只动态注入现有工具技术契约；业务指令正文来自官方包，不在源码保存副本。
    adapter = '\n## NoeWise transport contract\n\nRead [the PLATFORM transport contract](references/skillforge-transport.json) before using the upstream command examples. Invoke only the mapped browser tools; the host owns CLI execution, device identity and file paths.\n'
    files['SKILL.md'] = ('---\n' + front + '\n---\n' + adapter + body).encode('utf-8')
    files['references/skillforge-transport.json'] = (json.dumps(transport, ensure_ascii=False, indent=2) + '\n').encode('utf-8')
    validate(files)
    return files


def validate(files: dict[str, bytes]) -> None:
    if len(files) > 256 or sum(map(len, files.values())) > 10 * 1024 * 1024:
        raise ValueError('package exceeds SkillPackageValidator limits')
    for name, content in files.items():
        if len(name) > 240 or len(content) > 2 * 1024 * 1024 or '..' in Path(name).parts or Path(name).is_absolute():
            raise ValueError('unsafe skill package entry')
        text = content.decode('utf-8')
        if name.endswith('.md'):
            for target in re.findall(r'\]\(([^)]+)\)', text):
                if '://' in target or target.startswith('#'):
                    continue
                path = (Path(name).parent / target.split('#')[0]).as_posix()
                if path not in files:
                    raise ValueError(f'missing package reference: {path}')
    skill = files['SKILL.md'].decode('utf-8')
    if not skill.startswith('---\nname: browser-skill\n') or '\nmetadata:\n  version: ' not in skill:
        raise ValueError('invalid generated Skill metadata')


class Platform:
    def __init__(self, base: str, token: str):
        self.base = base.rstrip('/') + '/api/v1/admin/capabilities'
        self.headers = {'Content-Type': 'application/json', 'X-Platform-Token': 'Bearer ' + token}

    def request(self, method: str, path: str, body=None, revision=None):
        headers = dict(self.headers)
        if revision is not None:
            headers['If-Match'] = str(revision)
        data = None if body is None else json.dumps(body, ensure_ascii=False).encode('utf-8')
        req = urllib.request.Request(self.base + path, data=data, headers=headers, method=method)
        try:
            with urllib.request.build_opener(NoRedirect()).open(req, timeout=60) as response:
                if response.status == 204:
                    return None
                result = json.load(response)
        except urllib.error.HTTPError as error:
            raise RuntimeError(f'platform {method} {path.split("?")[0]} returned {error.code}') from None
        if result.get('code') != 'OK':
            raise RuntimeError('platform rejected publication')
        return result['data']


def publish(api: Platform, files: dict[str, bytes], version: str, definition_id: str | None) -> dict:
    if definition_id:
        definition = api.request('GET', '/definitions/detail?definitionId=' + urllib.parse.quote(definition_id))
        if definition.get('tenant_id') is not None or definition.get('capability_type') != 'SKILL':
            raise ValueError('target definition must be a platform public Skill')
    else:
        definitions = api.request('GET', '/definitions?capability_type=SKILL')
        matches = [d for d in definitions if d.get('capability_key') == 'browser-skill']
        if len(matches) > 1:
            raise ValueError('ambiguous BrowserSkill definition')
        definition = matches[0] if matches else api.request('POST', '/definitions', {
            'capability_type': 'SKILL', 'capability_key': 'browser-skill', 'name': 'browser-skill',
            'description': 'BrowserSkill ' + PIN})
    definition_id = str(definition['id'])
    active = api.request('GET', '/skill-drafts?definitionId=' + definition_id)
    encoded = {name: base64.b64encode(content).decode('ascii') for name, content in files.items()}
    if active is not None and active.get('files') != encoded:
        # 发布后保留的草稿可按原 revision 续编；任何未发布修改仍受保护，不能恢复旧版覆盖编辑。
        published_id = active.get('published_version_id')
        frozen = None if published_id is None else api.request('GET', '/skill-drafts/version?versionId=' + str(published_id))
        if frozen is None or any(field not in active or field not in frozen
                                 or active[field] != frozen[field] for field in DRAFT_CONTENT_FIELDS):
            raise ValueError('an unrelated active editing draft exists; publication does not overwrite it')
    versions = api.request('GET', '/definitions/versions?definitionId=' + definition_id)
    existing = [v for v in versions if v.get('version_key') == version]
    if existing:
        current = api.request('GET', '/skill-drafts/version?versionId=' + str(existing[0]['id']))
        if current.get('files') != encoded:
            raise ValueError('existing version content differs; select a new version')
        if existing[0].get('status') == 'PUBLISHED':
            return {'definition_id': definition_id, 'version_id': existing[0]['id'], 'status': 'already_published'}
    draft = active or api.request('POST', '/skill-drafts', {'definition_id': definition_id, 'base_version_id': existing[0]['id'] if existing else None})
    draft = api.request('PUT', '/skill-drafts', {
        'draft_id': draft['id'], 'version_key': version, 'files': encoded,
        'model_invocable': True, 'user_invocable': True, 'activation_mode': 'ON_DEMAND',
        'conflict_group': None, 'required_capabilities': [],
    }, revision=draft['revision'])
    validation = api.request('POST', '/skill-drafts/validate', {'draft_id': draft['id'], 'revision': draft['revision']})
    if validation.get('valid') is not True:
        raise ValueError('server SkillPackageValidator rejected generated package')
    current_definition = api.request('GET', '/definitions/detail?definitionId=' + definition_id)
    receipt = api.request('POST', '/skill-drafts/publish', {'draft_id': draft['id'], 'revision': draft['revision'], 'definition_revision': current_definition['revision']})
    frozen = api.request('GET', '/skill-drafts/version?versionId=' + str(receipt['version_id']))
    if frozen.get('files') != encoded or frozen.get('version_key') != version:
        raise ValueError('published package differs from generated version')
    return {'definition_id': definition_id, 'version_id': receipt['version_id'], 'status': 'published'}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--upstream', type=Path, required=True)
    parser.add_argument('--version', required=True)
    parser.add_argument('--output', type=Path, required=True, help='生成包ZIP路径；缺省只生成，不连接服务')
    parser.add_argument('--apply', action='store_true')
    parser.add_argument('--platform-url')
    parser.add_argument('--token-env', default='SKILLFORGE_PLATFORM_TOKEN')
    parser.add_argument('--definition-id')
    args = parser.parse_args()
    files = prepare(args.upstream, args.version)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(args.output, 'w', compression=zipfile.ZIP_DEFLATED) as archive:
        for name, content in sorted(files.items()):
            entry = zipfile.ZipInfo(name, date_time=(2026, 10, 2, 0, 0, 0));entry.compress_type=zipfile.ZIP_DEFLATED
            archive.writestr(entry, content)
    report = {'status': 'generated_not_published', 'upstream_commit': PIN, 'version': args.version,
              'files': len(files), 'bytes': sum(map(len, files.values())), 'zip_sha256': hashlib.sha256(args.output.read_bytes()).hexdigest()}
    if args.apply:
        token = os.environ.get(args.token_env)
        if not args.platform_url or not token:
            raise ValueError('--apply requires --platform-url and token environment variable')
        report.update(publish(Platform(args.platform_url, token), files, args.version, args.definition_id))
    print(json.dumps(report, ensure_ascii=False))


if __name__ == '__main__':
    main()
