"""Agent Tool 组合只读就绪检查的回归。"""
import contextlib
import io
import unittest

import check_agent_readiness as readiness


class FakeApi:
    def __init__(self, details, mount_sets, deployments):
        self.details = details
        self.mount_sets = mount_sets
        self.deployments = deployments
        self.calls = []
        self.versions = [{'id': 11, 'version_key': 'builtin:1', 'content_hash': 'catalog-hash'}]

    def request(self, method, path, body=None, headers=None):
        self.calls.append((method, path))
        if method != 'GET':
            raise AssertionError(f'readiness must not write: {method} {path}')
        if path == '/capabilities/platform-tools':
            return [{'name': 'search_tools', 'content_hash': 'catalog-hash'}]
        if path == '/capabilities/definitions?capability_type=TOOL&status=PUBLISHED':
            return [{'id': 10, 'capability_key': 'tool:search_tools', 'default_version_id': None}]
        if path == '/capabilities/definitions/versions?definitionId=10&status=PUBLISHED':
            return self.versions
        if path == '/capabilities/deployments':
            return self.deployments
        if path.startswith('/agents/detail?agentId='):
            return self.details[path.rsplit('=', 1)[1]]
        if path.startswith('/agents/capability-mounts?agentId='):
            return self.mount_sets[path.rsplit('=', 1)[1]]
        raise AssertionError(f'unexpected request {path}')


def valid_mount(version=11, status='ACTIVE', deployment='search-tools', revision=2, definition=10):
    return {
        'mount_id': 'mount-1', 'capability_type': 'TOOL',
        'capability_definition_id': definition, 'capability_version_id': version,
        'tool_config': {
            'tool_deployment_id': deployment,
            'tool_deployment_revision': revision,
        },
        'status': status,
    }


def valid_deployment(version=11, status='ACTIVE', revision=2):
    return {
        'deployment_key': 'search-tools', 'tool_version_id': version,
        'provider_type': 'PLATFORM', 'provider_ref': 'search_tools',
        'environment': 'PRODUCTION', 'status': status, 'revision': revision,
    }


class AgentReadinessTest(unittest.TestCase):
    def test_ready_agent_uses_catalog_backed_production_mount(self):
        api = FakeApi(
            {'42': {'status': 'published'}},
            {'42': {'generation': 7, 'mounts': [valid_mount(), {'capability_type': 'SKILL'}]}},
            [valid_deployment()],
        )
        report = readiness.check_agent_readiness(api, ('42',), ('search_tools',))
        self.assertTrue(report['ready'])
        self.assertEqual([], report['agents'][0]['issues'])
        self.assertEqual('tool_composition_only', report['scope'])
        self.assertTrue(all(method == 'GET' for method, _ in api.calls))

    def test_published_versions_must_match_release_key_and_catalog_hash(self):
        for versions, category in (
            ([], 'unpublished'),
            ([{'id': 11, 'version_key': 'builtin:1', 'content_hash': 'stale'}],
             'version_or_schema_drift'),
        ):
            with self.subTest(versions=versions):
                api = FakeApi({'42': {'status': 'published'}},
                              {'42': {'generation': 7, 'mounts': [valid_mount()]}},
                              [valid_deployment()])
                api.versions = versions
                report = readiness.check_agent_readiness(api, ('42',), ('search_tools',))
                self.assertFalse(report['ready'])
                self.assertEqual([category], [item['category'] for item in report['agents'][0]['issues']])

    def test_current_published_version_does_not_depend_on_historical_version_key(self):
        api = FakeApi({'42': {'status': 'published'}},
                      {'42': {'generation': 7, 'mounts': [valid_mount()]}},
                      [valid_deployment()])
        api.versions[0]['version_key'] = 'release-2026-11'
        self.assertTrue(readiness.check_agent_readiness(api, ('42',), ('search_tools',))['ready'])
        self.assertEqual('9007199254740993', readiness.require_positive({'id': '9007199254740993'}, 'id', 'version'))
        with self.assertRaises(readiness.ReadinessError):
            readiness.require_positive({'id': True}, 'id', 'version')

    def test_reports_unpublished_missing_and_drift_separately(self):
        api = FakeApi(
            {'42': {'status': 'draft'}, '43': {'status': 'published'}},
            {
                '42': {'generation': 1, 'mounts': []},
                '43': {'generation': 2, 'mounts': [
                    valid_mount(version=12),
                    valid_mount(version=12, status='DISABLED', definition=99),
                ]},
            },
            [valid_deployment(version=12)],
        )
        report = readiness.check_agent_readiness(api, ('42', '43'), ('search_tools',))
        self.assertFalse(report['ready'])
        first = {item['category'] for item in report['agents'][0]['issues']}
        second = {item['category'] for item in report['agents'][1]['issues']}
        self.assertEqual({'unpublished', 'missing_mount'}, first)
        self.assertEqual({'unpublished', 'version_or_schema_drift'}, second)

    def test_cli_requires_explicit_positive_targets_and_prints_no_secrets(self):
        for arguments in ([], ['--agent-id', '0'], ['--agent-id', 'bad']):
            with self.subTest(arguments=arguments), contextlib.redirect_stderr(io.StringIO()):
                with self.assertRaises(SystemExit):
                    readiness.parse_args(arguments)
        parsed = readiness.parse_args([
            '--agent-id', '42', '--agent-id', '43', '--required-tool', 'search_tools',
        ])
        self.assertEqual(['42', '43'], parsed.agent_id)
        self.assertEqual(['search_tools'], parsed.required_tool)


if __name__ == '__main__':
    unittest.main()
