"""独立官方 Skill 发布使用正式平台 API；未知写入不自动重放。"""
import base64
import json
from pathlib import Path
import tempfile
import unittest

import package_skills


FILES = {'SKILL.md': b'---\nname: example\ndescription: Use for example tasks\nmetadata:\n  version: 1.0.0\n---\n# Example\n'}


class PlatformFixture:
    def __init__(self, existing=False, published=False, fail_operation=None):
        self.definition = {'id':'11', 'tenant_id':None, 'capability_type':'SKILL',
                           'capability_key':'skill:example', 'revision':1} if existing else None
        self.version = {'id':'22', 'version_key':'1.0.0', 'status':'PUBLISHED'} if published else None
        self.files = {name:base64.b64encode(value).decode() for name,value in FILES.items()}
        self.draft = None
        self.calls = []
        self.fail_operation = fail_operation

    def request(self, method, path, body=None, revision=None):
        self.calls.append((method, path, body, revision))
        if (method, path) == self.fail_operation:
            raise OSError('transport outcome unknown')
        if method == 'GET' and path == '/definitions?capability_type=SKILL':
            return [self.definition] if self.definition else []
        if method == 'POST' and path == '/definitions':
            self.definition = dict(body, id='11', tenant_id=None, revision=1)
            return self.definition
        if method == 'GET' and path.startswith('/definitions/versions?'):
            return [self.version] if self.version else []
        if method == 'GET' and path.startswith('/skill-drafts/version?'):
            return {'files':self.files, 'version_key':'1.0.0'}
        if method == 'GET' and path.startswith('/skill-drafts?'):
            return self.draft
        if method == 'POST' and path == '/skill-drafts':
            self.draft = {'id':'33', 'revision':1}
            return self.draft
        if method == 'PUT' and path == '/skill-drafts':
            assert revision == 1
            self.draft = dict(body, id='33', revision=2)
            return self.draft
        if method == 'POST' and path == '/skill-drafts/validate':
            return {'valid':True}
        if method == 'GET' and path.startswith('/definitions/detail?'):
            return self.definition
        if method == 'POST' and path == '/skill-drafts/publish':
            assert body == {'draft_id':'33', 'revision':2, 'definition_revision':1}
            self.version = {'id':'22', 'version_key':'1.0.0', 'status':'PUBLISHED'}
            return {'version_id':'22'}
        raise AssertionError((method, path))


class IndependentPublicationTest(unittest.TestCase):
    def test_first_publication_creates_platform_definition_then_validates_and_publishes(self):
        with tempfile.TemporaryDirectory() as directory:
            api = PlatformFixture()
            journal = Path(directory)/'journal.json'
            receipt = package_skills.publish(api, 'example', '1.0.0', FILES, journal)
            self.assertEqual('published', receipt['status'])
            creation = next(call for call in api.calls if call[:2] == ('POST', '/definitions'))
            self.assertEqual({'capability_type':'SKILL', 'capability_key':'skill:example',
                              'name':'example', 'description':'Use for example tasks'}, creation[2])
            self.assertEqual({'definition_create','create','update','publish'}, set(json.loads(journal.read_text())))
            self.assertTrue(all(item['state'] == 'observed' for item in json.loads(journal.read_text()).values()))
            self.assertFalse(any('mount' in call[1] or 'skill-default' in call[1] for call in api.calls))

    def test_existing_published_version_is_reused_without_any_write(self):
        with tempfile.TemporaryDirectory() as directory:
            api = PlatformFixture(existing=True, published=True)
            receipt = package_skills.publish(api, 'example', '1.0.0', FILES, Path(directory)/'journal.json')
            self.assertEqual('already_published', receipt['status'])
            self.assertTrue(all(call[0] == 'GET' for call in api.calls))

    def test_existing_definition_gets_a_new_version_without_recreating_it(self):
        with tempfile.TemporaryDirectory() as directory:
            api = PlatformFixture(existing=True)
            receipt = package_skills.publish(api, 'example', '1.0.0', FILES, Path(directory)/'journal.json')
            self.assertEqual('published', receipt['status'])
            self.assertFalse(any(call[:2] == ('POST', '/definitions') for call in api.calls))

    def test_unknown_definition_or_publish_write_is_journaled_and_never_replayed(self):
        for operation, path in [('definition_create','/definitions'), ('publish','/skill-drafts/publish')]:
            with self.subTest(operation=operation), tempfile.TemporaryDirectory() as directory:
                api = PlatformFixture(existing=operation == 'publish', fail_operation=('POST', path))
                journal = Path(directory)/'journal.json'
                with self.assertRaisesRegex(OSError, 'outcome unknown'):
                    package_skills.publish(api, 'example', '1.0.0', FILES, journal)
                self.assertEqual('unknown', json.loads(journal.read_text())[operation]['state'])
                with self.assertRaisesRegex(RuntimeError, 'query reconciliation'):
                    package_skills.publish(api, 'example', '1.0.0', FILES, journal)
                self.assertEqual(1, sum(call[:2] == ('POST', path) for call in api.calls))

    def test_same_version_with_different_content_is_not_overwritten(self):
        with tempfile.TemporaryDirectory() as directory:
            api = PlatformFixture(existing=True, published=True)
            with self.assertRaisesRegex(ValueError, 'different content'):
                package_skills.publish(api, 'example', '1.0.0', dict(FILES, extra=b'changed'), Path(directory)/'journal.json')
            self.assertTrue(all(call[0] == 'GET' for call in api.calls))


if __name__ == '__main__':
    unittest.main()
