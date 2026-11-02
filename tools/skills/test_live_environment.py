"""隔离验收归属守卫的负控，无业务写入与模型调用。"""
import copy
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from live_environment import validate


class LiveEnvironmentTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.identity = 'a' * 64
        self.batch = 'sf-fulltest-1234567890'
        self.origins = {'platform': 'http://127.0.0.1:1001', 'institution': 'http://127.0.0.1:1002',
                        'gateway': 'http://127.0.0.1:1003'}
        self.manifest = {'batch': self.batch, 'containers': [self.identity], 'services': {
            service: {'status': 'READY', 'base_url': self.origins[name], 'port': 1001 + index}
            for index, (name, service) in enumerate((('platform', 'platform-service'),
                                                    ('institution', 'institution-service'), ('gateway', 'gateway')))}}
        self.path = self.root / 'environment.json'
        self.save_manifest()
        (self.root / 'isolated.env').write_text('SPRING_DATASOURCE_URL=jdbc:postgresql://127.0.0.1:1111/skillforge\nSECRET=private\n')
        self.fixture = {'environment_manifest': str(self.path), 'postgres_container_id': self.identity,
                        'database_name': 'skillforge'}
        self.observed = {'id': self.identity, 'name': '/' + self.batch + '-pg', 'owner': self.batch,
                         'running': True, 'database': 'skillforge', 'ports': [{'HostIp': '127.0.0.1', 'HostPort': '1111'}]}

    def save_manifest(self):
        self.path.write_text(json.dumps(self.manifest), encoding='utf-8')

    def tearDown(self):
        self.temporary.cleanup()

    def test_real_database_name_requires_matching_owned_container_and_current_services(self):
        with patch('live_environment.inspect_database', return_value=self.observed) as inspect:
            scope = validate(self.fixture, self.origins)
            self.assertEqual(self.batch, scope['batch'])
            inspect.assert_called_once_with(self.identity)

    def test_missing_manifest_unowned_container_stale_origin_and_not_ready_refuse_before_inspect(self):
        changes = [dict(self.fixture, environment_manifest=str(self.root / 'missing.json')),
                   dict(self.fixture, postgres_container_id='b' * 64)]
        with patch('live_environment.inspect_database') as inspect:
            for fixture in changes:
                with self.assertRaisesRegex(RuntimeError, 'OWNED_ISOLATED'):
                    validate(fixture, self.origins)
            with self.assertRaisesRegex(RuntimeError, 'OWNED_ISOLATED'):
                validate(self.fixture, dict(self.origins, institution='http://127.0.0.1:8200'))
            self.manifest['services']['gateway']['status'] = 'STARTING'
            self.save_manifest()
            with self.assertRaisesRegex(RuntimeError, 'OWNED_ISOLATED'):
                validate(self.fixture, self.origins)
            inspect.assert_not_called()

    def test_forged_owner_id_name_database_or_public_binding_refuse(self):
        changes = {'owner': 'another-batch', 'id': 'b' * 64, 'name': '/existing-pg', 'running': False,
                   'database': 'production', 'ports': [{'HostIp': '0.0.0.0', 'HostPort': '1111'}]}
        for key, value in changes.items():
            with self.subTest(key=key), patch('live_environment.inspect_database',
                                            return_value=dict(self.observed, **{key: value})):
                with self.assertRaisesRegex(RuntimeError, 'OWNERSHIP_MISMATCH'):
                    validate(self.fixture, self.origins)

    def test_development_looking_name_and_mismatched_jdbc_do_not_bypass_ownership(self):
        with patch('live_environment.inspect_database') as inspect:
            for database in ('skillforge_dev_example', 'skillforge/other'):
                with self.assertRaisesRegex(RuntimeError, 'OWNED_ISOLATED'):
                    validate(dict(self.fixture, database_name=database), self.origins)
            (self.root / 'isolated.env').write_text('SPRING_DATASOURCE_URL=jdbc:postgresql://10.0.0.1:1111/skillforge\n')
            with self.assertRaisesRegex(RuntimeError, 'OWNED_ISOLATED'):
                validate(self.fixture, self.origins)
            inspect.assert_not_called()


if __name__ == '__main__':
    unittest.main()
