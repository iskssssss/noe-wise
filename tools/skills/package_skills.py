"""校验固定来源并构建官方 Skill ZIP；只在显式 apply 时独立发布。"""
from __future__ import annotations
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import urllib.error
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[2]
SKILLS = ROOT / 'official-skills'
LOCK = Path(__file__).with_name('upstream-lock.json')
NAMES = tuple(entry['name'] for entry in json.loads((SKILLS / 'index.json').read_text(encoding='utf-8'))['packages'])


class NoRedirect(urllib.request.HTTPRedirectHandler):
    """认证请求不跟随重定向，避免凭据外发和未知写入的自动重发。"""

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def normalized(data: bytes, path: str) -> bytes:
    if path.endswith(('.md', '.json', '.py', '.mjs', '.txt')):
        return data.decode('utf-8').replace('\r\n', '\n').encode('utf-8')
    return data


def sha(data):
    return hashlib.sha256(data).hexdigest()


def frozen_content_matches(encoded, files):
    """已发布源码与构包文本只允许确定性的 UTF-8 换行归一差量。"""
    if set(encoded) != set(files):
        return False
    return all(normalized(base64.b64decode(encoded[path], validate=True), path) == content
               for path, content in files.items())


def publication_receipt(did, version_id, status, frozen, encoded):
    return {'definition_id':did, 'version_id':str(version_id), 'status':status,
            'content_comparison':'exact' if frozen['files'] == encoded else 'utf8_lf',
            'frozen_file_sha256':{path:sha(base64.b64decode(value, validate=True))
                                  for path,value in frozen['files'].items()}}


def dependency_name(name, language):
    return re.sub(r'[-_.]+', '-', name).lower() if language == 'python' else name


def verify_sources():
    lock = json.loads(LOCK.read_text(encoding='utf-8'))
    for source in lock['sources']:
        if not re.fullmatch('[0-9a-f]{40}', source['commit']):
            raise ValueError('upstream must be pinned to a full commit')
        file_root = source.get('file_root', 'official-skills')
        if file_root not in ('official-skills', 'workspace'):
            raise ValueError('unknown source file root')
        base = ROOT if file_root == 'workspace' else SKILLS
        for item in source['files']:
            path = PurePosixPath(item['package_path'])
            target = base / path
            if path.is_absolute() or '..' in path.parts or '\\' in str(path) or ':' in str(path) or target.is_symlink():
                raise ValueError('unsafe frozen source path')
            if not target.resolve().is_relative_to(base.resolve()):
                raise ValueError('frozen source escapes root')
            data = target.read_bytes()
            if sha(normalized(data, item['package_path'])) != item['normalized_sha256']:
                raise ValueError('frozen upstream changed: ' + item['package_path'])
    return lock


def verify_dependency_declarations():
    lock = json.loads(Path(__file__).with_name('dependencies-lock.json').read_text(encoding='utf-8'))
    for name, entry in lock['entries'].items():
        manifest = json.loads((SKILLS / name / 'manifest.json').read_text(encoding='utf-8'))
        matching = [candidate for candidate in [dict(manifest['entry'], dependencies=manifest.get('dependencies', [])),
                    *manifest.get('alternate_entries', [])] if candidate['language'] == entry['language']
                    and (not entry.get('script_path') or candidate['script_path'] == entry['script_path'])]
        if len(matching) != 1:
            raise ValueError('dependency lock requires one exact runtime entry: ' + name)
        declared = matching[0].get('dependencies', [])
        expected = {dependency_name(p['name'], entry['language']):p['version'] for p in entry['packages']}
        if {dependency_name(p['name'], entry['language']):p['version'] for p in declared} != expected or len(declared) != len(expected):
            raise ValueError('full dependency declarations differ from frozen installation: ' + name)
    return lock


def package_files(name):
    index = json.loads((SKILLS / 'index.json').read_text(encoding='utf-8'))
    entry = next(x for x in index['packages'] if x['name'] == name)
    directory = SKILLS / name
    expected = set(entry['files'])
    actual = {p.relative_to(directory).as_posix() for p in directory.rglob('*')
              if p.is_file() and '__pycache__' not in p.parts and p.suffix != '.pyc'}
    if expected != actual:
        raise ValueError(f'index files differ for {name}: {sorted(expected ^ actual)}')
    files = {}
    for path in sorted(expected):
        parts = PurePosixPath(path)
        file = directory / path
        if parts.is_absolute() or '..' in parts.parts or '\\' in path or ':' in path or file.is_symlink():
            raise ValueError('unsafe package file: ' + path)
        content = normalized(file.read_bytes(), path)
        if len(content) > 2 * 1024 * 1024:
            raise ValueError('oversized package file: ' + path)
        files[path] = content
    if len(files) > 256 or sum(map(len, files.values())) > 10 * 1024 * 1024:
        raise ValueError('package budget exceeded')
    match = re.search(r'^  version: ([0-9]+\.[0-9]+\.[0-9]+)$', files['SKILL.md'].decode(), re.M)
    if not match:
        raise ValueError('package requires exact metadata.version')
    return files, match.group(1)


def build(name, output):
    files, version = package_files(name)
    output.mkdir(parents=True, exist_ok=True)
    target = output / f'{name}-{version}.zip'
    with zipfile.ZipFile(target, 'w') as archive:
        for path, content in sorted(files.items()):
            info = zipfile.ZipInfo(path, date_time=(1980, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o100644 << 16
            archive.writestr(info, content)
    return {'name':name, 'version':version, 'zip':str(target), 'sha256':sha(target.read_bytes()),
            'files':{path:sha(data) for path,data in files.items()}}, files


class Platform:
    def __init__(self, base, token):
        self.base = base.rstrip('/') + '/api/v1/admin/capabilities'
        self.token = token

    def request(self, method, path, body=None, revision=None):
        headers = {'Content-Type':'application/json', 'X-Platform-Token':'Bearer ' + self.token}
        if revision is not None:
            headers['If-Match'] = str(revision)
        request = urllib.request.Request(self.base + path, method=method, headers=headers,
            data=None if body is None else json.dumps(body, ensure_ascii=False).encode())
        try:
            with urllib.request.build_opener(NoRedirect()).open(request, timeout=90) as response:
                if response.status == 204:
                    return None
                envelope = json.load(response)
        except urllib.error.HTTPError as error:
            raise RuntimeError(f'platform {method} {path.split("?")[0]} returned {error.code}') from None
        if envelope.get('code') != 'OK':
            raise RuntimeError('platform rejected Skill operation')
        return envelope['data']


def publish(api, name, version, files, journal_path):
    journal = json.loads(journal_path.read_text(encoding='utf-8')) if journal_path.exists() else {}

    def mutate(operation, method, path, body, revision=None):
        fingerprint = sha(json.dumps(body,sort_keys=True,ensure_ascii=False).encode())
        if operation in journal:
            raise RuntimeError('unknown or previously observed Skill write requires query reconciliation: ' + operation)
        journal[operation] = {'state':'unknown','request_sha256':fingerprint}
        journal_path.write_text(json.dumps(journal,indent=2)+'\n',encoding='utf-8')
        result = api.request(method,path,body,revision)
        journal[operation]['state']='observed'
        journal_path.write_text(json.dumps(journal,indent=2)+'\n',encoding='utf-8')
        return result
    definitions = api.request('GET', '/definitions?capability_type=SKILL')
    matches = [x for x in definitions if x['capability_key'] == 'skill:' + name]
    if not matches:
        skill = files['SKILL.md'].decode('utf-8')
        frontmatter = re.match(r'\A---\r?\n(.*?)\r?\n---(?:\r?\n|\Z)', skill, re.S)
        description = re.search(r'^description: ([^\r\n]+)$', frontmatter.group(1), re.M) if frontmatter else None
        if not description or description.group(1).strip() in ('>', '|', '>-', '|-'):
            raise ValueError('official package requires a single-line description: ' + name)
        created = mutate('definition_create', 'POST', '/definitions', {
            'capability_type':'SKILL', 'capability_key':'skill:' + name,
            'name':name, 'description':description.group(1).strip()})
        matches = [created]
    if (len(matches) != 1 or matches[0].get('tenant_id') is not None
            or matches[0].get('capability_type') != 'SKILL'
            or matches[0].get('capability_key') != 'skill:' + name):
        raise ValueError('requires one exact platform SKILL definition: ' + name)
    did = str(matches[0]['id'])
    encoded = {path:base64.b64encode(content).decode() for path,content in files.items()}
    versions = api.request('GET', '/definitions/versions?definitionId=' + did)
    existing = next((x for x in versions if x['version_key'] == version), None)
    if existing:
        frozen = api.request('GET', '/skill-drafts/version?versionId=' + str(existing['id']))
        if not frozen_content_matches(frozen['files'], files):
            raise ValueError('same version has different content: ' + name)
        if existing['status'] == 'PUBLISHED':
            return publication_receipt(did, existing['id'], 'already_published', frozen, encoded)
    draft = api.request('GET', '/skill-drafts?definitionId=' + did)
    if draft and (draft.get('files') != encoded or draft.get('version_key') != version):
        raise ValueError('unrelated editing draft exists: ' + name)
    if not draft:
        draft = mutate('create', 'POST', '/skill-drafts', {'definition_id':did, 'base_version_id':existing['id'] if existing else None})
        draft = mutate('update', 'PUT', '/skill-drafts', {'draft_id':draft['id'],'version_key':version,'files':encoded,
            'model_invocable':True,'user_invocable':True,'activation_mode':'ON_DEMAND','conflict_group':None,'required_capabilities':[]}, draft['revision'])
    validation = api.request('POST', '/skill-drafts/validate', {'draft_id':draft['id'],'revision':draft['revision']})
    if validation.get('valid') is not True:
        raise ValueError('server package validation failed: ' + name)
    definition = api.request('GET', '/definitions/detail?definitionId=' + did)
    receipt = mutate('publish', 'POST', '/skill-drafts/publish', {'draft_id':draft['id'],'revision':draft['revision'],'definition_revision':definition['revision']})
    frozen = api.request('GET', '/skill-drafts/version?versionId=' + str(receipt['version_id']))
    if frozen['files'] != encoded or frozen['version_key'] != version:
        raise ValueError('published package mismatch: ' + name)
    return publication_receipt(did, receipt['version_id'], 'published', frozen, encoded)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--skill', action='append', choices=NAMES)
    parser.add_argument('--apply', action='store_true')
    parser.add_argument('--platform-url', default='http://127.0.0.1:8100')
    parser.add_argument('--token-env', default='SKILLFORGE_PLATFORM_TOKEN')
    args = parser.parse_args()
    lock = verify_sources()
    dependency_lock = verify_dependency_declarations()
    api = None
    if args.apply:
        token = os.environ.get(args.token_env)
        if not token:
            parser.error('--apply requires token in the named environment variable')
        api = Platform(args.platform_url, token)
    report = {'sources':lock['sources'],'dependency_lock_sha256':sha(normalized(Path(__file__).with_name('dependencies-lock.json').read_bytes(), 'dependencies-lock.json')),'packages':[]}
    for name in args.skill or NAMES:
        item, files = build(name, args.output)
        if api:
            item['publication'] = publish(api, name, item['version'], files, args.output / f'{name}-{item["version"]}-publication.json')
        report['packages'].append(item)
        # 写入每个已观察回执，网络中断后重跑先查询原版本，不自动覆盖或重新生成编号。
        (args.output / 'package-report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(json.dumps([{'name':x['name'],'version':x['version'],'sha256':x['sha256'],
                      'publication':None if not x.get('publication') else
                          {key:x['publication'][key] for key in ('version_id','status','content_comparison')}}
                     for x in report['packages']]))


if __name__ == '__main__':
    main()
